package io.github.nesfeder.supabase.demo.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nesfeder.supabase.MutationStrategy
import io.github.nesfeder.supabase.ReadStrategy
import io.github.nesfeder.supabase.SupabaseOfflineClient
import io.github.nesfeder.supabase.demo.domain.Task
import io.github.nesfeder.supabase.demo.domain.TaskPatch
import io.github.nesfeder.supabase.insert
import io.github.nesfeder.supabase.update
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
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

class TaskListViewModel(
    private val offlineClient: SupabaseOfflineClient,
) : ViewModel() {

    private val tasksTable get() = offlineClient["tasks"]

    /**
     * Reactive list of tasks. Backed by `selectAsFlow` so it re-emits whenever the local cache
     * is updated (after a successful network round trip, a queued mutation, or an explicit sync).
     */
    val tasks: StateFlow<List<Task>> = tasksTable.selectAsFlow(columns = Columns.ALL) {
        order("created_at", Order.DESCENDING)
    }
        .map { result -> result.decodeList<Task>() }
        .catch { emit(emptyList()) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingMutationCount: StateFlow<Int> = offlineClient.pendingMutationCountFlow()
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _error.value = null
            try {
                tasksTable.select(
                    columns = Columns.ALL,
                    readStrategy = ReadStrategy.NetworkFirst(),
                ) {
                    order("created_at", Order.DESCENDING)
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to refresh tasks"
            }
        }
    }

    fun addTask(title: String, notes: String?) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return

        viewModelScope.launch {
            _error.value = null
            try {
                tasksTable.insert(
                    value = Task(
                        title = trimmedTitle,
                        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                    mutationStrategy = MutationStrategy.QueueIfOffline,
                )
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to add task"
            }
        }
    }

    fun toggleDone(task: Task) {
        val taskId = task.id ?: return
        viewModelScope.launch {
            _error.value = null
            try {
                tasksTable.update(
                    value = TaskPatch(isDone = !task.isDone),
                    mutationStrategy = MutationStrategy.QueueIfOffline,
                ) {
                    filter { eq("id", taskId) }
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to update task"
            }
        }
    }

    fun delete(task: Task) {
        val taskId = task.id ?: return
        viewModelScope.launch {
            _error.value = null
            try {
                tasksTable.delete(mutationStrategy = MutationStrategy.QueueIfOffline) {
                    filter { eq("id", taskId) }
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to delete task"
            }
        }
    }

    fun syncPendingMutationsIfAny() {
        viewModelScope.launch {
            _error.value = null
            val pending = runCatching { offlineClient.pendingMutationCount() }.getOrElse { return@launch }
            if (pending <= 0) return@launch

            _isSyncing.value = true
            try {
                offlineClient.syncAllPendingMutations()
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to sync pending changes"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
