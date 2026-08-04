# Ferry

Downloads AI model repositories to Android devices, and refuses to do it badly.

> **Status: core works.** Fetches a HuggingFace, ModelScope or Ollama repo, refuses to start without
> the disk space to finish it, and verifies every published SHA-256 before committing anything.
> Backgrounding is available as the optional `:ferry-work` module (see below); a failed or
> interrupted download resumes across process death — pause is the one still not done. On Maven
> Central as `dev.thuat:ferry:0.1.0`.

**0.x note:** `RepoProgress` is a sealed interface, and pause — stopping a download on purpose and
recording that, rather than as a failure — is still unimplemented (see Guarantee 4). Adding it later
needs a new `RepoProgress` case, which breaks any consumer's exhaustive `when`. The hub interface
(`ModelHub`) is likewise expected to grow a real error taxonomy beyond a bare `IOException`. Both may
break before `1.0.0` — a deliberate use of what `0.x` means in semver, not instability.

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
        // "needs 4.1 GB, 2.3 GB free" — before a single byte was transferred
    }
}
```

Three hubs, same call:

```kotlin
Ferry.huggingFace().download("google/gemma-2-2b-it", dir)
Ferry.modelScope().download("Qwen/Qwen2.5-0.5B-Instruct", dir)
Ferry.ollama().download("qwen2.5:0.5b", dir)
```

Each takes an `OkHttpClient` if you have one — and if you do, pass it: every request Ferry makes
then travels through your interceptors, your timeouts and your proxy config rather than a second
client it built behind your back.

## Getting it

```kotlin
dependencies {
    implementation("dev.thuat:ferry:0.1.0")
    // Optional, additive — only if you want WorkManager backgrounding:
    implementation("dev.thuat:ferry-work:0.1.0")
}
```

`:ferry-work` depends on `:ferry`, so taking it gets you both (see
[Backgrounding](#backgrounding-ferry-work)).

A composite build still works if you want to develop against a local checkout:

```kotlin
// settings.gradle.kts
includeBuild("../ferry")
```

`:ferry` is a plain `java-library` module — no `android.*` reference anywhere in it, and nothing
Android-specific to inherit. A direct `:ferry` consumer on Android declares its own
`<uses-permission android:name="android.permission.INTERNET" />`; Ferry no longer ships a manifest to
carry it for you. (`:ferry-work` still declares it, since it depends on `:ferry` and does the network
call.)

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

## Guarantees

Not a feature list. Promises the implementation holds and the tests enforce.

| # | guarantee | the failure it prevents |
|---|---|---|
| 1 | **Never a partial model** | files land one by one and a loader picks up a half-written repo |
| 2 | **Never a corrupt model** | trusting a `200`, or verifying against the wrong hash |
| 3 | **Never starts what can't finish** | 4 GB model onto 3 GB free, failing at 91% |
| 4 | **Resumable across a dropped connection, a failed attempt, or the process dying** | a multi-gigabyte download restarting from byte zero after a kill or a crash |

Guarantee 3 is the one neither reference implementation has.

Guarantee 4 covers more ground than "resumable" usually means, and what remains out of scope is the
honest part. A dropped connection is recovered from mid-attempt via `Range`. A file already staged and
verifying — from this attempt or an earlier one — is not fetched again. Staging itself is durable: a
failed attempt, a cancellation, or the process dying all leave it exactly as far as it got, and nothing
deletes it until either a later `download` call consumes it by committing the repo, or the caller
explicitly calls `abandonStaging` because no retry is coming. A second `download` call for the same repo id —
even from a fresh process — resumes from whatever is already on disk instead of restarting from zero,
and `stagedBytes(repoId, into)` lets a caller detect there is something to resume before calling
`download` at all, the number behind a "Resume, N already downloaded" row.

What is still out of scope is deliberate **pause**: stopping a download mid-flight and recording that
it was intentional rather than a failure needs cooperative cancellation threaded through the transfer
loop, which nothing here does yet — coroutine cancellation already stops a transfer, but nothing
records why it stopped. And resume is only ever as good as the hub's own validator: a `.part` with no
`ETag`/`Last-Modified` from the server restarts that one file from byte zero rather than risk resuming
onto content that changed underneath it.

## Three things about HuggingFace worth knowing

All verified against the live API, and each costs you a day if you meet it by surprise.

### The listing is neither recursive nor complete by default

`/tree/main` returns the top level only, and one page of at most 1000 entries.

```
/tree/main                    stabilityai/stable-diffusion-xl-base-1.0 → 10 files
/tree/main?recursive=true     the same repo                            → 57 files
```

Miss `recursive=true` and a repo with `unet/`, `vae/` or `onnx/` subtrees downloads as a fraction of
itself that still looks complete. Then, past 1000 entries, the response carries a `Link` header:

```
link: <…/tree/main?expand=false&recursive=true&limit=1000&cursor=ZXlKbWFX…>; rel="next"
```

`google/gemma-scope-9b-pt-res` is 1000 entries then 724. Follow the URL exactly as given rather than
rebuilding it — the cursor is opaque. But **check its host before following it**: it is the one
request target that comes from the response rather than from your own code, and a hub that can name
an arbitrary address gets to point your HTTP client at one.

### The ETag you get is not the ETag you want

A file URL 302-redirects to a CDN, and the two hops carry different hashes:

```
302   x-linked-etag: "fdf756fa…"   SHA-256, matches lfs.oid        ← use this
206   etag:          "bb5ff7e7…"   xetHash, matches nothing useful ← not this
```

Verify against the second and every check fails, for reasons indistinguishable from corruption.
Read the SHA from the tree API, or from `x-linked-etag` on the redirect.

### The resolved CDN URL expires

The 302 target is signed. Persisting it — the obvious optimisation — breaks resume about an hour
later. Always re-resolve from the canonical URL.

## Two things about HTTP range requests worth knowing

**A `200` answer to a range request must not be appended.** The server either ignores `Range` or
`If-Range` failed. Appending splices two copies together and produces a plausibly-sized,
permanently corrupt file. The response code decides whether to append, not the request.

**Set `Accept-Encoding: identity` on every request.** Ranges are offsets into the *encoded*
representation, and OkHttp adds `Accept-Encoding: gzip` whenever neither it nor `Range` is present,
then transparently decompresses. A first request without `Range` therefore fills the partial file
with decompressed bytes, and its length is not a valid resume offset.

## Adding a hub

Exactly one thing varies between hubs. Everything else — resume, verification, space, staging,
atomic commit — is shared.

```
resolve manifest      hub-specific   ← implement this, and only this
check free space      shared
per file:
  download w/ resume  shared
  verify sha256       shared
