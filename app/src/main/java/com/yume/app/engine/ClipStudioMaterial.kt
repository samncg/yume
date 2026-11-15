package com.yume.app.engine

import android.graphics.Bitmap
import java.util.zip.Inflater
import kotlin.math.max

/**
 * Reads the image out of a Clip Studio Paint material (the brush tips and
 * textures embedded in `.sut` / `.sutg` files).
 *
 * A material is a tar archive whose `data/material_0.layer` entry is a
 * "C2F" container: an 8-byte signature, then chunks of
 * `[length LE][tag][data][crc]` (HEAD, dATA..., TAIL). The dATA chunks laid
 * end to end are a SQLite file. A chunk whose first byte is 1 is protected
 * (the file's first pages: header and schema) and cannot be read; the rest
 * are plain pages.
 *
 * The image is stored as 256 x 256 tiles in that database: every tile is a
 * record `BlockDataBeginChunk` (UTF-16BE), then big-endian index,
 * unpacked size, width, height and a not-empty flag, then (if not empty) a
 * length pair and a zlib stream. Tiles of a single byte per pixel are the
 * mask (alpha: 255 = paint); five bytes per pixel are an alpha plane plus
 * colour. The image size is in a `Parameter` record (width, height).
 *
 * Large records continue on overflow pages, so the table-leaf cells are
 * read with SQLite's own payload rules to put them back together. The page
 * size lives in the protected header, so every usual size is tried and the
 * one under which the most tiles decompress wins.
 */
internal object ClipStudioMaterial {

    private val BEGIN = "BlockDataBeginChunk".toByteArray(Charsets.UTF_16BE)
    private val PARAM = "Parameter".toByteArray(Charsets.UTF_16BE)

    /** The material's mask, as greyscale values (0..255, 255 = paint); null if none. */
    class Mask(val width: Int, val height: Int, val values: IntArray)

    fun decode(fileData: ByteArray): Mask? {
        val layer = tarEntry(fileData) { it.endsWith(".layer") } ?: return null
        val images = images(layer)
        // The mask: one byte per pixel, the run with the most painted tiles.
        val best = images.maxWithOrNull(
            compareBy<Image>({ if (it.bytesPerPixel == 1) 1 else 0 }, { it.tiles.count { t -> t.data != null } })
        ) ?: return null
        return assemble(best)
    }

    // ------------------------------------------------------------------ tar

    private fun tarEntry(fd: ByteArray, want: (String) -> Boolean): ByteArray? {
        var off = 0
        while (off + 512 <= fd.size) {
            var end = off
            while (end < off + 100 && fd[end] != 0.toByte()) end++
            val name = String(fd, off, end - off, Charsets.ISO_8859_1)
            if (name.isEmpty()) return null
            var sizeStr = ""
            for (k in 124 until 136) {
                val c = fd[off + k].toInt().toChar()
                if (c in '0'..'7') sizeStr += c else if (sizeStr.isNotEmpty()) break
            }
            val size = sizeStr.toLongOrNull(8)?.toInt() ?: return null
            if (size < 0 || off + 512 + size > fd.size) return null
            if (want(name)) return fd.copyOfRange(off + 512, off + 512 + size)
            off += 512 + ((size + 511) / 512) * 512
        }
        return null
    }

    // ------------------------------------------------------- C2F / SQLite

