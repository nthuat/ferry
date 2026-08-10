package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okio.FileSystem
import okio.Path

internal actual fun defaultHttpClient(): HttpClient = HttpClient(OkHttp)

internal actual fun availableBytes(path: Path): Long = path.toFile().usableSpace

internal actual fun defaultFileSystem(): FileSystem = FileSystem.SYSTEM