commit atomically     shared
```

So a hub is one `ModelHub` implementation, roughly forty lines:

```kotlin
interface ModelHub {
    suspend fun manifest(repoId: String): Result<RepoManifest>
}

data class RemoteFile(
    val path: String,      // where it lands on disk
    val url: String,       // where to fetch it, resolved by the adapter
    val sizeBytes: Long,
    val sha256: String?,
)
```

The URL is resolved while building the manifest rather than derived later, because not every hub
names its files. Ollama serves content-addressed OCI blobs identified only by a digest, so a
stateless `fileUrl(repoId, path)` could not map a synthesized path back to one.

**The rule that keeps this honest: an adapter describes *what* to fetch, never *how*.** If a hub's
behaviour forces its adapter to influence the transport, the abstraction has leaked and the fix
belongs in the transport instead.

That is not theoretical. ModelScope honours range requests but answers `200` with a valid
`Content-Range` rather than `206`. The temptation is a per-hub flag. The correct fix was to make the
transport read `Content-Range`'s start offset instead of trusting the status code — which is now
right for every hub, including ones nobody has written an adapter for yet.

### What three hubs look like

All three are implemented.

| | HuggingFace | ModelScope | Ollama |
|---|---|---|---|
| Listing | `/api/models/{id}/tree/main?recursive=true` | `/api/v1/models/{id}/repo/files?Revision=master&Recursive=True` | `/v2/library/{id}/manifests/{tag}` |
| Auth to list | none | none | none |
| File identity | `path` | `Path` | **none — digest only** |
| File type field | `type == "file"` | `Type == "blob"` | every layer is a file |
| Path synthesis | n/a — the hub names it | n/a — the hub names it | `{mediaType suffix}-{digest}` |
| SHA-256 | `lfs.oid`, LFS files only | `Sha256`, **every file** | the digest itself |
| Download | `/{id}/resolve/main/{path}` → 302 | `/api/v1/models/{id}/repo?…&FilePath=` | `/v2/library/{id}/blobs/{digest}` → 307 |
| Range response | `206` | **`200`** with `Content-Range` | `206` |
| Default revision | `main` | `master` | a tag, e.g. `0.5b`, embedded in the id |
| Pagination | `Link` header, 1000/page | none observed | **none possible — one manifest, no cursor** |

**`Recursive` is case-sensitive, and the wrong case is silently ignored rather than rejected —
verified live: `Recursive=True` returned 39 entries (21 nested); `recursive=true` returned 18 with
none nested, HTTP 200 either way, no error.** Copy the capital R and capital T exactly — a lowercase
typo here is the same silent truncation `recursive=true` on HuggingFace's `/tree/main` used to
produce, just spelled differently.

**Two Ollama layers can share a mediaType, and naming by suffix alone collides.**
`llama3.2-vision:11b` ships two `application/vnd.ollama.image.license` layers — verified live. The
obvious path — the mediaType's last segment, `license` — collides for both, and whichever naive
implementation drops the second is the exact silent-truncation class HuggingFace's non-recursive
listing already cost this project once: `RepoManifest.totalBytes` comes out short by one layer, with
nothing anywhere reporting it. Appending the layer's own digest to the suffix (`license-832dd9e0…`)
closes this by construction rather than by detecting the collision after the fact: two layers can
only share a synthesized path if they share both a suffix and a digest, and a shared digest means
identical content by definition of content-addressing — the same file referenced twice, not a
collision. `OllamaTest` pins this exact manifest shape so it cannot regress unnoticed.

### Hub status, and why a third

| Hub | Status |
|---|---|
| HuggingFace | implemented |
| ModelScope | implemented |
| Ollama | implemented |
| Modelers.cn | **documented, not implemented** — unreachable from where this was written, and an adapter whose only evidence is reading someone else's source is exactly what this project keeps proving to be insufficient |
| Kaggle Models | no — `403` unauthenticated, needs API-key handling first |

HuggingFace and ModelScope are a hub and its mirror: they publish identical SHA-256 for identical
content, so one can stand in for the other and be verified against the same expected hash. Two of
those proves `ModelHub` compiles against a second hub, not that it survives one — both are REST
listings of named files with per-file content hashes; structurally, neither was ever going to break
the interface.

Ollama was added to find out whether a hub *could*. Its manifest is an OCI image manifest, not a
directory listing: a layer is `mediaType` + `size` + `digest`, no filename anywhere, so
`RemoteFile.path` has to be synthesized rather than read off the response — the one part of
`ModelHub` a HuggingFace-shaped hub can never exercise. It fit without contortion: `ModelHub` and
`RemoteFile` needed no change, which `RemoteFile.url`'s own KDoc already anticipated (it is
adapter-resolved rather than derived from `path` precisely because "not every hub names its files").

Ollama's GGUFs are also a genuinely different artifact from HuggingFace's originals — converted and
re-quantized, sharing no SHA-256 with either existing hub even for "the same" nominal model — so this
does not extend the mirroring property above to a third hub; that trade only ever existed between
HuggingFace and ModelScope.

Revision belongs to the adapter, not the interface — HuggingFace and ModelScope already disagree on
its default, so a shared parameter would only push the difference up a layer. Ollama has no separate
revision concept at all: the tag lives inside `repoId` itself, resolved by the adapter, not defaulted
by a constructor parameter.

### Four things that get harder than they look

**Auth crosses a trust boundary.** OkHttp strips `Authorization` on a cross-host redirect, which is
correct: your token must not reach a CDN. It is also why HuggingFace's redirect target is signed and
expires — the signature replaces the token that was dropped. An adapter that "fixes" the missing
token by forcing the header through the redirect is leaking a credential to a third party.

**Rate limits are a shared concern.** Listing endpoints are rate-limited. Backoff belongs in the
transport, once, not in each adapter.

**Content-addressing enables mirroring.** Both hubs report the same SHA-256 for the same file —
`model.safetensors` is `fdf756fa…` on each. So a failed hub can be retried on another and verified
against the same expected hash. That is a property of the content, not of either hub, and it is the
strongest argument for keeping verification in the shared layer.

**Private repos change the manifest, not just the transport.** A gated model may list differently, or
not at all, without a token. Handle it as a listing failure with a distinguishable cause, not as a
download failure.

## Sample app

`:sample` is a small Compose app that demonstrates what Ferry refuses to do, not its downloads — a
progress bar can't show a guarantee, only the absence of one is visible. It lists three real
HuggingFace repos (a 4.7 MB, an 18 MB, and the 5.6 GB `gpt2`) and a sabotage panel with two controls
built on seams the library already exposes: one pretends the disk is nearly full, via a
`FreeSpaceProbe` passed into a directly-constructed `RepoDownloader`, so every model's `WontFit` row
shows real `SpaceReport` numbers; the other flips a byte in a downloaded file on disk, so tapping
Re-check visibly redownloads it instead of trusting what's there. Neither adds anything to `:ferry`.

```bash
./gradlew :sample:assembleDebug
```

## Backgrounding: `:ferry-work`

`:ferry-work` is an optional module that wraps `RepoDownloader.download` in a `CoroutineWorker`,
with a host-controlled foreground notification, WorkManager's own retry and backoff, and a
uniqueness guarantee `:ferry` itself cannot provide. It depends on `:ferry`; nothing in `:ferry`
depends on it — a host with no interest in WorkManager takes only `:ferry` and never notices
`:ferry-work` exists.

```kotlin
// settings.gradle.kts
include(":ferry-work")

