# Ferry

Downloads AI model repositories to Android devices, and refuses to do it badly.

> **Status: core works.** Fetches a HuggingFace repo, refuses to start without the disk space to
> finish it, and verifies every published SHA-256 before committing anything. Backgrounding, pause,
> resume-across-launch and a second hub are not done. Not published to Maven.

```kotlin
val ferry = Ferry.huggingFace()

ferry.download("google/gemma-2-2b-it", context.filesDir) { progress ->
    when (progress) {
        is RepoProgress.CheckingSpace -> …
        is RepoProgress.Downloading -> …
        is RepoProgress.Verifying -> …
        is RepoProgress.Complete -> …
    }
}.onFailure { error ->
    if (error is InsufficientSpaceException) {
        // "needs 4.1 GB, 2.3 GB free" — before a single byte was transferred
    }
}
```

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
| 4 | **Resumable within one download attempt** | progress kept in memory, or keyed to a version code |

Guarantee 3 is the one neither reference implementation has.

Guarantee 4 is scoped, and the scope is the honest part. A dropped connection is recovered from
mid-attempt via `Range`, and a file already on disk and verifying is not fetched again. Resume across
process death is **not** implemented: the staging directory is deleted in a `finally` block so a
failure can never leave a half-repo behind, which forfeits the partial files with it. A failed
attempt therefore restarts from byte zero. Making both true at once needs persisted state and is a
later phase.

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

So a hub is one `ModelRepo` implementation, roughly forty lines:

```kotlin
interface ModelRepo {
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

HuggingFace is implemented. The other two are checked against their live APIs, not adapted yet.

| | HuggingFace | ModelScope | Ollama |
|---|---|---|---|
| Listing | `/api/models/{id}/tree/main?recursive=true` | `/api/v1/models/{id}/repo/files?Revision=master` | `/v2/library/{id}/manifests/{tag}` |
| Auth to list | none | none | none |
| File identity | `path` | `Path` | **none — digest only** |
| File type field | `type == "file"` | `Type == "blob"` | every layer is a file |
| SHA-256 | `lfs.oid`, LFS files only | `Sha256`, **every file** | the digest itself |
| Download | `/{id}/resolve/main/{path}` → 302 | `/api/v1/models/{id}/repo?…&FilePath=` | `/v2/library/{id}/blobs/{digest}` → 307 |
| Range response | `206` | **`200`** with `Content-Range` | `206` |
| Default revision | `main` | `master` | a tag, e.g. `0.5b` |

### Which hubs this will actually ship

Two, deliberately.

| Hub | Status |
|---|---|
| HuggingFace | implemented |
| ModelScope | planned — API verified live |
| Ollama | deferred — adapter is straightforward, but its converted GGUFs share no hashes with the others, so it adds surface without compounding |
| Modelers.cn | **documented, not implemented** — unreachable from where this was written, and an adapter whose only evidence is reading someone else's source is exactly what this project keeps proving to be insufficient |
| Kaggle Models | no — `403` unauthenticated, needs API-key handling first |

Two is the minimum that proves `ModelRepo` is load-bearing rather than decorative, and these two in
particular are a hub and its mirror: they publish identical SHA-256 for identical content, so one can
stand in for the other and be verified against the same expected hash. A third adapter would be a
third adapter. That is not the same kind of gain.

Revision belongs to the adapter, not the interface — the two hubs already disagree on its default,
so a shared parameter would only push the difference up a layer.

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

## Building

```bash
./gradlew :ferry:testDebugUnitTest
```

Requires JDK 17 and an Android SDK with API 35.

## License

Apache 2.0
