package com.awaki.ui

/**
 * The plan the agent published for the current task, read back out of the
 * transcript instead of kept as separate state.
 *
 * `task_plan` is stateless: the model re-publishes the whole step list every
 * time it updates. Deriving the plan from those persisted calls rather than
 * listening to them means one component can show a plan that survives a
 * restart, updates in place as the flow re-emits, and always holds the newest
 * version — with no second copy of the steps anywhere to fall out of sync.
 */
enum class PlanStepState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class PlanStep(
  val number: Int,
  val content: String,
  val state: PlanStepState,
  /** The model's own progress note for this step, e.g. "24 / 86". */
  val detail: String = "",
  /** Wall time between the publish that opened the step and the one that closed it. */
  val seconds: Double = 0.0
)

data class AgentPlan(
  val title: String,
  val note: String,
  val steps: List<PlanStep>,
  /** Which turn published this plan — the identity the live clock restarts on. */
  val turnId: String,
  /** False once that turn has ended: the plan states its result, not a promise. */
  val running: Boolean,
  /** When the open step was published as under way, so the card can count up. */
  val stepStartedAt: Long
) {
  val total: Int get() = steps.size
  /** Only a step the agent actually finished counts here; a skipped one is not done. */
  val done: Int get() = steps.count { it.state == PlanStepState.DONE }
  val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
  val isOpen: Boolean get() = steps.any { it.state == PlanStepState.PENDING || it.state == PlanStepState.RUNNING }
  val current: PlanStep? get() = steps.firstOrNull { it.state == PlanStepState.RUNNING }
}

/**
 * The activity stream hides these rows: the pinned plan card shows them, so a
 * step update must never add a card to the conversation.
 */
fun TurnBlock.isPlanPublish(): Boolean = this is ActionBlock && name == "task_plan"

/**
 * The newest plan in the transcript, with each step's duration measured from
 * the publishes that opened and closed it. Null when the agent never planned.
 */
fun agentPlanFrom(items: List<ChatItem>): AgentPlan? {
  var latest: PlanSnapshot? = null
  // Content is the step's identity across publishes; a renamed step is a new step.
  val lastSighting = LinkedHashMap<String, Long>()
  val settledAt = LinkedHashMap<String, Long>()
  val openedAt = LinkedHashMap<String, Long>()
  var turnId = ""
  var running = false

  for (item in items) {
    if (item !is AgentTurnItem) continue
    for (block in item.blocks) {
      if (!block.isPlanPublish()) continue
      block as ActionBlock
      val parsed = parsePlan(block.argsJson) ?: continue
      latest = parsed
      turnId = item.id
      running = item.status == TurnStatus.RUNNING
      for (step in parsed.steps) {
        val key = step.content
        if (key.isBlank()) continue
        // Publishes are read in transcript order, so the previous sighting is
        // the last moment we know the step was still open.
        val opened = lastSighting[key] ?: block.createdAt
        val settled = step.state == PlanStepState.DONE ||
          step.state == PlanStepState.FAILED ||
          step.state == PlanStepState.SKIPPED
        if (settled && !settledAt.containsKey(key)) {
          settledAt[key] = block.createdAt
          openedAt[key] = opened
        } else if (step.state == PlanStepState.RUNNING &&
          !openedAt.containsKey(key) && !settledAt.containsKey(key)
        ) {
          openedAt[key] = opened
        }
        lastSighting[key] = block.createdAt
      }
    }
  }

  val snapshot = latest ?: return null
  val current = snapshot.steps.firstOrNull { it.state == PlanStepState.RUNNING }
  return AgentPlan(
    title = snapshot.title,
    note = snapshot.note,
    steps = snapshot.steps.mapIndexed { index, step ->
      val start = openedAt[step.content] ?: 0L
      val end = settledAt[step.content] ?: 0L
      step.copy(
        number = index + 1,
        seconds = if (start > 0L && end > start) (end - start) / 1000.0 else 0.0
      )
    },
    turnId = turnId,
    running = running,
    stepStartedAt = current?.let { openedAt[it.content] } ?: 0L
  )
}

private data class PlanSnapshot(val title: String, val note: String, val steps: List<PlanStep>)

/**
 * Reads one `task_plan` call's arguments. Tolerant by design: the step list is
 * written by a model, so a missing field, a bare string step, or a status
 * spelled another way must still yield a plan rather than nothing.
 */
private fun parsePlan(argsJson: String): PlanSnapshot? {
  if (argsJson.isBlank()) return null
  return runCatching {
    val args = org.json.JSONObject(argsJson)
    val array = args.optJSONArray("steps") ?: return null
    val steps = ArrayList<PlanStep>(array.length())
    for (i in 0 until array.length()) {
      val raw = array.opt(i)
      val content: String
      val state: PlanStepState
      val detail: String
      if (raw is org.json.JSONObject) {
        content = raw.optString("content").ifBlank { raw.optString("description") }.trim()
        state = planStateOf(raw.optString("status"))
        detail = raw.optString("detail").trim()
      } else {
        // A model that saw its own previous plan back as `1. [x] apply fix`
        // tends to send that shape, so the marker counts as its status.
        val (marker, text) = splitMarkedStep(raw?.toString().orEmpty())
        content = text
        state = marker
        detail = ""
      }
      if (content.isNotBlank()) steps.add(PlanStep(steps.size + 1, content, state, detail))
    }
    if (steps.isEmpty()) return null
    PlanSnapshot(
      title = args.optString("title").trim(),
      note = args.optString("note").trim(),
      steps = steps
    )
  }.getOrNull()
}

private fun planStateOf(status: String): PlanStepState = when (status.trim().lowercase()) {
  "done", "completed", "complete", "finished" -> PlanStepState.DONE
  "in_progress", "in progress", "inprogress", "active", "running", "doing" -> PlanStepState.RUNNING
  "failed", "error", "errored" -> PlanStepState.FAILED
  "cancelled", "canceled", "skipped", "skip" -> PlanStepState.SKIPPED
  else -> PlanStepState.PENDING
}

/** Splits a `[x] apply fix` style step into its state and its text. */
private fun splitMarkedStep(raw: String): Pair<PlanStepState, String> {
  val trimmed = raw.trim()
  if (trimmed.length < 4 || trimmed[0] != '[' || trimmed.indexOf(']') != 2) {
    return PlanStepState.PENDING to trimmed
  }
  val state = when (trimmed[1]) {
    'x', 'X', '✓' -> PlanStepState.DONE
    '>' -> PlanStepState.RUNNING
    '!' -> PlanStepState.FAILED
    '-', '~' -> PlanStepState.SKIPPED
    ' ', 'o' -> PlanStepState.PENDING
    else -> return PlanStepState.PENDING to trimmed
  }
  return state to trimmed.substring(3).trim()
}