// app/build.gradle.kts
implementation(project(":ferry-work"))
```

Your notification, your channel, your wording — registered once, via WorkManager's own factory hook:

```kotlin
class App : Application(), Configuration.Provider {
    override val workManagerConfiguration = Configuration.Builder()
        .setWorkerFactory(
            RepoDownloadWorkerFactory { repoId, progress ->
                // progress is null before the first update arrives
                buildNotification(repoId, progress)
            },
        )
        .build()
}
```

Then enqueue:

```kotlin
WorkManager.getInstance(context)
    .enqueueRepoDownload(repoId = "google/gemma-2-2b-it", into = filesDir, notificationId = 42)
```

Ferry moves bytes; it never decides what your users read while it does.

Enqueueing the same repo id twice is a no-op rather than a race — the second call keeps the running
work instead of replacing it. Replacing it would cancel the in-flight `download` call, and coroutine
cancellation is cooperative: it stops at the next checkpoint, not instantly, so a replacement's own
`download` call could start writing into the same staging directory while the cancelled one's last
write is still landing — the same concurrent-download hazard `download`'s own KDoc already warns
against for two ordinary calls, self-inflicted here by cancelling one enqueue's work out from under
the request that is about to replace it. `KEEP` avoids that by never starting the second call at all
while the first is still running.

### Why a separate module, not a feature of `:ferry`

`:ferry`'s own `checkEmbeddable` Gradle task fails the build the moment `androidx.work` appears on
its classpath, and it is wired into `check` — enforced, not merely intended. The reason is this
README's own "Why this exists" table: Alibaba's MNN backgrounds downloads with a plain foreground
`Service`; Google's AI Edge Gallery backgrounds them with a `CoroutineWorker`. A `:ferry` that
required WorkManager would rule MNN's approach out by construction — the exact failure this
project exists to avoid for a *hub*, one layer up, for a *backgrounding strategy*. Splitting
backgrounding into its own module, rather than gating it behind a feature flag inside `:ferry`,
is what makes the exclusion real: a flag can still be compiled in and inspected on the classpath;
a dependency that is never declared cannot be.

### Design decisions

Full reasoning lives in `RepoDownloadWorker`'s own KDoc; this is the shape of each choice.

- **Input and output.** `repoId`, an absolute `into` path and a notification id go in as
  `androidx.work.Data` — primitives only. On success, the committed directory's own absolute path
  comes out, not recomputed from the inputs by this module. On failure, only
  `InsufficientSpaceException` survives in detail (a reason code, its message, and its
  `SpaceReport`'s numbers); every other failure loses its exception type and carries no `Data`
  while it is still retrying — see Retry for when it stops.
- **Retry.** `InsufficientSpaceException` fails outright: a full device is exactly as full on the
  next attempt. Everything else retries, including a `VerificationException`, because
  `RepoDownloader` exposes no richer taxonomy than these two named exceptions plus a bare
  `IOException` for everything else — a dropped connection and a permanently-corrupt file are not
  distinguishable by type from outside `:ferry`. That retry is bounded, not indefinite: exponential
  backoff makes attempts less frequent, never fewer, so a case that will in fact never pass needs
  an actual ceiling. `RepoDownloadWorker.MAX_RETRY_ATTEMPTS` (5) is that ceiling, read off
  WorkManager's own `runAttemptCount`; past it, the worker fails with `REASON_RETRIES_EXHAUSTED` in
  the same `KEY_FAILURE_REASON` field `InsufficientSpaceException` uses, rather than a parallel one.
- **Progress.** `RepoProgress.Downloading` fires once per read buffer. `RepoDownloadThrottle` — a
  direct port of `:sample`'s own `DownloadingThrottle`, not a shared dependency, since the only
  module both could share is `:ferry` — gates both `setProgress` and the notification update to at
  most once a second, always letting a file's last byte through.
- **Uniqueness.** `enqueueRepoDownload` wraps `WorkManager.enqueueUniqueWork` with
  `ExistingWorkPolicy.KEEP`, keyed by repo id: a second enqueue for a repo already running is
  dropped rather than cancelling and restarting it, which would forfeit whatever had already
  downloaded. This closes `docs/known-limitations.md`'s concurrent-download entry *within
  WorkManager*; a host calling `RepoDownloader` directly, outside this enqueue path, is still
  responsible for serialising itself the way that entry has always said.
- **Foreground.** The notification's text, icon and actions are supplied by the host through
  `RepoDownloadNotifications`, never hardcoded by Ferry — the same reasoning that keeps `:ferry`
  itself free of a UI framework. `:ferry-work`'s own manifest declares
  `android.permission.FOREGROUND_SERVICE_DATA_SYNC` and overrides WorkManager's
  `SystemForegroundService` with `android:foregroundServiceType="dataSync"`, verified directly
  against work-runtime 2.11.2's own merged manifest (which declares neither), so a host does not
  need to know that internal class name exists at all.

## Building

```bash
./gradlew :ferry:test
```

`:ferry` is a plain JVM module and needs only a JDK. Building the rest of the repo — `:ferry-work`
and the `:sample` app — additionally requires an Android SDK with API 35, and a JDK between 17 and 21.

The upper bound is Gradle 8.9's, not this project's: a newer JDK fails during script compilation
with a bare `IllegalArgumentException` naming the JDK's own version and nothing else, which is not
an obvious diagnosis. If you hit it, point the build at a supported JDK rather than debugging the
message:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :ferry:check
```

