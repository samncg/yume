package com.yume.app.engine

import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Append-only spill log for one tile store (Stage 3b eviction).
 *
 * Each record is a 32-byte header followed by its payload:
 *
 *     magic 'YTS1' | key (8) | w (2) | h (2) | kind (1) | 0 (3)
 *     | payloadLen (4) | payloadCrc32 (4) | headerCrc32 (4, over the 28 bytes before)
 *
 * kind 0 = raw premultiplied pixel bytes (exactly `copyPixelsToBuffer`),
 * kind 1 = the same bytes through Deflater level 1 (used only when smaller).
 *
 * Records are never rewritten in place: a newer record for the same key makes
 * the older one garbage, and [compact] rewrites the live set into a fresh file
 * once garbage passes half the file. The in-memory [index] maps a tile key to
 * its newest record. The file is scratch only (crash recovery is Stage 3c), so
 * it is deleted when the store's cache closes.
 *
 * Thread safety: all index and write access is synchronised on this object.
 * Reads use positional channel reads against an immutable [Record] snapshot;
 * [generation] changes on compaction so a prefetch that raced one is discarded.
 */
class SpillFile(private val file: File) {

    class Record(
        val key: Long,
        val offset: Long,
        val payloadLen: Int,
        val kind: Int,
        val w: Int,
        val h: Int,
        val crc: Int,
        val generation: Int
    ) {
        val totalLen: Long get() = HEADER + payloadLen.toLong()
    }

    class CorruptRecordException(msg: String) : IOException(msg)

    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private val index = HashMap<Long, Record>()

    @Volatile var generation: Int = 0
        private set
    /** Bytes in the file (live + garbage). */
    var fileBytes: Long = 0L
        private set
    /** Bytes belonging to the newest record of a live key. */
    var liveBytes: Long = 0L
        private set
    var recordsWritten: Long = 0L
        private set
    var bytesWritten: Long = 0L
        private set
    var compactions: Int = 0
        private set

    /** Debug failure injection: every write throws while this is set. */
    @Volatile var failWrites: Boolean = false

    private val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
    private val crc = CRC32()

    private fun open(): FileChannel {
        channel?.let { return it }
        file.parentFile?.mkdirs()
        val r = RandomAccessFile(file, "rw")
        r.setLength(0L)
        raf = r
        val ch = r.channel
        channel = ch
        fileBytes = 0L
        return ch
    }

    @Synchronized
    fun lookup(key: Long): Record? = index[key]

    @Synchronized
    fun has(key: Long): Boolean = index.containsKey(key)

    @Synchronized
    fun drop(key: Long) {
        val r = index.remove(key) ?: return
        liveBytes -= r.totalLen
    }

    @Synchronized
    fun dropAll() {
        index.clear()
        liveBytes = 0L
        // Nothing live: start the log over instead of carrying the garbage.
        channel?.let {
            runCatching { it.truncate(0L) }
            fileBytes = 0L
        }
    }

    /**
     * Appends [tile]'s pixels under [key] and makes it the newest record.
     * Throws [IOException] on any failure; the index is then unchanged, so the
     * caller keeps the tile resident and nothing is lost.
     */
    /** A tile's payload, compressed (or not) and checksummed, ready to append. */
    class Packed(val w: Int, val h: Int, val kind: Int, val data: ByteArray, val len: Int, val crc: Int)

    private class PackScratch {
        val deflater = Deflater(1)
        var raw: ByteBuffer = ByteBuffer.allocate(0)
        var pack = ByteArray(0)
        val crc = CRC32()
    }

    /**
     * Appends [tile]'s pixels under [key] and makes it the newest record.
     * Throws [IOException] on any failure; the index is then unchanged, so the
     * caller keeps the tile resident and nothing is lost.
     */
    fun write(key: Long, tile: Bitmap): Record {
        if (failWrites) throw IOException("injected spill write failure")
        return writePacked(key, pack(tile, copy = false))
    }

    /**
     * Appends an already [pack]ed payload under [key]. Packing can run on any
     * thread (so many tiles compress at once); appending is serialised here.
     */
    @Synchronized
    fun writePacked(key: Long, pk: Packed): Record {
        if (failWrites) throw IOException("injected spill write failure")
        val ch = open()
        val offset = fileBytes
        header.clear()
        header.putInt(MAGIC)
        header.putLong(key)
        header.putShort(pk.w.toShort())
        header.putShort(pk.h.toShort())
        header.put(pk.kind.toByte())
        header.put(0); header.put(0); header.put(0)
        header.putInt(pk.len)
        header.putInt(pk.crc)
        crc.reset()
        crc.update(header.array(), 0, HEADER - 4)
        header.putInt(crc.value.toInt())
        header.flip()
        var pos = offset
        while (header.hasRemaining()) pos += ch.write(header, pos)
        val body = ByteBuffer.wrap(pk.data, 0, pk.len)
        while (body.hasRemaining()) pos += ch.write(body, pos)
        fileBytes = pos

        val rec = Record(key, offset, pk.len, pk.kind, pk.w, pk.h, pk.crc, generation)
        index.put(key, rec)?.let { liveBytes -= it.totalLen }
        liveBytes += rec.totalLen
        recordsWritten++
        bytesWritten += rec.totalLen
        return rec
    }

