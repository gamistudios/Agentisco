package com.awaki.agent.tool

import com.awaki.agent.skill.AgentSkill
import com.awaki.agent.skill.SkillStore
import java.io.File
import org.json.JSONObject

/**
 * Load a skill's instructions.
 *
 * One tool serves both questions an agent can have. With a name it returns that
 * skill's own text; without one it returns the index, because an agent that was
 * not told about a skill still needs a way to find out what exists.
 */
class UseSkillTool(private val skills: SkillStore) : AgentTool {
  override val name = "use_skill"
  override val description =
    "Read the full instructions of a skill - a packaged set of know-how for this project or app " +
      "(how we test, how a release is cut, what our commit style is). Omit name to list every " +
      "skill with what it covers. Follow a skill once you have read it; it exists because someone " +
      "wrote down how this work should be done."
  override val params = listOf(
    ToolParam(
      "name",
      "The skill to load, exactly as listed in the available skills. Omit to list them.",
      required = false
    )
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val available = skills.discover(File(ctx.project.path))
    if (available.isEmpty()) {
      return ToolResult(
        success = true,
        output = "No skills are available for this project. A skill is a folder under " +
          "\"${SkillStore.PROJECT_DIR}/<name>/SKILL.md\" holding instructions the agent should follow."
      )
    }

    val wanted = args.str("name").trim()
    if (wanted.isEmpty()) {
      return ToolResult(success = true, output = index(available), metadata = mapOf("count" to available.size.toString()))
    }

    val skill = available.match(wanted)
      ?: return ToolResult(
        success = false,
        error = "No skill named \"$wanted\". Available: ${available.joinToString(", ") { it.name }}. " +
          "Call use_skill with no name for what each one covers."
      )

    return ToolResult(success = true, output = render(skill), metadata = mapOf("skill" to skill.name))
  }

  private fun render(skill: AgentSkill) = buildString {
    appendLine("Skill: ${skill.displayName}  (${skill.path})")
    appendLine(skill.description)
    appendLine()
    append(skill.instructions)
  }

  companion object {
    /** The index every run is shown up front, so a skill can be used by name. */
    fun index(skills: List<AgentSkill>): String = buildString {
      appendLine("Available skills (load one with use_skill before doing that kind of work):")
      skills.forEach { appendLine("- ${it.name}: ${it.description}") }
    }

    /**
     * An agent types what the prompt showed it, which is a name; it also types it
     * loosely, so the label and a near miss resolve before it does not. Exact wins
     * over prefix over containment, and only ever within one scope - a skill the
     * project overrode must not be reachable under the installed one's name.
     */
    fun List<AgentSkill>.match(wanted: String): AgentSkill? {
      val key = SkillStore.slug(wanted).ifEmpty { wanted.trim().lowercase() }
      return firstOrNull { it.name == key }
        ?: firstOrNull { it.displayName.equals(wanted.trim(), ignoreCase = true) }
        ?: filter { it.name.startsWith(key) && key.isNotEmpty() }.takeIf { it.size == 1 }?.first()
        ?: filter { it.name.contains(key) && key.isNotEmpty() }.takeIf { it.size == 1 }?.first()
    }
  }
}