### Releasing

Four credentials drive publication, all read from a Gradle property or an environment variable of the
same name in SCREAMING_SNAKE_CASE (`gradle/publishing.gradle.kts`), none with a default:
`signingInMemoryKey` and `signingInMemoryKeyPassword` hold an ASCII-armored GPG private key whose
public half is on a keyserver, and `mavenCentralUsername` / `mavenCentralPassword` hold a Central
Portal user token. Absent, nothing breaks and no `Sign` task is even registered — building, testing
and `./gradlew publishToMavenLocal` all work with none of them, so a contributor cloning this never
needs a signing key.

With them set, a release is: tag the commit, deploy, promote, publish.

```bash
git tag -a v0.1.1 -m "0.1.1" && git push origin v0.1.1
JAVA_HOME=/path/to/jdk-21 ./gradlew publishAllPublicationsToMavenCentralRepository
curl -X POST -H "Authorization: Bearer $(printf '%s:%s' "$PORTAL_USER" "$PORTAL_TOKEN" | base64)" \
  "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/dev.thuat"
```

Then Publish the deployment at central.sonatype.com/publishing/deployments. Everything before that
button is reversible; a released version never is.

Both modules must go up in one Gradle invocation, so they share a staging repository — Central
validates `ferry-work`'s dependency on `ferry` within the deployment. Note also that the promote
endpoint is scoped to the whole `dev.thuat` namespace, not to this project: it sweeps up anything
else sitting unpromoted in that namespace's default staging repository.

## License

Apache 2.0
