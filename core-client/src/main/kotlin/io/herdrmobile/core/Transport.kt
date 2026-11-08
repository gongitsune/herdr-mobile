package io.herdrmobile.core
import java.io.*
interface ByteTransport : Closeable {
    val input: InputStream
    val output: OutputStream
}
fun interface TransportFactory { suspend fun open(): ByteTransport }
