package com.yume.app.ai

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The AI models Yume can use. They are not part of the app: each is
 * downloaded once, the first time a tool needs it, from its publisher's own
 * public release, checked against a pinned SHA-256 and kept on the device.
 * Everything runs on the tablet; nothing is ever uploaded.
 */
enum class AiModel(
    val label: String,
    val purpose: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    /** Null: the download is the model. Otherwise a zip; these entries are kept (by file name). */
    val zipEntries: List<String>?,
    /** The file the runtime opens. */
    val modelFile: String,
    val license: String
) {
    SegmentIllustration(
        label = "Background removal: illustrations",
        purpose = "IS-Net (anime), for drawn characters and objects",
        url = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-anime.onnx",
        sha256 = "f15622d853e8260172812b657053460e20806f04b9e05147d49af7bed31a6e99",
        bytes = 176_069_933L,
        zipEntries = null,
        modelFile = "isnet-anime.onnx",
        license = "Apache-2.0 (IS-Net, anime-seg by SkyTNT)"
    ),
    SegmentPhoto(
        label = "Background removal: photos",
        purpose = "IS-Net (general use), for photos and objects",
        url = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-general-use.onnx",
        sha256 = "60920e99c45464f2ba57bee2ad08c919a52bbf852739e96947fbb4358c0d964a",
        bytes = 178_648_008L,
        zipEntries = null,
        modelFile = "isnet-general-use.onnx",
        license = "Apache-2.0 (IS-Net / DIS by Xuebin Qin et al.)"
    ),
    UpscaleFast(
        label = "Upscaling: fast",
        purpose = "Real-ESRGAN General x4v3",
        url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.67.0/real_esrgan_general_x4v3-onnx-float.zip",
        sha256 = "4f3619c89eb0727ff9170b977578d160fa4d696680a0cac4c7f870b52973033b",
        bytes = 4_541_448L,
        zipEntries = listOf("real_esrgan_general_x4v3.onnx", "real_esrgan_general_x4v3.data"),
        modelFile = "real_esrgan_general_x4v3.onnx",
        license = "BSD-3-Clause (Real-ESRGAN by Xintao Wang et al.)"
    ),
    UpscaleDetailed(
        label = "Upscaling: detailed",
        purpose = "Real-ESRGAN x4plus",
        url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_x4plus/releases/v0.67.0/real_esrgan_x4plus-onnx-float.zip",
        sha256 = "2ac78e1073a67a7577116ac5c44af78f6cce9642efca3c8e62675d68255fa91c",
        bytes = 62_171_800L,
        zipEntries = listOf("real_esrgan_x4plus.onnx", "real_esrgan_x4plus.data"),
        modelFile = "real_esrgan_x4plus.onnx",
        license = "BSD-3-Clause (Real-ESRGAN by Xintao Wang et al.)"
    );

    val megabytes: Int get() = ((bytes + 524_288L) / 1_048_576L).toInt()
}

/** Where the models live and how they arrive. */
class ModelStore(context: Context) {
    // Not in the backed-up files: the models can always be fetched again.
    private val root = File(context.noBackupFilesDir, "models").apply { mkdirs() }

    fun dir(model: AiModel) = File(root, model.name)
    fun file(model: AiModel) = File(dir(model), model.modelFile)
    fun isReady(model: AiModel): Boolean = File(dir(model), READY).exists() && file(model).exists()

    /** Bytes on disk for installed models. */
    fun installedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(model: AiModel) {
        dir(model).deleteRecursively()
        File(root, model.name + PART).delete()
    }

    class Cancelled : IOException("Download cancelled")

    /**
     * Downloads, checks and installs [model] (resuming a partial download).
     * [progress] gets (bytes so far, total). Throws on failure or when
     * [cancelled] turns true. Runs on the caller's (background) thread.
     */
    fun download(model: AiModel, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        if (isReady(model)) return
        val part = File(root, model.name + PART)
        val free = StatFs(root.path).availableBytes
        val need = model.bytes * 2 - part.length()
        if (free < need) throw IOException("Not enough free space: ${model.megabytes * 2} MB needed")
        var attempt = 0
        while (true) {
            try {
                fetch(model, part, cancelled, progress)
                break
            } catch (c: Cancelled) {
                throw c
            } catch (e: IOException) {
                // A dropped connection resumes from where it stopped.
                if (++attempt >= 4) throw e
                Thread.sleep(1500L * attempt)
            }
        }
        if (part.length() != model.bytes || sha256(part) != model.sha256) {
            part.delete()
            throw IOException("The download did not match the published file; please try again")
        }
        install(model, part)
        part.delete()
    }

    private fun fetch(model: AiModel, part: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        if (part.length() > model.bytes) part.delete()
        if (part.length() == model.bytes) return
        val have = part.length()
        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Yume")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL && have > 0
            if (code != HttpURLConnection.HTTP_OK && !append) throw IOException("Server answered $code")
            var done = if (append) have else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > (1 shl 20) || done == model.bytes) {
                            lastReport = done
                            progress(done, model.bytes)
                        }
                    }
                }
            }
            if (done != model.bytes) throw IOException("Download ended early")
        } finally {
            conn.disconnect()
        }
    }

    /** Unpacks into a temporary folder, then swaps it in: never a half-installed model. */
    private fun install(model: AiModel, part: File) {
        val tmp = File(root, model.name + ".installing").apply { deleteRecursively(); mkdirs() }
        val entries = model.zipEntries
        if (entries == null) {
            part.copyTo(File(tmp, model.modelFile), overwrite = true)
        } else {
            val found = HashSet<String>()
            ZipInputStream(FileInputStream(part)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // Only the expected files, by bare name: no paths from the archive.
                    val name = e.name.substringAfterLast('/')
                    if (e.isDirectory || name !in entries) continue
                    FileOutputStream(File(tmp, name)).use { zip.copyTo(it) }
                    found.add(name)
                }
            }
            if (found.size != entries.size) {
                tmp.deleteRecursively()
                throw IOException("The download is missing files")
            }
        }
        File(tmp, READY).writeText(model.sha256)
        val target = dir(model)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) {
            tmp.deleteRecursively()
            throw IOException("Could not install the model")
        }
    }

    companion object {
        private const val PART = ".part"
        private const val READY = "ready"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
