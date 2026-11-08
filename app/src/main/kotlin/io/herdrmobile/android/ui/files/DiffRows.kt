package io.herdrmobile.android.ui.files

enum class DiffKind { HEADER, HUNK, ADD, REMOVE, CONTEXT }
data class DiffRow(val text: String, val kind: DiffKind, val oldLine: Int? = null, val newLine: Int? = null)

/** Hunk numbers are independent on each side, including zero-line insertions. */
fun diffRows(diff: String): List<DiffRow> {
    var old = 0; var next = 0; var inHunk = false
    val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")
    return diff.lineSequence().map { line ->
        val match = hunk.matchEntire(line)
        when {
            match != null -> { old = (match.groupValues[1].toIntOrNull() ?: 0); next = (match.groupValues[2].toIntOrNull() ?: 0); inHunk = true; DiffRow(line, DiffKind.HUNK) }
            line.startsWith("diff --git ") -> { inHunk = false; DiffRow(line, DiffKind.HEADER) }
            !inHunk -> DiffRow(line, DiffKind.HEADER)
            line.startsWith("+") -> DiffRow(line, DiffKind.ADD, newLine = next++)
            line.startsWith("-") -> DiffRow(line, DiffKind.REMOVE, oldLine = old++)
            line.startsWith(" ") -> DiffRow(line, DiffKind.CONTEXT, old++, next++)
            else -> DiffRow(line, DiffKind.HEADER)
        }
    }.toList()
}
