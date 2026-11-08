package io.herdrmobile.core.files

import kotlinx.serialization.Serializable

@Serializable data class FileChunk(val text: String, val first: Long, val last: Long, val lines: Long)
data class FileEntry(val name: String, val path: String, val directory: Boolean, val symlink: Boolean = false)
data class ChangeEntry(val path: String, val oldPath: String? = null, val indexStatus: String,
                       val worktreeStatus: String, val untracked: Boolean = false)
data class ChangeSet(val cwd: String, val entries: List<ChangeEntry>, val diff: String,
                     val stagedDiff: String, val truncated: Boolean = false)
