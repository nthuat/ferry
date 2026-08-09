package dev.thuat.ferry

import io.ktor.client.HttpClient
import okio.Path

/** Per-platform default engine: OkHttp on JVM, Darwin on iOS. */
internal expect fun defaultHttpClient(): HttpClient

/** Free bytes on the volume holding [path]. [path] must exist (callers probe nearestExistingAncestor). */
internal expect fun availableBytes(path: Path): Long