    /**
     * Reads [rec] into [into] (which must be a mutable w x h ARGB_8888 bitmap).
     * Verifies both CRCs; throws [CorruptRecordException] on any mismatch and
     * [IOException] on a short read. Safe to call from the prefetch thread.
     */
    fun read(rec: Record, into: Bitmap, scratch: ReadScratch) {
        val ch = synchronized(this) {
            if (rec.generation != generation) throw IOException("record superseded by compaction")
            channel ?: throw IOException("spill file closed")
        }
        val hb = scratch.header
        hb.clear()
        readFully(ch, hb, rec.offset)
        hb.flip()
        val c = scratch.crc
        c.reset()
        c.update(hb.array(), 0, HEADER - 4)
        hb.order(ByteOrder.LITTLE_ENDIAN)
        if (hb.getInt(0) != MAGIC) throw CorruptRecordException("bad magic at ${rec.offset}")
        if (hb.getInt(HEADER - 4) != c.value.toInt()) {
            throw CorruptRecordException("header crc mismatch at ${rec.offset}")
        }
        if (hb.getLong(4) != rec.key || hb.getInt(20) != rec.payloadLen) {
            throw CorruptRecordException("header does not match index at ${rec.offset}")
        }
        val body = scratch.payload(rec.payloadLen)
        readFully(ch, ByteBuffer.wrap(body, 0, rec.payloadLen), rec.offset + HEADER)
        c.reset()
        c.update(body, 0, rec.payloadLen)
        if (c.value.toInt() != rec.crc || hb.getInt(24) != rec.crc) {
            throw CorruptRecordException("payload crc mismatch at ${rec.offset}")
        }
        val n = rec.w * rec.h * 4
        if (into.width != rec.w || into.height != rec.h || into.byteCount != n) {
            throw IOException("tile size mismatch")
        }
        val raw = if (rec.kind == KIND_DEFLATE) {
            val out = scratch.raw(n)
            val inf = scratch.inflater
            inf.reset()
            inf.setInput(body, 0, rec.payloadLen)
            var got = 0
            while (got < n && !inf.finished()) {
                val k = inf.inflate(out, got, n - got)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                got += k
            }
            if (got != n) throw CorruptRecordException("inflate produced $got of $n bytes")
            out
        } else {
            if (rec.payloadLen != n) throw CorruptRecordException("raw length ${rec.payloadLen} != $n")
            body
        }
        into.copyPixelsFromBuffer(ByteBuffer.wrap(raw, 0, n))
    }

    /** Per-thread read buffers (render thread and prefetcher each own one). */
    class ReadScratch {
        val header: ByteBuffer = ByteBuffer.allocate(HEADER)
        val crc = CRC32()
        val inflater = Inflater()
        private var body = ByteArray(0)
        private var rawOut = ByteArray(0)
        fun payload(n: Int): ByteArray {
            if (body.size < n) body = ByteArray(n)
            return body
        }
        fun raw(n: Int): ByteArray {
            if (rawOut.size < n) rawOut = ByteArray(n)
            return rawOut
        }
    }

    /** True when at least half of the file is garbage and it is worth rewriting. */
    @Synchronized
    fun shouldCompact(): Boolean =
        fileBytes >= MIN_COMPACT_BYTES && liveBytes * 2 < fileBytes

    /**
     * Copies every live record into a new file and swaps it in. Records keep
     * their bytes (CRCs are re-checked on the way). On failure the old file and
     * index stay in use.
     */
    @Synchronized
    fun compact(scratch: ReadScratch) {
        val ch = channel ?: return
        val tmpFile = File(file.path + ".compact")
        val tmp = RandomAccessFile(tmpFile, "rw")
        val newIndex = HashMap<Long, Record>()
        var pos = 0L
        try {
            tmp.setLength(0L)
            val out = tmp.channel
            for ((k, r) in index) {
                val buf = ByteBuffer.allocate(r.totalLen.toInt())
                readFully(ch, buf, r.offset)
                // Re-verify the header CRC so compaction never launders a
                // corrupt record into a fresh-looking one.
                val c = scratch.crc
                c.reset()
                c.update(buf.array(), 0, HEADER - 4)
                val hb = ByteBuffer.wrap(buf.array()).order(ByteOrder.LITTLE_ENDIAN)
                if (hb.getInt(HEADER - 4) != c.value.toInt()) {
                    throw CorruptRecordException("header crc mismatch during compaction")
                }
                buf.flip()
                val at = pos
                while (buf.hasRemaining()) pos += out.write(buf, pos)
                newIndex[k] = Record(k, at, r.payloadLen, r.kind, r.w, r.h, r.crc, generation + 1)
            }
            out.force(false)
            tmp.close()
            raf?.close()
            if (!tmpFile.renameTo(file)) throw IOException("rename failed")
            val r = RandomAccessFile(file, "rw")
            raf = r
            channel = r.channel
            index.clear()
            index.putAll(newIndex)
            fileBytes = pos
            liveBytes = pos
            generation++
            compactions++
        } catch (e: IOException) {
            runCatching { tmp.close() }
            tmpFile.delete()
            if (raf == null || channel?.isOpen != true) {
                // The swap failed after the old handle was closed: reopen it.
                val r = RandomAccessFile(file, "rw")
                raf = r
                channel = r.channel
            }
            throw e
        }
    }

