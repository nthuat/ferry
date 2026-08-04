# Ferry

[![Maven Central](https://img.shields.io/maven-central/v/dev.thuat/ferry)](https://central.sonatype.com/artifact/dev.thuat/ferry)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Downloads AI model repositories to Android devices, and refuses to do it badly.

Fetches a HuggingFace, ModelScope or Ollama repo, refuses to start without the disk space to finish
it, and verifies every published SHA-256 before committing anything. A failed or interrupted download
resumes across process death.

```kotlin
implementation("dev.thuat:ferry:0.1.0")
// Optional and additive. Only if you want WorkManager backgrounding:
implementation("dev.thuat:ferry-work:0.1.0")
```

```kotlin
val ferry = Ferry.huggingFace()

ferry.download("google/gemma-2-2b-it", context.filesDir) { progress ->
    when (progress) {
        is RepoProgress.CheckingSpace -> …
        is RepoProgress.Downloading -> …
        is RepoProgress.Skipped -> …   // already staged and verified; no bytes moved
        is RepoProgress.Verifying -> …
        is RepoProgress.Complete -> …
    }
}.onFailure { error ->
    if (error is InsufficientSpaceException) {
        // "needs 4.1 GB, 2.3 GB free", before a single byte was transferred
    }
}
```

Three hubs, same call:

```kotlin
Ferry.huggingFace().download("google/gemma-2-2b-it", dir)
Ferry.modelScope().download("Qwen/Qwen2.5-0.5B-Instruct", dir)
Ferry.ollama().download("qwen2.5:0.5b", dir)
```

Each takes an `OkHttpClient` if you have one. Pass it if you do: every request Ferry makes then
travels through your interceptors, your timeouts and your proxy config.

`:ferry` is a plain `java-library` module with no `android.*` reference in it, so a consumer on
Android declares its own `<uses-permission android:name="android.permission.INTERNET" />`.

## How a download works

![Ferry resolves a manifest from the hub, refuses up front if the disk cannot hold it, downloads each file into a staging directory where a .part resumes via Range and is renamed only after its sha256 verifies, then commits the whole repo with one atomic rename](docs/img/download-flow.svg)

## Guarantees

Not a feature list. Promises the implementation holds and the tests enforce.

| # | guarantee | the failure it prevents |
|---|---|---|
| 1 | **Never a partial model** | files land one by one and a loader picks up a half-written repo |
| 2 | **Never a corrupt model** | trusting a `200`, or verifying against the wrong hash |
| 3 | **Never starts what can't finish** | 4 GB model onto 3 GB free, failing at 91% |
| 4 | **Resumable across a dropped connection, a failed attempt, or the process dying** | a multi-gigabyte download restarting from byte zero after a kill or a crash |

Guarantee 3 is the one neither reference implementation has (see [Why this exists](#why-this-exists)).

Guarantee 4 covers more than "resumable" usually means. Staging is durable, so a failed attempt, a
cancellation, or the process dying all leave it exactly as far as it got, and a later `download` call
for the same repo id resumes from there rather than from zero. Nothing deletes staged bytes until a
`download` commits the repo or the caller calls `abandonStaging`. `stagedBytes(repoId, into)` reports
how much is already there, the number behind a "Resume, N already downloaded" row.

Two things stay out of scope. **Deliberate pause**, meaning recording that a stop was intentional
rather than a failure, needs cooperative cancellation threaded through the transfer loop; coroutine
cancellation already stops a transfer, but nothing records why. And **resume is only as good as the
hub's validator**: a `.part` with no `ETag` or `Last-Modified` restarts that file from byte zero
rather than risk resuming onto content that changed underneath it.

## Why this exists

Two of the most prominent on-device-LLM Android apps wrote the same downloader independently:

| | Alibaba MNN (`MnnLlmChat`) | Google AI Edge Gallery |
|---|---|---|
| Transport | OkHttp + `Range` | `HttpURLConnection` + `Range` |
| Backgrounding | foreground `Service` | `CoroutineWorker` + `setForeground` |
| Repo of many files | yes | single file only |
| Verification | SHA-256 from ETag | not visible |
| Free-space check | **no** | **no** |

Nothing on Maven covers it. `firebase-ml-modeldownloader` handles Firebase-hosted models only;
`tasks-genai` and `litert` run models but never fetch them; Play for On-device AI delivers models
you own and bundle, not ones fetched from a hub at runtime.

## Backgrounding

`:ferry-work` is an optional module wrapping the download in a `CoroutineWorker`, with a
host-controlled foreground notification, WorkManager's retry and backoff, and a uniqueness guarantee
`:ferry` cannot provide. Nothing in `:ferry` depends on it.

```kotlin
implementation("dev.thuat:ferry-work:0.1.0")

WorkManager.getInstance(context)
    .enqueueRepoDownload(repoId = "google/gemma-2-2b-it", into = filesDir, notificationId = 42)
```

Setup, retry policy and design reasoning: [docs/ferry-work.md](docs/ferry-work.md).

## Sample app

`:sample` is a small Compose app demonstrating what Ferry refuses to do, not its downloads. A
progress bar cannot show a guarantee; only the absence of one is visible. It lists three real
HuggingFace repos plus a sabotage panel that fakes a nearly-full disk and corrupts a downloaded file
on demand, both built on seams the library already exposes.

```bash
./gradlew :sample:assembleDebug
```

## Building

```bash
./gradlew :ferry:test
```

`:ferry` needs only a JDK. The rest of the repo (`:ferry-work` and `:sample`) also needs an Android
SDK with API 35, and a JDK between 17 and 21. The upper bound is Gradle 8.9's: a newer JDK fails
during script compilation with a bare `IllegalArgumentException` naming the JDK's own version and
nothing else.

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :ferry:check
```

## Docs

- [Hubs](docs/hubs.md). HuggingFace and range-request field notes, the `ModelHub` interface, and what
  three hubs look like side by side. Read this before writing an adapter.
- [Backgrounding](docs/ferry-work.md). `:ferry-work` setup and every design decision behind it.
- [Known limitations](docs/known-limitations.md). What is open, what is closed, and why.
- [Releasing](docs/releasing.md). Maintainer-only.

## 0.x

`RepoProgress` is a sealed interface and pause is unimplemented, so adding it later needs a new case,
which breaks any consumer's exhaustive `when`. `ModelHub` is likewise expected to grow a real error
taxonomy beyond a bare `IOException`. Both may break before `1.0.0`. That is a deliberate use of what
`0.x` means in semver, not instability.

## License

Apache 2.0
