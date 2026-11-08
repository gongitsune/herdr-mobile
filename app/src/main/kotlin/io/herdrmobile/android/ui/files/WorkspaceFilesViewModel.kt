package io.herdrmobile.android.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.ui.asHcError
import io.herdrmobile.core.files.*
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class WorkspaceFilesViewModel(private val connections: HostConnections, private val hostId: String, val cwd: String, private val machine: String?, private val demo: Boolean) : ViewModel() {
    val changes = MutableStateFlow<ChangeSet?>(null)
    val staged = MutableStateFlow(false)
    val diff = combine(changes, staged) { changes, staged -> diffRows(if (staged) changes?.stagedDiff.orEmpty() else changes?.diff.orEmpty()) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val directories = MutableStateFlow<Map<String, List<FileEntry>>>(emptyMap())
    val expanded = MutableStateFlow<Set<String>>(emptySet())
    val tree = combine(directories, expanded, ::flattenTree).flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selectedFile = MutableStateFlow<FileEntry?>(null)
    val file = MutableStateFlow<FileChunk?>(null)
    val error = MutableStateFlow<HcError?>(null)
    private val operations = MutableStateFlow(0)
    val busy = operations.map { it > 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val loadingDirectories = MutableStateFlow<Set<String>>(emptySet())
    private var changeJob: Job? = null
    private var fileJob: Job? = null
    private val folderJobs = mutableMapOf<String, Job>()
    fun refreshChanges() {
        changeJob?.cancel()
        changeJob = viewModelScope.launch {
            operations.update { it + 1 }; error.value = null
            try { changes.value = if (demo) FakeFiles.changes else client().changes(cwd, on = machine) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.asHcError() }
            finally { operations.update { (it - 1).coerceAtLeast(0) } }
        }
    }
    fun refreshFiles() { selectedFile.value?.let(::open) ?: loadFolder(refresh = true) }
    fun loadFolder(path: String = ".", refresh: Boolean = false) {
        if (path in loadingDirectories.value || (!refresh && path in directories.value)) return
        folderJobs[path] = viewModelScope.launch {
            loadingDirectories.value = loadingDirectories.value + path
            error.value = null
            try {
                val entries = if (demo) FakeFiles.list(path) else client().listFiles(cwd, path, on = machine)
                directories.value = directories.value + (path to entries)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.asHcError() }
            finally { loadingDirectories.value = loadingDirectories.value - path }
        }
    }
    fun toggle(entry: FileEntry) {
        if (!entry.directory || entry.symlink) return
        if (entry.path in expanded.value) expanded.value = expanded.value - entry.path
        else { expanded.value = expanded.value + entry.path; loadFolder(entry.path) }
    }
    fun open(entry: FileEntry) {
        if (entry.directory) { toggle(entry); return }
        fileJob?.cancel(); selectedFile.value = entry; file.value = null; error.value = null
        fileJob = read(entry, more = false)
    }
    fun more() {
        val entry = selectedFile.value ?: return
        val chunk = file.value ?: return
        if (fileJob?.isActive == true || busy.value || chunk.last >= chunk.lines || chunk.last < chunk.first || chunk.text.length >= 1_000_000) return
        fileJob = read(entry, more = true)
    }
    private fun read(entry: FileEntry, more: Boolean) = viewModelScope.launch {
        operations.update { it + 1 }; error.value = null
        try {
            val old = if (more) file.value else null
            val next = if (demo) FakeFiles.read(entry.path) else client().readFile(entry.path, cwd, offset = old?.last?.plus(1) ?: 1, limit = 1000, on = machine)
            requireText(next)
            if (selectedFile.value == entry) file.value = if (old == null) next.copy(text = next.text.take(1_000_000)) else next.copy(first = old.first,
                text = (old.text + (if (old.text.endsWith("\n") || old.text.isEmpty()) "" else "\n") + next.text).take(1_000_000))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (selectedFile.value == entry) error.value = e.asHcError() }
        finally { operations.update { (it - 1).coerceAtLeast(0) } }
    }
    fun backToTree() { fileJob?.cancel(); selectedFile.value = null; file.value = null; error.value = null }
    fun cancelLoads() { changeJob?.cancel(); fileJob?.cancel(); folderJobs.values.forEach { it.cancel() }; folderJobs.clear() }
    private fun client() = checkNotNull(connections.client(hostId)) { "Host disconnected" }
}