    private fun u32le(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun u32be(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun u16be(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun isC2f(d: ByteArray) = d.size > 8 && d[1] == 'C'.code.toByte() && d[2] == '2'.code.toByte() && d[3] == 'F'.code.toByte()

    /** The dATA chunks end to end: the SQLite file, protected parts as zeros. */
    private fun sqliteImage(layer: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(layer.size)
        var i = 8
        while (i + 8 <= layer.size) {
            val ln = u32le(layer, i)
            if (ln < 0 || i + 8 + ln > layer.size) break
            val tag = String(layer, i + 4, 4, Charsets.ISO_8859_1)
            if (tag == "dATA" && ln >= 2) {
                if (layer[i + 8].toInt() == 0) out.write(layer, i + 10, ln - 2)
                else out.write(ByteArray(max(0, ln - 10)))
            }
            i += 12 + ln
        }
        return out.toByteArray()
    }

    /** The raw dATA chunks (fallback when the pages cannot be put together). */
    private fun dechunked(layer: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(layer.size)
        var i = 8
        while (i + 8 <= layer.size) {
            val ln = u32le(layer, i)
            if (ln < 0 || i + 8 + ln > layer.size) break
            if (String(layer, i + 4, 4, Charsets.ISO_8859_1) == "dATA") out.write(layer, i + 8, ln)
            i += 12 + ln
        }
        return out.toByteArray()
    }

    private fun varint(b: ByteArray, at: Int): LongArray {
        var v = 0L
        var i = at
        for (k in 0 until 9) {
            val c = b[i++].toInt() and 0xFF
            if (k == 8) return longArrayOf((v shl 8) or c.toLong(), i.toLong())
            v = (v shl 7) or (c and 0x7F).toLong()
            if (c < 0x80) break
        }
        return longArrayOf(v, i.toLong())
    }

    /** Every readable table-leaf record payload, overflow pages followed, end to end. */
    private fun payloads(img: ByteArray, pageSize: Int): ByteArray {
        val u = pageSize
        val pages = img.size / u
        if (pages < 2) return ByteArray(0)
        val x = u - 35
        val m = ((u - 12) * 32 / 255) - 23
        val out = java.io.ByteArrayOutputStream()
        for (pn in 2..pages) {
            val base = (pn - 1) * u
            if (img[base].toInt() != 0x0D) continue
            val ncell = u16be(img, base + 3)
            if (8 + 2 * ncell > u) continue
            for (c in 0 until ncell) {
                try {
                    val ptr = u16be(img, base + 8 + 2 * c)
                    if (ptr < 8 || ptr >= u) continue
                    val pv = varint(img, base + ptr)
                    val p = pv[0]
                    val rv = varint(img, pv[1].toInt())
                    var j = rv[1].toInt()
                    if (p <= x) {
                        if (j + p <= base + u) out.write(img, j, p.toInt())
                        continue
                    }
                    if (p > 64L * 1024 * 1024) continue
                    val k = m + ((p - m) % (u - 4)).toInt()
                    val local = if (k <= x) k else m
                    if (j + local + 4 > base + u) continue
                    val buf = java.io.ByteArrayOutputStream(p.toInt())
                    buf.write(img, j, local)
                    var next = u32be(img, j + local)
                    var guard = 0
                    while (next in 1..pages && buf.size() < p && guard < 1_000_000) {
                        val ob = (next - 1) * u
                        buf.write(img, ob + 4, u - 4)
                        next = u32be(img, ob)
                        guard++
                    }
                    val bytes = buf.toByteArray()
                    out.write(bytes, 0, minOf(bytes.size.toLong(), p).toInt())
                } catch (e: IndexOutOfBoundsException) {
                    continue
                }
            }
        }
        return out.toByteArray()
    }

    // --------------------------------------------------------------- tiles

    private class Tile(val index: Int, val unpacked: Int, val w: Int, val h: Int, val data: ByteArray?)

    private class Image(val width: Int, val height: Int, val tiles: List<Tile>) {
        val bytesPerPixel: Int get() = tiles.first().let { it.unpacked / max(1, it.w * it.h) }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        var i = max(0, from)
        val last = hay.size - needle.size
        val first = needle[0]
        while (i <= last) {
            if (hay[i] == first) {
                var k = 1
                while (k < needle.size && hay[i + k] == needle[k]) k++
                if (k == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun inflate(z: ByteArray, off: Int, len: Int, expect: Int): ByteArray? {
        val inf = Inflater()
        return try {
            inf.setInput(z, off, len)
            val out = ByteArray(expect)
            var got = 0
            while (got < expect && !inf.finished()) {
                val n = inf.inflate(out, got, expect - got)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                got += n
            }
            if (got == expect) out else null
        } catch (e: java.util.zip.DataFormatException) {
            null
        } finally {
            inf.end()
        }
    }

    /** Tile runs (restarting at index 0) matched to a Parameter size whose grid fits. */
    private fun parse(d: ByteArray): List<Image> {
        val params = ArrayList<IntArray>()
        val runs = ArrayList<ArrayList<Tile>>()
        var cur = ArrayList<Tile>()
        var i = 0
        while (true) {
            val b = indexOf(d, BEGIN, i)
            val p = indexOf(d, PARAM, i)
            if (b < 0 && p < 0) break
            if (p >= 0 && (b < 0 || p < b)) {
                val q = p + PARAM.size
                if (q + 8 <= d.size) {
                    val w = u32be(d, q)
                    val h = u32be(d, q + 4)
                    if (w in 1..20000 && h in 1..20000) params.add(intArrayOf(w, h))
                }
                i = q
                continue
            }
            var q = b + BEGIN.size
            if (q + 20 > d.size) break
            val idx = u32be(d, q)
            val unpacked = u32be(d, q + 4)
            val tw = u32be(d, q + 8)
            val th = u32be(d, q + 12)
            val notEmpty = u32be(d, q + 16)
            q += 20
            var data: ByteArray? = null
            if (tw !in 1..4096 || th !in 1..4096 || unpacked <= 0 || unpacked > 64 * 1024 * 1024) {
                i = q
                continue
            }
            if (notEmpty != 0 && q + 8 <= d.size) {
                val len = u32le(d, q + 4)
                if (len > 0 && q + 8 + len <= d.size) {
                    data = inflate(d, q + 8, len, unpacked)
                    q += 8 + len
                }
            }
            if (cur.isNotEmpty() && (idx == 0 || cur.last().unpacked != unpacked)) {
                runs.add(cur)
                cur = ArrayList()
            }
            cur.add(Tile(idx, unpacked, tw, th, data))
            i = q
        }
        if (cur.isNotEmpty()) runs.add(cur)
        val out = ArrayList<Image>()
        for (run in runs) {
            if (run.none { it.data != null }) continue
            val tw = run[0].w
            val th = run[0].h
            val n = run.maxOf { it.index } + 1
            val size = params.firstOrNull { ((it[0] + tw - 1) / tw) * ((it[1] + th - 1) / th) == n }
                ?: intArrayOf(n * tw, th)
            out.add(Image(size[0], size[1], run))
        }
        return out
    }

    private fun images(layer: ByteArray): List<Image> {
        if (!isC2f(layer)) return parse(layer)
        val img = sqliteImage(layer)
        var best: List<Image> = emptyList()
        var bestOk = 0
        for (u in intArrayOf(4096, 1024, 2048, 8192, 16384, 32768, 65536)) {
            val imgs = parse(payloads(img, u))
            val ok = imgs.sumOf { im -> im.tiles.count { it.data != null } }
            if (ok > bestOk) {
                bestOk = ok
                best = imgs
            }
        }
        if (bestOk > 0) return best
        return parse(dechunked(layer))
    }

    private fun assemble(im: Image): Mask? {
        val tw = im.tiles[0].w
        val th = im.tiles[0].h
        val per = im.bytesPerPixel
        val cols = (im.width + tw - 1) / tw
        val w = im.width
        val h = im.height
        if (w.toLong() * h > 40_000_000L) return null
        val out = IntArray(w * h)
        var any = false
        for (t in im.tiles) {
            val data = t.data ?: continue
            val bx = (t.index % cols) * tw
            val by = (t.index / cols) * th
            val plane = tw * th
            for (y in 0 until th) {
                val yy = by + y
                if (yy >= h) break
                for (x in 0 until tw) {
                    val xx = bx + x
                    if (xx >= w) break
                    val s = y * tw + x
                    val a = data[s].toInt() and 0xFF
                    val v = if (per >= 5) {
                        // Alpha plane, then colour: paint is opaque and dark.
                        val o = plane + s * 4
                        val r = data[o].toInt() and 0xFF
                        val g = data[o + 1].toInt() and 0xFF
                        val b = data[o + 2].toInt() and 0xFF
                        a * (255 - (r * 77 + g * 151 + b * 28 shr 8)) / 255
                    } else a
                    if (v > 0) any = true
                    out[yy * w + xx] = v
                }
            }
        }
        return if (any) Mask(w, h, out) else null
    }
}
