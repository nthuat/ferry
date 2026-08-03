package dev.thuat.ferry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Resumable download over HTTP range requests.
 *
 * Reach for this only when the file is big enough that losing progress matters — a video, an
 * offline catalog, a model file. Product images are the wrong size for it: OkHttp's disk cache
 * already handles those, and a partial JPEG is worth nothing anyway.
 *
 * For a user-visible large download that should survive the process dying, prefer the platform's
 * DownloadManager. This class is for when you need control DownloadManager won't give you —
 * custom auth, your own retry policy, progress piped into your own UI.
 *
 * The protocol, in three headers:
 *
 *   Accept-Ranges: bytes      server advertises it supports this at all
 *   Range: bytes=1024-        client asks for "everything from byte 1024 on"
 *   Content-Range: bytes 1024-4095/4096   server confirms what it actually sent
 */
class ResumableDownloader(
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Downloads [url] to [target], resuming from a previous partial attempt if one exists.
     *
     * Progress lands in a sibling `.part` file and is only renamed onto [target] once the byte
     * count matches what the server declared, so a reader of [target] never sees a truncated file.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Result<File> = withContext(dispatcher) {
        val part = File(target.parentFile, "${target.name}.part")
        val validatorFile = File(target.parentFile, "${target.name}.validator")

        try {
            target.parentFile?.mkdirs()
            val haveBytes = if (part.exists()) part.length() else 0L
            val validator = validatorFile.takeIf { it.exists() && haveBytes > 0 }?.readText()

            // No validator means no way to ask "is this still the file I started?". The server
            // would answer 206 and hand back bytes from whatever it holds now, which we would
            // append to a stale prefix. Re-downloading is cheap; silent corruption is not.
            val resumeFrom = if (validator != null) haveBytes else 0L

            client.newCall(rangeRequest(url, resumeFrom, validator)).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code} for $url"))
                }

                // The bug this whole class exists to avoid: we asked for a range and the server
                // sent the whole file instead — either it ignores Range, or If-Range failed
                // because the file changed. Appending here splices two different files together
                // and produces a plausible-sized, permanently corrupt result.
                val append = response.continuesFrom(resumeFrom)
                val startFrom = if (append) resumeFrom else 0L

                response.validator()?.let { validatorFile.writeText(it) }

                val total = totalBytes(response, startFrom)
                val written = writeBody(response, part, append, startFrom, total, onProgress)

                if (total != null && written != total) {
                    return@withContext Result.failure(
                        IOException("incomplete: wrote $written of $total bytes"),
                    )
                }

                if (target.exists() && !target.delete()) {
                    return@withContext Result.failure(IOException("cannot replace ${target.name}"))
                }
                if (!part.renameTo(target)) {
                    return@withContext Result.failure(IOException("cannot finalise ${target.name}"))
                }
                validatorFile.delete()
                Result.success(target)
            }
        } catch (e: IOException) {
            // The .part file is deliberately left behind — it is the resume point.
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            // Request.Builder.url throws this, not IOException, for a malformed URL. ModelHub is a
            // public interface, so RemoteFile.url can come from a third-party adapter and reach here
            // as anything at all — and nothing may throw across this boundary.
            Result.failure(IOException("invalid url: $url", e))
        }
    }

    private fun rangeRequest(url: String, haveBytes: Long, validator: String?): Request =
        Request.Builder()
            .url(url)
            // Ranges are offsets into the *encoded* representation. OkHttp adds Accept-Encoding:
            // gzip whenever neither it nor Range is set, and transparently decompresses — so a
            // first request would fill the .part file with decompressed bytes, and the resume
            // offset computed from its length would index into a different byte stream entirely.
            // Asking for identity on every request keeps disk and protocol talking about the same
            // bytes. This is what MNN's ModelFileDownloader does, and the reason is easy to miss.
            .header("Accept-Encoding", "identity")
            .apply {
                if (haveBytes > 0) {
                    header("Range", "bytes=$haveBytes-")
                    // Without If-Range, resuming a file that changed on the server splices the
                    // tail of the new file onto the head of the old one. With it, the server
                    // answers 200 and we start clean.
                    validator?.let { header("If-Range", it) }
                }
            }
            .build()

    private suspend fun writeBody(
        response: Response,
        part: File,
        append: Boolean,
        startFrom: Long,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        val body = response.body ?: throw IOException("empty body")
        var written = startFrom

        FileOutputStream(part, append).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    // Cancellation has to be checked by hand: a blocking read is not suspending,
                    // so cancelling the coroutine would otherwise not stop the transfer.
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    written += read
                    onProgress(written, total)
                }
                out.fd.sync()
            }
        }
        return written
    }

    /**
     * Total size of the whole resource, not of this response.
     *
     * On a 206 that is the number after the slash in Content-Range; Content-Length would only
     * describe the slice just sent. A server is also allowed to put an asterisk after that slash
     * when it genuinely doesn't know the total, which is why this is nullable rather than guessed.
     */
    private fun totalBytes(response: Response, startFrom: Long): Long? {
        response.header("Content-Range")?.let { header ->
            return header.substringAfterLast('/').trim().toLongOrNull()
        }
        val length = response.body?.contentLength() ?: -1L
        return if (length >= 0) startFrom + length else null
    }

    /** ETag is the strong validator; Last-Modified is the fallback for servers that omit it. */
    private fun Response.validator(): String? =
        header("ETag") ?: header("Last-Modified")

    /**
     * Whether this response continues from [resumeFrom], or restarts the file.
     *
     * The status code alone is not enough to decide. ModelScope honours a Range request correctly
     * and still answers `200 OK` rather than `206`, carrying a valid `Content-Range`. Reading only
     * the code there makes resume impossible against that hub: every attempt is treated as a
     * restart, writes the tail at offset zero, and fails the length check forever.
     *
     * Content-Range's start offset is the signal that actually distinguishes the cases. A server
     * that ignored the Range header sends no Content-Range at all, and one that answered with the
     * whole body reports a start of 0, which cannot match a non-zero resume point. So this stays
     * strict about the case that corrupts files while accepting the one that is merely
     * non-compliant.
     */
    private fun Response.continuesFrom(resumeFrom: Long): Boolean {
        if (code == HTTP_PARTIAL_CONTENT) return true
        if (resumeFrom <= 0L) return false
        val start = header("Content-Range")
            ?.substringAfter("bytes ")
            ?.substringBefore('-')
            ?.trim()
            ?.toLongOrNull()
            ?: return false
        return start == resumeFrom
    }

    private companion object {
        const val HTTP_PARTIAL_CONTENT = 206
        const val BUFFER_BYTES = 8 * 1024
    }
}
