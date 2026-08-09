package dev.thuat.ferry

import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.encodeURLPathPart

/**
 * Appends [raw]'s slash-separated pieces as path segments, resolving `.` and `..` the way OkHttp's
 * `HttpUrl.Builder.addPathSegments` always did — popping the previous segment on `..`, dropping `.`
 * — rather than Ktor's own [appendPathSegments], which appends every piece literally, `..` included,
 * and never pops anything (confirmed empirically: `appendPathSegments("../../etc/passwd")` off
 * `api/models` builds `.../api/models/../../etc/passwd`, segments `[api, models, .., .., etc,
 * passwd]`, not `[etc, passwd]`).
 *
 * Every hub adapter's `requireWithinNamespace` asserts against the *resolved* path — the same
 * defense OkHttp gave for free by resolving `..` client-side, mirroring what a spec-compliant server
 * does with dot segments (RFC 3986 §5.2.4) before ever routing the request. Ported onto Ktor's own
 * `appendPathSegments` unchanged, that defense would silently stop firing: a literal `..` segment
 * never pops anything, so the built URL's first segments always still read as the namespace prefix,
 * and `requireWithinNamespace`'s `take(prefix.size) != prefix` check would never trip.
 *
 * Popping past the first segment already appended is a no-op, not an error — mirrors OkHttp's own
 * "nothing left to pop" behaviour. The escape is still caught downstream: once there is nothing left
 * to pop, every further real segment (`etc`, `passwd`, ...) lands at the front of what remains,
 * which no longer matches the namespace prefix either.
 */
internal fun URLBuilder.appendPathSegmentsResolvingDots(raw: String): URLBuilder = apply {
    raw.split('/').forEach { piece ->
        when (piece) {
            "." -> Unit
            ".." -> if (encodedPathSegments.isNotEmpty()) {
                encodedPathSegments = encodedPathSegments.dropLast(1)
            }
            else -> appendPathSegments(piece)
        }
    }
}

/**
 * Appends [segment] as exactly one path segment, mirroring OkHttp's `HttpUrl.Builder.addPathSegment`
 * (singular) — not [appendPathSegmentsResolvingDots], which mirrors the *plural* `addPathSegments`
 * and is the wrong replacement here: OkHttp drew a real behavioural line between the two that this
 * has to preserve for call sites (Ollama's OCI tag) that only ever held one path component and never
 * meant `/` inside it to be a delimiter.
 *
 * Verified against OkHttp 4.12.0's own source (`HttpUrl.Builder.push`, `PATH_SEGMENT_ENCODE_SET`):
 * `addPathSegment(pathSegment)` runs the *entire* string through one `canonicalize` call — the same
 * segment encode set used per-piece by the plural form, `" \"<>^`{}|/\\?#"` — so any `/` inside it is
 * percent-encoded to `%2F` as part of one opaque segment, never treated as a delimiter. Only when the
 * *whole* canonicalized string is exactly `.` or `..` does OkHttp's shared `push()` treat it
 * specially — skip, or pop one segment — the identical dot-handling plural `addPathSegments` gets per
 * split piece, just evaluated once, over the whole un-split string, here. A tag of
 * `"../../../../secret-endpoint"` therefore becomes one segment,
 * `..%2F..%2F..%2F..%2Fsecret-endpoint` — the literal `..`s never reach a `/`-delimited position, so
 * no RFC-3986-normalizing proxy downstream can resolve them into a traversal. A tag of exactly `".."`
 * still pops one segment, exactly once — OkHttp's `push()` doesn't distinguish which call site fed it
 * that string.
 *
 * [encodeURLPathPart] is Ktor's own per-segment percent-encoder and encodes the identical character
 * set OkHttp's `PATH_SEGMENT_ENCODE_SET` does (confirmed empirically against the literal set above,
 * including that `&` and `+` are left literal) — it is what [appendPathSegments] already uses per
 * split piece; this calls it directly, once, over the whole unsplit [segment], which is the one thing
 * [appendPathSegments] cannot do (it re-splits on `/` even for a single vararg argument).
 */
internal fun URLBuilder.appendOpaqueSegment(segment: String): URLBuilder = apply {
    when (segment) {
        "." -> Unit
        ".." -> if (encodedPathSegments.isNotEmpty()) {
            encodedPathSegments = encodedPathSegments.dropLast(1)
        }
        else -> encodedPathSegments = encodedPathSegments + segment.encodeURLPathPart()
    }
}
