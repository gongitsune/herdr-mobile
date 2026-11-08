package io.herdrmobile.android.ui.files

import io.herdrmobile.core.files.FileChunk
import io.herdrmobile.core.rpc.HcError
import org.junit.Assert.*
import org.junit.Test

class FileErrorsTest {
    @Test fun binaryContentDoesNotRenderAsSourceCode() {
        try { requireText(FileChunk("header\u0000data", 1, 1, 1)); fail("Expected binary_file") }
        catch (error: HcError) { assertEquals("binary_file", error.kind) }
    }
    @Test fun unicodeTextRemainsUnchanged() {
        val chunk = FileChunk("日本語\n# content", 1, 2, 2)
        assertEquals(chunk, requireText(chunk))
    }
}
