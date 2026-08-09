package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okio.FileSystem

/**
 * Entry point.
 *
 * Deliberately a factory rather than a builder: a fluent API written before there is a second hub
 * to choose between is guessing at its own shape.
 *
 * Nothing here decides how work is backgrounded, where files live, or what the user is shown. The
 * two applications this library is aimed at background downloads differently — one with a
 * foreground Service, one with a CoroutineWorker — so imposing either would rule out one of them.
 */
object Ferry {

    fun huggingFace(
        client: HttpClient = defaultHttpClient(),
        fileSystem: FileSystem = FileSystem.SYSTEM,
        baseUrl: String = "https://huggingface.co",
    ): RepoDownloader = RepoDownloader(
        repo = HuggingFace(client = client, baseUrl = baseUrl),
        downloader = ResumableDownloader(client, fileSystem),
        fileSystem = fileSystem,
    )

    fun modelScope(
        client: HttpClient = defaultHttpClient(),
        fileSystem: FileSystem = FileSystem.SYSTEM,
        baseUrl: String = "https://modelscope.cn",
        revision: String = "master",
    ): RepoDownloader = RepoDownloader(
        repo = ModelScope(client = client, baseUrl = baseUrl, revision = revision),
        downloader = ResumableDownloader(client, fileSystem),
        fileSystem = fileSystem,
    )

    fun ollama(
        client: HttpClient = defaultHttpClient(),
        fileSystem: FileSystem = FileSystem.SYSTEM,
        baseUrl: String = "https://registry.ollama.ai",
    ): RepoDownloader = RepoDownloader(
        repo = Ollama(client = client, baseUrl = baseUrl),
        downloader = ResumableDownloader(client, fileSystem),
        fileSystem = fileSystem,
    )
}

// becomes expect/actual in Task 7
internal fun defaultHttpClient(): HttpClient = HttpClient(OkHttp)
