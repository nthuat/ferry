# Ferry

Downloads AI model repositories to Android devices, and refuses to do it badly.

> **Status: early.** The transport layer is written and tested. Repository semantics, verification
> and the Android integration are not done yet. Not published to Maven, not ready to use.

```kotlin
Ferry.to(context.filesDir)
    .from(HuggingFace)
    .fetch("google/gemma-2-2b-it")
    .collect { progress -> … }
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
| 4 | **Always resumable** | progress kept in memory, or keyed to a version code |

Guarantee 3 is the one neither reference implementation has.

## Two things about HuggingFace worth knowing

Both verified against the live API, and both cost you a day if you meet them by surprise.

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

## Building

```bash
./gradlew :ferry:testDebugUnitTest
```

Requires JDK 17 and an Android SDK with API 35.

## License

Apache 2.0
