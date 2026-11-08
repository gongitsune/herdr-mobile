package io.herdrmobile.android.ui.files

import io.herdrmobile.core.files.FileEntry

data class TreeRow(val entry: FileEntry, val depth: Int)
fun flattenTree(entries: Map<String, List<FileEntry>>, expanded: Set<String>): List<TreeRow> {
    val out = mutableListOf<TreeRow>()
    val visited = mutableSetOf<String>()
    fun visit(path: String, depth: Int) {
        if (!visited.add(path) || depth > 32 || out.size >= 5000) return
        entries[path].orEmpty().sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name.lowercase() }).forEach { entry ->
            if (out.size >= 5000) return@forEach
            out += TreeRow(entry, depth)
            // Symlink directories are never recursively expanded.
            if (entry.directory && !entry.symlink && entry.path in expanded) visit(entry.path, depth + 1)
        }
    }
    visit(".", 0)
    return out
}
