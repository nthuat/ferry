package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLParserException
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use

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
    private val client: HttpClient,
    private val fileSystem: FileSystem = defaultFileSystem(),
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
        target: Path,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Result<Path> = withContext(dispatcher) {
        val parent = target.parent
            ?: return@withContext Result.failure(IOException("target has no parent directory: $target"))
        val part = parent / "${target.name}.part"
        val validatorFile = parent / "${target.name}.validator"

        try {
            fileSystem.createDirectories(parent)
            val haveBytes = fileSystem.metadataOrNull(part)?.size ?: 0L
            val validator = if (haveBytes > 0 && fileSystem.exists(validatorFile)) {
                fileSystem.read(validatorFile) { readUtf8() }
            } else null

            // No validator means no way to ask "is this still the file I started?" — restart.
            val resumeFrom = if (validator != null) haveBytes else 0L

            client.prepareGet(url) {
                expectSuccess = false
                // Ranges are offsets into the *encoded* representation; identity keeps disk and
                // protocol talking about the same bytes. (The OkHttp engine would otherwise add
                // transparent gzip exactly like bare OkHttp did.)
                header(HttpHeaders.AcceptEncoding, "identity")
                if (resumeFrom > 0) {
                    header(HttpHeaders.Range, "bytes=$resumeFrom-")
                    validator?.let { header(HttpHeaders.IfRange, it) }
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    return@execute Result.failure(
                        IOException("HTTP ${response.status.value} for $url"),
                    )
                }

                val append = response.continuesFrom(resumeFrom)
                val startFrom = if (append) resumeFrom else 0L

                response.validator()?.let { v ->
                    fileSystem.write(validatorFile) { writeUtf8(v) }
                }

                val total = totalBytes(response, startFrom)
                val written =
                    writeBody(response.bodyAsChannel(), part, append, total, onProgress)

                if (total != null && written != total) {
                    return@execute Result.failure(
                        IOException("incomplete: wrote $written of $total bytes"),
                    )
                }

                fileSystem.delete(target, mustExist = false)
                fileSystem.atomicMove(part, target)
                fileSystem.delete(validatorFile, mustExist = false)
                Result.success(target)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The .part file is deliberately left behind — it is the resume point.
            Result.failure(e)
        } catch (e: URLParserException) {
            Result.failure(IOException("invalid url: $url", e))
        } catch (e: Exception) {
            // Engine-specific network failures (Darwin does not throw java.io types). Same
            // normalisation RepoDownloader.asDownloadFailure applies at its own boundary.
            Result.failure(IOException(e.message ?: e.toString(), e))
        }
    }

    private suspend fun writeBody(
        body: ByteReadChannel,
        part: Path,
        append: Boolean,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        fileSystem.openReadWrite(part).use { handle ->
            if (!append) handle.resize(0L)
            var position = handle.size()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                // readAvailable is a suspending, cancellable read — the by-hand ensureActive()
                // the blocking OkHttp stream needed is now the channel's own job.
                val read = body.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                handle.write(position, buffer, 0, read)
                position += read
                onProgress(position, total)
            }
            handle.flush()
            return position
        }
    }

    /**
     * Total size of the whole resource, not of this response.
     *
     * On a 206 that is the number after the slash in Content-Range; Content-Length would only
     * describe the slice just sent. A server is also allowed to put an asterisk after that slash
     * when it genuinely doesn't know the total, which is why this is nullable rather than guessed.
     */
    private fun totalBytes(response: HttpResponse, startFrom: Long): Long? {
        response.headers[HttpHeaders.ContentRange]?.let { header ->
            return header.substringAfterLast('/').trim().toLongOrNull()
        }
        val length = response.contentLength() ?: return null
        return startFrom + length
    }

    /** ETag is the strong validator; Last-Modified is the fallback for servers that omit it. */
    private fun HttpResponse.validator(): String? =
        headers[HttpHeaders.ETag] ?: headers[HttpHeaders.LastModified]

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
    private fun HttpResponse.continuesFrom(resumeFrom: Long): Boolean {
        if (status.value == HTTP_PARTIAL_CONTENT) return true
        if (resumeFrom <= 0L) return false
        val start = headers[HttpHeaders.ContentRange]
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
