package io.herdrmobile.android.ui.files

import org.junit.Assert.*
import org.junit.Test

class DiffRowsTest {
    @Test fun fileHeadersAreNotCountedAsAddedOrRemovedAndHunksResetNumbers() {
        val rows = diffRows("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -10,2 +20,2 @@\n-old\n+new\n context\n@@ -30,0 +40,1 @@\n+added\n\\ No newline at end of file")
        assertEquals(DiffKind.HEADER, rows[1].kind)
        assertEquals(DiffKind.HEADER, rows[2].kind)
        assertEquals(10, rows[4].oldLine)
        assertEquals(20, rows[5].newLine)
        assertEquals(11, rows[6].oldLine)
        assertEquals(21, rows[6].newLine)
        assertEquals(40, rows[8].newLine)
        assertEquals(DiffKind.HEADER, rows[9].kind)
    }
}
