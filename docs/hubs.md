# Hubs

Field notes and the adapter interface. Everything here is verified against a live API, and each item
costs a day if you meet it by surprise.

## Three things about HuggingFace worth knowing

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
rebuilding it, because the cursor is opaque.

But **check its host before following it**. It is the one request target that comes from the response
rather than from your own code, and a hub that can name an arbitrary address gets to point your HTTP
client at one.

### The ETag you get is not the ETag you want

A file URL 302-redirects to a CDN, and the two hops carry different hashes:

```
302   x-linked-etag: "fdf756fa…"   SHA-256, matches lfs.oid        ← use this
206   etag:          "bb5ff7e7…"   xetHash, matches nothing useful ← not this
```

Verify against the second and every check fails, for reasons indistinguishable from corruption.
Read the SHA from the tree API, or from `x-linked-etag` on the redirect.

### The resolved CDN URL expires

The 302 target is signed. Persisting it, which is the obvious optimisation, breaks resume about an
hour later. Always re-resolve from the canonical URL.

## Two things about HTTP range requests worth knowing

**A `200` answer to a range request must not be appended.** The server either ignores `Range` or
`If-Range` failed. Appending splices two copies together and produces a plausibly-sized,
permanently corrupt file. The response code decides whether to append, not the request.

**Set `Accept-Encoding: identity` on every request.** Ranges are offsets into the *encoded*
representation, and OkHttp adds `Accept-Encoding: gzip` whenever neither it nor `Range` is present,
then transparently decompresses. A first request without `Range` therefore fills the partial file
with decompressed bytes, and its length is not a valid resume offset.

## Adding a hub

Exactly one thing varies between hubs. Everything else (resume, verification, space, staging, atomic
commit) is shared.

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
transport read `Content-Range`'s start offset instead of trusting the status code, which is now right
for every hub, including ones nobody has written an adapter for yet.

## What three hubs look like

All three are implemented.

| | HuggingFace | ModelScope | Ollama |
|---|---|---|---|
| Listing | `/api/models/{id}/tree/main?recursive=true` | `/api/v1/models/{id}/repo/files?Revision=master&Recursive=True` | `/v2/library/{id}/manifests/{tag}` |
| Auth to list | none | none | none |
| File identity | `path` | `Path` | **none, digest only** |
| File type field | `type == "file"` | `Type == "blob"` | every layer is a file |
| Path synthesis | n/a, the hub names it | n/a, the hub names it | `{mediaType suffix}-{digest}` |
| SHA-256 | `lfs.oid`, LFS files only | `Sha256`, **every file** | the digest itself |
| Download | `/{id}/resolve/main/{path}` → 302 | `/api/v1/models/{id}/repo?…&FilePath=` | `/v2/library/{id}/blobs/{digest}` → 307 |
| Range response | `206` | **`200`** with `Content-Range` | `206` |
| Default revision | `main` | `master` | a tag, e.g. `0.5b`, embedded in the id |
| Pagination | `Link` header, 1000/page | none observed | **none possible: one manifest, no cursor** |

**`Recursive` is case-sensitive, and the wrong case is silently ignored rather than rejected.**
Verified live: `Recursive=True` returned 39 entries (21 nested); `recursive=true` returned 18 with
none nested, HTTP 200 either way, no error. Copy the capital R and capital T exactly. A lowercase
typo here is the same silent truncation `recursive=true` on HuggingFace's `/tree/main` used to
produce, just spelled differently.

**Two Ollama layers can share a mediaType, and naming by suffix alone collides.**
`llama3.2-vision:11b` ships two `application/vnd.ollama.image.license` layers, verified live. The
obvious path is the mediaType's last segment, `license`, and it collides for both. Whichever naive
implementation drops the second hits the exact silent-truncation class HuggingFace's non-recursive
listing already cost this project once: `RepoManifest.totalBytes` comes out short by one layer, with
nothing anywhere reporting it.

Appending the layer's own digest to the suffix (`license-832dd9e0…`) closes this by construction
rather than by detecting the collision after the fact. Two layers can only share a synthesized path
if they share both a suffix and a digest, and a shared digest means identical content by definition
of content-addressing: the same file referenced twice, not a collision. `OllamaTest` pins this exact
manifest shape so it cannot regress unnoticed.

## Hub status, and why a third

| Hub | Status |
|---|---|
| HuggingFace | implemented |
| ModelScope | implemented |
| Ollama | implemented |
| Modelers.cn | **documented, not implemented.** Unreachable from where this was written, and an adapter whose only evidence is reading someone else's source is exactly what this project keeps proving to be insufficient |
| Kaggle Models | no. `403` unauthenticated, needs API-key handling first |

HuggingFace and ModelScope are a hub and its mirror: they publish identical SHA-256 for identical
content, so one can stand in for the other and be verified against the same expected hash. Two of
those proves `ModelHub` compiles against a second hub, not that it survives one. Both are REST
listings of named files with per-file content hashes, so structurally neither was ever going to break
the interface.

Ollama was added to find out whether a hub *could*. Its manifest is an OCI image manifest, not a
directory listing: a layer is `mediaType` + `size` + `digest`, with no filename anywhere, so
`RemoteFile.path` has to be synthesized rather than read off the response. That is the one part of
`ModelHub` a HuggingFace-shaped hub can never exercise.

It fit without contortion. `ModelHub` and `RemoteFile` needed no change, which `RemoteFile.url`'s own
KDoc already anticipated: it is adapter-resolved rather than derived from `path` precisely because
"not every hub names its files".

Ollama's GGUFs are also a genuinely different artifact from HuggingFace's originals, converted and
re-quantized, sharing no SHA-256 with either existing hub even for "the same" nominal model. So this
does not extend the mirroring property above to a third hub. That trade only ever existed between
HuggingFace and ModelScope.

Revision belongs to the adapter, not the interface. HuggingFace and ModelScope already disagree on
its default, so a shared parameter would only push the difference up a layer. Ollama has no separate
revision concept at all: the tag lives inside `repoId` itself, resolved by the adapter, not defaulted
by a constructor parameter.

## Four things that get harder than they look

**Auth crosses a trust boundary.** OkHttp strips `Authorization` on a cross-host redirect, which is
correct: your token must not reach a CDN. It is also why HuggingFace's redirect target is signed and
expires, since the signature replaces the token that was dropped. An adapter that "fixes" the missing
token by forcing the header through the redirect is leaking a credential to a third party.

**Rate limits are a shared concern.** Listing endpoints are rate-limited. Backoff belongs in the
transport, once, not in each adapter.

**Content-addressing enables mirroring.** Both hubs report the same SHA-256 for the same file:
`model.safetensors` is `fdf756fa…` on each. So a failed hub can be retried on another and verified
against the same expected hash. That is a property of the content, not of either hub, and it is the
strongest argument for keeping verification in the shared layer.

**Private repos change the manifest, not just the transport.** A gated model may list differently, or
not at all, without a token. Handle it as a listing failure with a distinguishable cause, not as a
download failure.