    /** Debug: flips one payload byte of [key]'s record on disk. */
    @Synchronized
    fun corruptForTest(key: Long) {
        val r = index[key] ?: return
        val ch = channel ?: return
        val b = ByteBuffer.allocate(1)
        readFully(ch, b, r.offset + HEADER)
        b.flip()
        val v = b.get(0)
        b.clear()
        b.put((v.toInt() xor 0x5A).toByte())
        b.flip()
        ch.write(b, r.offset + HEADER)
    }

    /** Debug: cuts the file inside [key]'s record. */
    @Synchronized
    fun truncateForTest(key: Long) {
        val r = index[key] ?: return
        channel?.truncate(r.offset + HEADER + r.payloadLen / 2)
        fileBytes = r.offset + HEADER + r.payloadLen / 2
    }

    @Synchronized
    fun close(delete: Boolean) {
        runCatching { channel?.close() }
        runCatching { raf?.close() }
        channel = null
        raf = null
        index.clear()
        liveBytes = 0L
        fileBytes = 0L
        if (delete) file.delete()
    }

    companion object {
        private val packScratch = ThreadLocal.withInitial { PackScratch() }

        /** Bytes of the trial compression that decides whether a tile is worth deflating. */
        private const val PROBE = 16 * 1024

        /**
         * Compresses [tile] for [writePacked]. Thread-safe. A tile whose first
         * rows barely compress (noise, photos, dense texture) is stored raw:
         * deflating it would cost several ms and save almost nothing. With
         * [copy] false the payload lives in a per-thread buffer, valid until
         * this thread packs again.
         */
        fun pack(tile: Bitmap, copy: Boolean = true): Packed {
            val sc = packScratch.get()!!
            val w = tile.width
            val h = tile.height
            val n = tile.byteCount
            if (sc.raw.capacity() < n) sc.raw = ByteBuffer.allocate(n)
            val rawBuf = sc.raw
            rawBuf.clear()
            rawBuf.limit(n)
            tile.copyPixelsToBuffer(rawBuf)
            val raw = rawBuf.array()
            if (sc.pack.size < n + 64) sc.pack = ByteArray(n + 64)
            val packBuf = sc.pack
            val deflater = sc.deflater
            var useDeflate = true
            if (n > PROBE * 4) {
                deflater.reset()
                deflater.setInput(raw, 0, PROBE)
                deflater.finish()
                var got = 0
                while (!deflater.finished() && got < packBuf.size) {
                    got += deflater.deflate(packBuf, got, packBuf.size - got)
                }
                if (got > PROBE * 9 / 10) useDeflate = false
            }
            var packed = 0
            if (useDeflate) {
                deflater.reset()
                deflater.setInput(raw, 0, n)
                deflater.finish()
                while (!deflater.finished() && packed < packBuf.size) {
                    packed += deflater.deflate(packBuf, packed, packBuf.size - packed)
                }
                useDeflate = deflater.finished() && packed < n
            }
            val payload = if (useDeflate) packBuf else raw
            val len = if (useDeflate) packed else n
            sc.crc.reset()
            sc.crc.update(payload, 0, len)
            val data = if (copy) payload.copyOf(len) else payload
            return Packed(w, h, if (useDeflate) KIND_DEFLATE else KIND_RAW, data, len, sc.crc.value.toInt())
        }

        const val HEADER = 32
        const val MAGIC = 0x31535459 // "YTS1" little endian
        const val KIND_RAW = 0
        const val KIND_DEFLATE = 1
        const val MIN_COMPACT_BYTES = 4L * 1024L * 1024L

        private fun readFully(ch: FileChannel, buf: ByteBuffer, at: Long) {
            var pos = at
            while (buf.hasRemaining()) {
                val n = ch.read(buf, pos)
                if (n < 0) throw IOException("short read at $pos")
                pos += n
            }
        }
    }
}
