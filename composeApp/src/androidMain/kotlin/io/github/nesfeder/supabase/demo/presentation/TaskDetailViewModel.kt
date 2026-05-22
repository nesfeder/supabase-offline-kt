package io.github.nesfeder.supabase.demo.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nesfeder.supabase.MutationStrategy
import io.github.nesfeder.supabase.ReadStrategy
import io.github.nesfeder.supabase.SupabaseOfflineClient
import io.github.nesfeder.supabase.demo.domain.Task
import io.github.nesfeder.supabase.demo.domain.TaskPatch
import io.github.nesfeder.supabase.demo.domain.TaskPhoto
import io.github.nesfeder.supabase.ensureLocalFile
import io.github.nesfeder.supabase.insert
import io.github.nesfeder.supabase.update
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class TaskDetailViewModel(
    private val offlineClient: SupabaseOfflineClient,
    private val taskId: String,
) : ViewModel() {

    private val tasksTable get() = offlineClient["tasks"]
    private val photosTable get() = offlineClient["task_photos"]
    private val photosBucket get() = offlineClient.storage("task-photos")

    private val _task = MutableStateFlow<Task?>(null)
    val task: StateFlow<Task?> = _task.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    data class PhotoEntry(val photo: TaskPhoto, val localFile: File?)

    /**
     * Reactive list of (photo row + local file path) for this task. The local file may be `null`
     * while the bucket download is still in progress or if the device is offline and the file
     * has not been cached yet.
     */
    val photos: StateFlow<List<PhotoEntry>> = photosTable.selectAsFlow(columns = Columns.ALL) {
        filter { eq("task_id", taskId) }
        order("uploaded_at", Order.DESCENDING)
    }
        .map { result -> result.decodeList<TaskPhoto>() }
        .catch { emit(emptyList()) }
        .map { rows ->
            rows.map { row ->
                val localFile = runCatching {
                    photosBucket.ensureLocalFile(objectPath = row.objectPath, isPublic = true)
                }.getOrNull()
                PhotoEntry(photo = row, localFile = localFile)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        loadTask()
    }

    private fun loadTask() {
        viewModelScope.launch {
            _error.value = null
            try {
                val result = tasksTable.select(
                    columns = Columns.ALL,
                    readStrategy = ReadStrategy.CacheFirst(),
                ) {
                    filter { eq("id", taskId) }
                }
                _task.value = result.decodeList<Task>().firstOrNull()
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to load task"
            }
        }
    }

    fun updateTask(title: String, notes: String?) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return
        viewModelScope.launch {
            _error.value = null
            try {
                tasksTable.update(
                    value = TaskPatch(
                        title = trimmedTitle,
                        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                    mutationStrategy = MutationStrategy.QueueIfOffline,
                ) {
                    filter { eq("id", taskId) }
                }
                _task.value = _task.value?.copy(
                    title = trimmedTitle,
                    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                )
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to update task"
            }
        }
    }

    fun addPhoto(bytes: ByteArray, contentType: String?) {
        if (bytes.isEmpty()) return
        viewModelScope.launch {
            _error.value = null
            _isUploading.value = true
            try {
                val objectPath = "$taskId/${UUID.randomUUID()}"
                withContext(Dispatchers.IO) {
                    photosBucket.upload(path = objectPath, data = bytes) {
                        upsert = true
                        contentType?.let { this.contentType = ContentType.parse(it) }
                    }
                }
                photosTable.insert(
                    value = TaskPhoto(
                        taskId = taskId,
                        objectPath = objectPath,
                        contentType = contentType,
                        sizeBytes = bytes.size.toLong(),
                    ),
                    mutationStrategy = MutationStrategy.QueueIfOffline,
                )
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to upload photo"
            } finally {
                _isUploading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
