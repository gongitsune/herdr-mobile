package io.herdrmobile.android.ui.files

import io.herdrmobile.core.files.*

object FakeFiles {
    val changes = ChangeSet("~/develop/herdr-mobile", listOf(ChangeEntry("app/Main.kt", indexStatus = ".", worktreeStatus = "M")),
        "diff --git a/app/Main.kt b/app/Main.kt\n--- a/app/Main.kt\n+++ b/app/Main.kt\n@@ -1,2 +1,2 @@\n-val title = \"Console\"\n+val title = \"Agents\"\n show(title)", "")
    fun list(path: String): List<FileEntry> = when (path) {
        "." -> listOf(FileEntry("app", "app", true), FileEntry("README.md", "README.md", false))
        "app" -> listOf(FileEntry("Main.kt", "app/Main.kt", false))
        else -> emptyList()
    }
    fun read(path: String) = FileChunk(if (path.endsWith("Main.kt")) "val title = \"Agents\"\nshow(title)" else "# herdr mobile\n\nRead-only workspace preview.", 1, if (path.endsWith("Main.kt")) 2 else 3, if (path.endsWith("Main.kt")) 2 else 3)
}
