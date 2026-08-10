package dev.thuat.ferry

import io.ktor.client.HttpClient
import okio.FileSystem
import okio.Path

/** Per-platform default engine: OkHttp on JVM, Darwin on iOS. */
internal expect fun defaultHttpClient(): HttpClient

/** Free bytes on the volume holding [path]. [path] must exist (callers probe nearestExistingAncestor). */
internal expect fun availableBytes(path: Path): Long

/**
 * The real filesystem, per platform.
 *
 * `okio.FileSystem.SYSTEM` is not itself a common declaration — it is a separate `actual`-only
 * property on each of okio's jvm and native artifacts, with no `expect` counterpart in okio's own
 * commonMain (okio also targets JS, which has no system filesystem to give one). Referencing
 * `FileSystem.SYSTEM` directly from this module's own commonMain code resolves only by accident,
 * when a per-platform target compile (jvm, iosArm64, …) merges commonMain with that platform's real
 * dependency classpath; the metadata compile that produces this module's own common klib — needed to
 * publish a root Gradle module — has no such classpath and fails with "Unresolved reference
 * 'SYSTEM'". This expect/actual pair is what `defaultHttpClient` already does for the same reason.
 */
internal expect fun defaultFileSystem(): FileSystem
