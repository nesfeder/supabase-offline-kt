package io.github.nesfeder.supabase.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.nesfeder.supabase.SupabaseOfflineClient
import io.github.nesfeder.supabase.demo.domain.Task
import io.github.nesfeder.supabase.demo.presentation.TaskDetailViewModel
import io.github.nesfeder.supabase.demo.presentation.TaskListViewModel
import io.github.nesfeder.supabase.demo.util.ConnectivityObserver

@Composable
fun DemoApp(
    offlineClient: SupabaseOfflineClient,
    connectivity: ConnectivityObserver,
) {
    val isOnline by connectivity.isOnline.collectAsState(initial = true)

    val listViewModel = remember(offlineClient) {
        TaskListViewModel(offlineClient = offlineClient)
    }

    var selectedTaskId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isOnline) {
        if (isOnline) {
            listViewModel.syncPendingMutationsIfAny()
        }
    }

    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            val currentId = selectedTaskId
            if (currentId == null) {
                TaskListScreen(
                    viewModel = listViewModel,
                    isOnline = isOnline,
                    onTaskClick = { task: Task -> selectedTaskId = task.id },
                )
            } else {
                val detailViewModel = remember(currentId) {
                    TaskDetailViewModel(offlineClient = offlineClient, taskId = currentId)
                }
                TaskDetailScreen(
                    viewModel = detailViewModel,
                    onBack = { selectedTaskId = null },
                )
            }
        }
    }
}
