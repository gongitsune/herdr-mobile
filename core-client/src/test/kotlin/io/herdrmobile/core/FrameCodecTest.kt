package io.herdrmobile.core

import io.herdrmobile.core.wire.*
import org.junit.jupiter.api.Test
import java.io.*
import kotlin.test.*

class FrameCodecTest {
    @Test fun partialReadsAndUnsignedChannel() {
        val bytes = FrameCodec.encode(Frame(0xffffffffL, Frame.DATA, "hello".toByteArray()))
        assertContentEquals(byteArrayOf(-1,-1,-1,-1,0,5,0,0,0), bytes.copyOf(9))
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1,len))
        }
        val frame = FrameCodec.decode(input)!!
        assertEquals(0xffffffffL, frame.channel)
        assertEquals(Frame.DATA, frame.kind)
        assertContentEquals("hello".toByteArray(), frame.payload)
        assertNull(FrameCodec.decode(input))
    }
    @Test fun truncatedHeaderAndPayload() {
        val bytes = FrameCodec.encode(Frame(1, Frame.DATA, byteArrayOf(1,2)))
        for (n in 1 until bytes.size) assertFailsWith<EOFException> { FrameCodec.decode(ByteArrayInputStream(bytes.copyOf(n))) }
    }
    @Test fun sizeLimit() {
        val max = Frame(0, Frame.DATA, ByteArray(FrameCodec.MAX_PAYLOAD))
        assertEquals(FrameCodec.MAX_PAYLOAD, FrameCodec.decode(ByteArrayInputStream(FrameCodec.encode(max)))!!.payload.size)
        assertFailsWith<IllegalArgumentException> { FrameCodec.encode(Frame(0, 0, ByteArray(FrameCodec.MAX_PAYLOAD+1))) }
        assertFailsWith<IllegalArgumentException> {
            FrameCodec.decode(ByteArrayInputStream(byteArrayOf(0,0,0,0,0,-1,-1,-1,-1)))
        }
    }
}
