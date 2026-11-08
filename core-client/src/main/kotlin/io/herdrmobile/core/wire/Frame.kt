package io.herdrmobile.core.wire

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Frame(val channel: Long, val kind: Int, val payload: ByteArray = byteArrayOf()) {
    companion object {
        const val DATA = 0
        const val CREDIT = 1
        const val CLOSE = 2
        const val RESIZE = 3
        const val FOCUS = 4
        const val FACTS = 5
        const val AUTOMATIC_INPUT = 6
    }
}
object FrameCodec {
    const val MAX_PAYLOAD = 16 * 1024 * 1024
    fun encode(frame: Frame): ByteArray {
        require(frame.channel in 0..0xffffffffL && frame.kind in 0..255)
        require(frame.payload.size <= MAX_PAYLOAD)
        return ByteBuffer.allocate(9 + frame.payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(frame.channel.toInt()).put(frame.kind.toByte()).putInt(frame.payload.size)
            .put(frame.payload).array()
    }
    fun decode(input: InputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val header = ByteArray(9)
        header[0] = first.toByte()
        readFully(input, header, 1, 8)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channel = b.int.toLong() and 0xffffffffL
        val kind = b.get().toInt() and 255
        val len = b.int.toLong() and 0xffffffffL
        require(len <= MAX_PAYLOAD) { "Frame exceeds 16MiB: $len" }
        val payload = ByteArray(len.toInt())
        readFully(input, payload, 0, payload.size)
        return Frame(channel, kind, payload)
    }
    private fun readFully(input: InputStream, bytes: ByteArray, offset: Int, length: Int) {
        var pos = offset
        while (pos < offset + length) {
            val n = input.read(bytes, pos, offset + length - pos)
            if (n < 0) throw EOFException("Truncated frame")
            if (n == 0) {
                val one = input.read()
                if (one < 0) throw EOFException("Truncated frame")
                bytes[pos++] = one.toByte()
            } else pos += n
        }
    }
    fun u32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    fun size(cols: Int, rows: Int): ByteArray {
        require(cols in 1..65535 && rows in 1..65535)
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(cols.toShort()).putShort(rows.toShort()).array()
    }
}
