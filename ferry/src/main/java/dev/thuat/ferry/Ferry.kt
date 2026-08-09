package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

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

    /**
     * Temporary bridge so [ResumableDownloader]'s Ktor [HttpClient] runs over the same OkHttp
     * client and interceptors a host handed to these factories — the property EmbeddabilityTest
     * exists to guarantee. Disappears once the hub adapters themselves move to Ktor (Task 5) and
     * these factories take an `HttpClient` directly (Task 6).
     */
    private fun bridgeClient(client: OkHttpClient): HttpClient =
        HttpClient(OkHttp) { engine { preconfigured = client } }

    fun huggingFace(
        client: OkHttpClient = OkHttpClient(),
        baseUrl: String = "https://huggingface.co",
    ): RepoDownloader = RepoDownloader(
        repo = HuggingFace(client = client, baseUrl = baseUrl),
        downloader = ResumableDownloader(bridgeClient(client)),
    )

    fun modelScope(
        client: OkHttpClient = OkHttpClient(),
        baseUrl: String = "https://modelscope.cn",
        revision: String = "master",
    ): RepoDownloader = RepoDownloader(
        repo = ModelScope(client = client, baseUrl = baseUrl, revision = revision),
        downloader = ResumableDownloader(bridgeClient(client)),
    )

    fun ollama(
        client: OkHttpClient = OkHttpClient(),
        baseUrl: String = "https://registry.ollama.ai",
    ): RepoDownloader = RepoDownloader(
        repo = Ollama(client = client, baseUrl = baseUrl),
        downloader = ResumableDownloader(bridgeClient(client)),
    )
}
