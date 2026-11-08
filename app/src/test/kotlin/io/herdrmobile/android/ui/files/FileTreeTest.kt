package io.herdrmobile.android.ui.files

import io.herdrmobile.core.files.FileEntry
import org.junit.Assert.*
import org.junit.Test

class FileTreeTest {
    @Test fun directoriesPrecedeFilesAndOnlyExpandedNonSymlinkDirectoriesRecurse() {
        val entries = mapOf("." to listOf(FileEntry("z.txt", "z.txt", false), FileEntry("app", "app", true), FileEntry("link", "link", true, true)),
            "app" to listOf(FileEntry("Main.kt", "app/Main.kt", false)), "link" to listOf(FileEntry("secret", "link/secret", false)))
        val rows = flattenTree(entries, setOf("app", "link"))
        assertEquals(listOf("app", "app/Main.kt", "link", "z.txt"), rows.map { it.entry.path })
        assertEquals(1, rows[1].depth)
    }
    @Test fun selfReferencingDirectoryDoesNotLoop() {
        val entries = mapOf("." to listOf(FileEntry("loop", ".", true)))
        assertEquals(1, flattenTree(entries, setOf(".")).size)
    }
}
