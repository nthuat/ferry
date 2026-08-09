package dev.thuat.ferry

import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments

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
