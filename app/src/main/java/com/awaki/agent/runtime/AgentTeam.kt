package com.awaki.agent.runtime

import com.awaki.agent.model.AgentRole

/** How far a seat on the team board has got. */
enum class AgentWorkStatus {
  RUNNING,
  DONE,
  FAILED,
  CANCELLED
}

/**
 * A readable copy of one seat, taken at the moment it was asked for. Seats are
 * live objects, so anything outside the runtime that wants to show them (a status
 * strip, a log line) reads through this instead of holding the handle.
 */
data class AgentSeatView(
  val id: String,
  val roleId: String,
  val roleName: String,
  val task: String,
  val files: List<String>,
  val status: AgentWorkStatus,
  val delegated: Boolean
)

/**
 * One agent's place on [AgentTeamBoard].
 *
 * The file list is not copied at join time: it is the runtime's own live set of
 * files it has changed, so what other agents see grows as this agent actually
 * edits. A claim is a fact about work already done, not a promise made before
 * the first file is opened.
 */
class AgentSeat internal constructor(
  val id: String,
  private val roleId: String,
  private val roleName: String,
  private val task: String,
  private val delegated: Boolean,
  private val files: Set<String>,
  private val board: AgentTeamBoard
) {

  @Volatile
  var status: AgentWorkStatus = AgentWorkStatus.RUNNING
    private set

  /** Closes the seat: it stops appearing to other agents from this moment. */
  fun finish(status: AgentWorkStatus) {
    this.status = status
    board.onSeatClosed(this)
  }

  fun view(): AgentSeatView = AgentSeatView(
    id = id,
    roleId = roleId,
    roleName = roleName,
    task = task,
    files = files.toList().sorted(),
    status = status,
    delegated = delegated
  )
}

/**
 * Who is working in one workspace right now, and which files each of them holds.
 *
 * A delegated run is given the board of the agent that delegated to it, so every
 * run in a turn — the parent, each specialist, and specialists delegated beside
 * each other — is told before it edits that another agent is in the same tree.
 * That is what turns "several chats that happen to share a folder" into a team:
 * the overlap is visible instead of being something each agent has to guess at.
 *
 * [promptBlock] is the only thing that reaches a model, and it lists other
 * agents' work in progress only. A finished agent is kept in [snapshot] for a
 * while because that is what the user just watched, but it no longer owns
 * anything, so telling a running agent to keep out of its files would be wrong.
 *
 * A run reads the board once, when its prompt is built, and the block says so in
 * its own wording. Refreshing it every round trip would cost a rebuild of the
 * largest message in the transcript for information that is usually the same.
 */
class AgentTeamBoard {

  private companion object {
    /** Rows in one ACTIVE AGENTS block: past this the list is noise, not guidance. */
    const val MAX_AGENTS_SHOWN = 8

    /** Files named per agent before the list is cut short. */
    const val MAX_FILES_SHOWN = 10

    /** Characters of a task description before it is shortened. */
    const val TASK_CHARS = 140

    /** Closed seats kept for the UI before the oldest are dropped. */
    const val CLOSED_SEATS_KEPT = 6

    /** A task is one bullet line, however the brief that produced it was written. */
    val LINE_BREAKS = Regex("\\s+")

    /** What the block says when the agent asking is the only one here. */
    const val ALONE =
      "Workspace team: no other agent was working in this workspace when you started, so nothing is claimed - every file is yours."

    // The block is injected once, when the run is built, so it says when it was true
    // rather than claiming to be current: an agent that trusts a stale list would
    // avoid files nobody holds any more.
    val HEADER = "The workspace team as it was when you started (these agents were working then, and may still be):"

    val RULE =
      "Those are other agents' edits, not the state your task was written against. Never revert, reformat or " +
        "overwrite one of them: re-read a listed file before touching it, and stay inside the files your own task " +
        "names. The agent that delegated your work is in this list too - its changes are the state you continue " +
        "from. If your part genuinely cannot be done without changing another agent's file, make the smallest edit " +
        "that keeps its work and say exactly what you did in your report."
  }

  private val lock = Any()

  /** Join order, so a block reads in the order the agents were started. */
  private val seats = mutableListOf<AgentSeat>()
  private var started = 0

  /**
   * Opens a seat for a run that is about to start. [files] must be the runtime's
   * live set of modified files, and the returned handle must be closed with
   * [AgentSeat.finish] on every path the run can take — an unclosed seat would
   * keep telling later agents that a finished run is still editing.
   */
  fun join(role: AgentRole?, task: String, delegated: Boolean, files: Set<String>): AgentSeat =
    synchronized(lock) {
      val seat = AgentSeat(
        id = "agent-${++started}",
        roleId = role?.id ?: "main",
        roleName = role?.name ?: "Main agent",
        task = task.replace(LINE_BREAKS, " ").trim(),
        delegated = delegated,
        files = files,
        board = this@AgentTeamBoard
      )
      seats.add(seat)
      seat
    }

  /** Drops a closed seat once the board has more recent history to show. */
  internal fun onSeatClosed(seat: AgentSeat) {
    synchronized(lock) {
      val closed = seats.filter { it.status != AgentWorkStatus.RUNNING }
      if (closed.size > CLOSED_SEATS_KEPT) {
        closed.take(closed.size - CLOSED_SEATS_KEPT).forEach { seats.remove(it) }
      }
    }
  }

  /** Every seat on the board, oldest first. */
  fun snapshot(excludingId: String? = null): List<AgentSeatView> = synchronized(lock) {
    seats.filterNot { it.id == excludingId }.map { it.view() }
  }

  /**
   * The block injected into a run's system prompt: who else is working, on what,
   * and which files they have already touched. [excludingId] is the asking
   * agent's own seat — an agent is never told to keep out of its own files.
   */
  fun promptBlock(excludingId: String?): String {
    val running = synchronized(lock) {
      seats
        .filter { it.status == AgentWorkStatus.RUNNING && it.id != excludingId }
        .map { it.view() }
    }
    if (running.isEmpty()) return ALONE
    return buildString {
      appendLine(HEADER)
      running.take(MAX_AGENTS_SHOWN).forEach { seat ->
        val files = seat.files.take(MAX_FILES_SHOWN).joinToString(", ").ifEmpty { "none yet" }
        val more = seat.files.size - minOf(seat.files.size, MAX_FILES_SHOWN)
        appendLine("- ${seat.roleName} (${seat.roleId}): ${seat.task.ellipsised(TASK_CHARS)} — files: $files" + if (more > 0) " (+$more more)" else "")
      }
      if (running.size > MAX_AGENTS_SHOWN) {
        appendLine("(${running.size - MAX_AGENTS_SHOWN} more agents are running; treat their files as claimed too)")
      }
      append(RULE)
    }.trimEnd()
  }

  private fun String.ellipsised(limit: Int): String =
    if (length <= limit) this else take(limit).trimEnd() + "…"
}
