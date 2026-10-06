package com.awaki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.awaki.data.repository.WorkspaceRepository

/**
 * The background work a test class started, kept so that class can end it.
 *
 * A [WorkspaceRepository] runs its scans, git reads and web probes on a scope of its own
 * that resumes on Main, and that scope outlives the last assertion unless somebody
 * cancels it. Work still on a worker thread dispatches its result through the Main
 * dispatcher the *next* test class is busy installing or resetting, and
 * kotlinx-coroutines-test fails a write that crosses a read with
 * `Dispatchers.Main is used concurrently with setting it` — on whichever class happened
 * to be tearing down.
 *
 * So: hold what you build here, and call [closeAll] from `@After`, before
 * `Dispatchers.resetMain()`. Clearing through a [ViewModelStore] is what ends a view
 * model: it cancels `viewModelScope` and reaches `onCleared`, which is where a view model
 * disposes the repository behind it.
 */
class HeldWork {

  private val store = ViewModelStore()
  private val repositories = mutableListOf<WorkspaceRepository>()
  private var nextKey = 0

  /** Registers a view model — and with it the repository it holds — and returns it. */
  fun <T : ViewModel> hold(viewModel: T): T {
    store.put("held-${nextKey++}", viewModel)
    return viewModel
  }

  /** Registers a repository a test built without a view model in front of it. */
  fun hold(repository: WorkspaceRepository): WorkspaceRepository {
    repositories += repository
    return repository
  }

  fun closeAll() {
    store.clear()
    repositories.forEach { it.dispose() }
    repositories.clear()
  }
}
