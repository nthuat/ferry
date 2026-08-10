package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber

internal actual fun defaultHttpClient(): HttpClient = HttpClient(Darwin)

internal actual fun defaultFileSystem(): FileSystem = FileSystem.SYSTEM

@OptIn(ExperimentalForeignApi::class)
internal actual fun availableBytes(path: Path): Long {
    val attributes = NSFileManager.defaultManager
        .attributesOfFileSystemForPath(path.toString(), null)
        ?: return 0L
    return (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: 0L
}
