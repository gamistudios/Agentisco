package com.agentisco.background

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * The single source of truth for "Agentisco is still busy".
 *
 * It lives on the process, not on a screen: an agent turn, a build stage or a
 * rootfs download registers itself here, and the foreground service, the
 * notification and the wake lock all follow this list. Whoever owns the work
 * decides when it ends, so nothing can leave the app awake by accident - an id
 * that is begun twice simply replaces its own record.
 */
class WorkRegistry {

  private val _active = MutableStateFlow<List<ActiveWork>>(emptyList())

  /** Live work, oldest first. */
  val active: StateFlow<List<ActiveWork>> = _active.asStateFlow()

  /** Stops the work behind an id (cancel the job, kill the process, ...). */
  private val cancellers = ConcurrentHashMap<String, () -> Unit>()

  fun begin(id: String, kind: WorkKind, label: String, detail: String = "", canceller: (() -> Unit)? = null) {
    val record = ActiveWork(
      id = id,
      kind = kind,
      label = label,
      detail = detail,
      cancellable = canceller != null
    )
    _active.update { list -> list.filterNot { it.id == id } + record }
    if (canceller == null) cancellers.remove(id) else cancellers[id] = canceller
  }

  /** Replaces the label/detail of a running record, e.g. the agent's status line. */
  fun setProgress(id: String, label: String = "", detail: String = "") {
    _active.update { list ->
      list.map {
        if (it.id != id) it
        else it.copy(label = label.ifBlank { it.label }, detail = detail)
      }
    }
  }

  /**
   * Marks work that cannot continue until the user answers. The notification
   * layer raises this to an alert: a turn parked on an approval otherwise looks
   * exactly like a turn that is happily grinding.
   */
  fun setAttention(id: String, needed: Boolean, reason: String = "") {
    _active.update { list ->
      list.map {
        if (it.id != id) it else it.copy(needsAttention = needed, detail = reason.ifBlank { it.detail })
      }
    }
  }

  fun end(id: String) {
    _active.update { list -> list.filterNot { it.id == id } }
    cancellers.remove(id)
  }

  fun has(id: String): Boolean = _active.value.any { it.id == id }

  /** Asks the owner of [id] to stop. Returns false when nothing is registered. */
  fun cancel(id: String): Boolean {
    val canceller = cancellers[id] ?: return false
    runCatching { canceller() }
    return true
  }
}
