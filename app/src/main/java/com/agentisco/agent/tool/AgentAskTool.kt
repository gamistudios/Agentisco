package com.agentisco.agent.tool

import com.agentisco.agent.model.PendingApproval
import org.json.JSONObject

/**
 * The agent asks the user something and waits, instead of guessing. A choice the
 * user makes costs one tap; the same choice guessed wrong costs a rebuilt
 * workspace. Options keep the answer short and unambiguous, and the user can
 * always type their own.
 */
class AskUserTool : AgentTool {
  override val name = "ask_user"
  override val description =
    "Ask the user a question and wait for the answer. Use it when a decision genuinely belongs to the user (which library, which behaviour, destructive trade-offs) and the options are not derivable from the workspace. Give 2-${MAX_OPTIONS} short, mutually exclusive options; the user can always type a free-text answer."
  override val params = listOf(
    ToolParam("question", "One clear question, e.g. \"Cache the compiled model in the project folder or in app storage?\"."),
    ToolParam(
      "options",
      "JSON array of ${MAX_OPTIONS}-or-fewer short answer labels the user can pick, e.g. [\"project folder\", \"app storage\"].",
      type = "array", required = false, allowBlank = true
    ),
    ToolParam(
      "allow_free_text",
      "Let the user type an answer instead of picking an option (default true).",
      type = "boolean", required = false
    )
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val question = args.str("question").trim()
    if (question.isBlank()) return ToolResult(false, error = "question must be a non-empty question for the user.")
    val rawOptions = args.optJSONArray("options")
    val options = mutableListOf<String>()
    if (rawOptions != null) {
      for (i in 0 until rawOptions.length()) {
        val option = rawOptions.opt(i)?.toString()?.trim().orEmpty()
        if (option.isNotBlank() && option !in options) options += option.take(MAX_OPTION_CHARS)
      }
    }
    if (options.size > MAX_OPTIONS) {
      return ToolResult(
        false,
        error = "options must hold at most $MAX_OPTIONS choices (got ${options.size}); merge similar ones or ask a narrower question."
      )
    }
    val allowFreeText = args.optBoolean("allow_free_text", true)
    if (options.isEmpty() && !allowFreeText) {
      return ToolResult(false, error = "A question needs options or a free-text answer; allow_free_text false with no options leaves the user no way to reply.")
    }

    val answer = ctx.askUser(
      PendingApproval(
        id = newApprovalId(),
        command = question,
        title = "The agent has a question",
        impactDescription = question,
        isQuestion = true,
        options = options.toList(),
        allowFreeText = allowFreeText
      )
    )
    if (answer.isNullOrBlank()) {
      return ToolResult(
        success = true,
        output = "The user did not answer (the question was dismissed). Decide with your best judgment, state the assumption you made, and do not ask the same question again.",
        metadata = mapOf("answered" to "false")
      )
    }
    return ToolResult(
      success = true,
      output = "User answered: $answer",
      metadata = mapOf("answered" to "true")
    )
  }

  companion object {
    const val MAX_OPTIONS = 4
    const val MAX_OPTION_CHARS = 120
  }
}
