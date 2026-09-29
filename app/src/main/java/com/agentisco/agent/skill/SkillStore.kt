package com.agentisco.agent.skill

import android.content.Context
import java.io.File

/** Where a skill was found: in this project, or installed for every project. */
enum class SkillScope { PROJECT, APP }

/**
 * A packaged piece of know-how the agent can pull in when a task calls for it.
 *
 * A skill is a folder holding a `SKILL.md`: a name, one line saying when to use
 * it, and the instructions themselves. It is documentation rather than code - the
 * agent still decides whether a task warrants it, and reads it before acting.
 *
 * [description] is what the agent sees on every turn, so it has to be enough to
 * decide from; [instructions] are only loaded once it has decided.
 */
data class AgentSkill(
  /** How the agent names it in `use_skill`; unique across the team's skills. */
  val name: String,
  val displayName: String,
  /** When to use this - the line the agent chooses from. */
  val description: String,
  val instructions: String,
  val scope: SkillScope,
  /** Relative path of the `SKILL.md` this came from, for "where is it" answers. */
  val path: String
)

/**
 * The skills available to a workspace, read straight from the folders - there is no
 * database and no install step. Drop `.agentisco/skills/testing/SKILL.md` in a repo
 * and every agent working in it can use it; put it under the app's own directory
 * and it applies everywhere.
 *
 * A project skill wins over an installed one with the same name: the repo knows
 * what this repo does better than a general skill does.
 */
class SkillStore(private val context: Context? = null) {

  /** The app-private skills folder, shared by every project. */
  private val installedRoot: File? = context?.getDir("agentisco", Context.MODE_PRIVATE)?.resolve(INSTALLED_DIR)

  /** Every skill this workspace can use, project ones last so they win by name. */
  fun discover(projectRoot: File?): List<AgentSkill> {
    val byName = linkedMapOf<String, AgentSkill>()
    load(installedRoot, SkillScope.APP)?.forEach { byName[it.name] = it }
    projectRoot?.let { root ->
      load(File(root, PROJECT_DIR), SkillScope.PROJECT)?.forEach { byName[it.name] = it }
    }
    return byName.values.sortedBy { it.name }
  }

  private fun load(dir: File?, scope: SkillScope): List<AgentSkill>? {
    dir ?: return null
    val folders = runCatching { dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } }.getOrNull()
      ?: return null
    return folders.mapNotNull { folder ->
      val file = File(folder, FILE_NAME)
      if (!file.isFile) return@mapNotNull null
      val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
      // A skill that cannot be addressed is not a skill: the folder name is the id.
      parse(folder.name, text, scope, relativePath(scope, folder.name))
    }
  }

  private fun relativePath(scope: SkillScope, folder: String) =
    (if (scope == SkillScope.PROJECT) "$PROJECT_DIR/" else "$INSTALLED_DIR/") + "$folder/$FILE_NAME"

  companion object {
    const val PROJECT_DIR = ".agentisco/skills"

    /** Under the app's own `agentisco/` directory. */
    const val INSTALLED_DIR = "skills"
    const val FILE_NAME = "SKILL.md"

    /** Keys this app understands; any other frontmatter line is left alone. */
    private val KEY_LINE = Regex("^(name|description)\\s*:\\s*(.*)$")

    /**
     * `SKILL.md` as it is written by hand: optional frontmatter with `name` and
     * `description`, then the body. With no frontmatter the first heading is the
     * name and the first paragraph the description, so a plain markdown file a
     * user already has is still usable.
     */
    fun parse(folderName: String, text: String, scope: SkillScope, path: String): AgentSkill? {
      val lines = text.replace("\r\n", "\n").lines()
      var front: Map<String, String> = emptyMap()
      var bodyFrom = 0

      if (lines.firstOrNull()?.trim() == "---") {
        val close = lines.indices.drop(1).firstOrNull { lines[it].trim() == "---" }
        if (close != null) {
          front = lines.subList(1, close).mapNotNull { line ->
            KEY_LINE.matchEntire(line.trim())?.let { it.groupValues[1] to it.groupValues[2].trim() }
          }.toMap()
          bodyFrom = close + 1
        }
      }

      val body = lines.drop(bodyFrom).joinToString("\n").trim()
      if (body.isBlank() && front["description"].isNullOrBlank()) return null

      val heading = body.lineSequence().firstOrNull { it.trim().startsWith("#") }
        ?.trim()?.trimStart('#')?.trim()?.takeIf { it.isNotEmpty() }
      val description = front["description"]?.takeIf { it.isNotBlank() }
        ?: body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: heading ?: ""

      return AgentSkill(
        // The folder is the address, and a `name:` line is how it is written down;
        // the heading is only a label, so renaming it never moves the skill.
        name = slug(front["name"]?.takeIf { it.isNotBlank() } ?: folderName),
        displayName = heading ?: front["name"]?.takeIf { it.isNotBlank() } ?: folderName,
        description = description.trim(),
        // An agent follows the instructions verbatim, so what it gets is the file,
        // not a re-typed summary of it.
        instructions = body.ifBlank { description },
        scope = scope,
        path = path
      ).takeIf { it.name.isNotEmpty() && it.description.isNotEmpty() }
    }

    /** The id an agent types: lowercase, ASCII, single dashes. */
    fun slug(raw: String): String = raw.trim().lowercase()
      .map { if (it.isLetterOrDigit()) it else '-' }
      .joinToString("")
      .replace(Regex("-+"), "-")
      .trim('-')
      .take(48)

    /**
     * The same file a user would write by hand. `name` holds the address the agent
     * types - the folder, so an editor renaming the label cannot move the skill out
     * from under a running agent - and the heading is the readable title.
     */
    fun render(skill: AgentSkill): String = buildString {
      appendLine("---")
      appendLine("name: ${skill.name}")
      appendLine("description: ${skill.description.replace("\n", " ")}")
      appendLine("---")
      appendLine()
      appendLine("# ${skill.displayName}")
      appendLine()
      appendLine(skill.instructions.trim().removePrefix("# ${skill.displayName}").trim())
    }

    /** Where a skill of this scope lives. Null when that scope has nowhere to go. */
    fun fileFor(scope: SkillScope, context: Context?, projectRoot: File?, name: String): File? {
      val base = when (scope) {
        SkillScope.PROJECT -> projectRoot?.let { File(it, PROJECT_DIR) }
        SkillScope.APP -> context?.getDir("agentisco", Context.MODE_PRIVATE)?.resolve(INSTALLED_DIR)
      } ?: return null
      return File(base, name).resolve(FILE_NAME)
    }
  }

  /** Write one skill out and return the workspace's list as it now stands. */
  @Synchronized
  fun save(projectRoot: File?, skill: AgentSkill): List<AgentSkill> {
    val file = fileFor(skill.scope, context, projectRoot, skill.name)
    runCatching {
      file ?: return@runCatching
      file.parentFile?.mkdirs()
      file.writeText(render(skill))
    }
    return discover(projectRoot)
  }

  @Synchronized
  fun delete(projectRoot: File?, name: String, scope: SkillScope): List<AgentSkill> {
    val folder = fileFor(scope, context, projectRoot, name)?.parentFile
    runCatching { if (folder?.isDirectory == true) folder.deleteRecursively() }
    return discover(projectRoot)
  }
}
