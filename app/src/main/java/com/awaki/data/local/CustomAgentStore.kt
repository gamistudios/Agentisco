package com.awaki.data.local

import android.content.Context
import com.awaki.agent.model.AgentRole
import com.awaki.agent.model.AgentRoles
import com.awaki.agent.model.UNLIMITED_ITERATIONS
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's own specialists, stored as JSON under the app-private dir.
 *
 * A custom agent is saved as exactly the data a built-in seat is made of - name,
 * purpose, brief, scope, tools, model - because that is what makes it a seat and
 * not a preset: the runtime, the tool registry and the team board all read the
 * same [AgentRole], so nothing has to learn that this one came from the user.
 *
 * Degrades to in-memory operation when [context] is null (unit tests, previews).
 */
class CustomAgentStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("awaki", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("custom_agents.json")

  private var cached: List<AgentRole>? = null

  @Synchronized
  fun get(): List<AgentRole> {
    cached?.let { return it }
    val loaded = configFile?.takeIf { it.isFile }?.let { file ->
      runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
          array.optJSONObject(i)?.let(::fromJson)
        }
      }.getOrNull()
    } ?: emptyList()
    // Re-normalized rather than trusted: a file edited by hand, or written by an
    // older build, must not put a seat on the team that cannot be run.
    val normalized = normalize(loaded)
    cached = normalized
    return normalized
  }

  /** Persist the list exactly as the app will use it, and return that state. */
  @Synchronized
  fun set(agents: List<AgentRole>): List<AgentRole> {
    val normalized = normalize(agents)
    cached = normalized
    runCatching {
      val file = configFile ?: return normalized
      val array = JSONArray()
      normalized.forEach { array.put(toJson(it)) }
      file.writeText(array.toString(2))
    }
    return normalized
  }

  /** Replace one entry by id, or add it when the id is new. */
  @Synchronized
  fun save(agent: AgentRole): List<AgentRole> =
    set(if (get().any { it.id == agent.id }) get().map { if (it.id == agent.id) agent else it } else get() + agent)

  @Synchronized
  fun remove(id: String): List<AgentRole> = set(get().filterNot { it.id == id.trim() })

  companion object {

    /** An agent with no name or no brief is not an agent; it is dropped. */
    fun isValid(name: String, systemPrompt: String): Boolean =
      name.isNotBlank() && systemPrompt.isNotBlank()

    /**
     * A delegable id: one addressable token, lowercase, no spaces. Built-in ids
     * are reserved - the id is how the model calls a seat, and a custom agent that
     * took `backend`'s id would make the team ambiguous.
     */
    fun slugId(raw: String, name: String): String {
      val source = raw.trim().ifBlank { name.trim() }
      val slug = source.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
      return slug.take(40)
    }

    /** The list as it will be used: valid entries only, unique ids, sane limits. */
    fun normalize(agents: List<AgentRole>): List<AgentRole> {
      val taken = AgentRoles.builtIn.map { it.id }.toMutableSet()
      return agents.mapNotNull { role ->
        if (!isValid(role.name, role.systemPrompt)) return@mapNotNull null
        val base = slugId(role.id, role.name).ifBlank { return@mapNotNull null }
        var id = base
        var suffix = 2
        while (id in taken) id = "$base-$suffix".also { suffix++ }
        taken += id
        role.copy(
          id = id,
          name = role.name.trim(),
          purpose = role.purpose.trim().ifBlank { role.systemPrompt.trim().lineSequence().first().trim().take(160) },
          systemPrompt = role.systemPrompt.trim(),
          scope = role.scope.trim(),
          modelId = role.modelId.trim(),
          toolNames = role.toolNames.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
          // A custom agent writes its own brief, so the built-in lane lists are unused.
          responsibilities = emptyList(),
          handsOff = emptyList(),
          craft = emptyList(),
          builtIn = false,
          // A user may still choose a budget for their own agent; not having one is
          // the default, and a saved "unlimited" must survive a reload.
          maxToolIterations =
            if (role.maxToolIterations == UNLIMITED_ITERATIONS) UNLIMITED_ITERATIONS
            else role.maxToolIterations.coerceIn(8, 600)
        )
      }
    }

    private fun toJson(role: AgentRole): JSONObject = JSONObject()
      .put("id", role.id)
      .put("name", role.name)
      .put("purpose", role.purpose)
      .put("systemPrompt", role.systemPrompt)
      .put("scope", role.scope)
      .put("toolNames", JSONArray(role.toolNames))
      .put("modelId", role.modelId)
      .put("readOnly", role.readOnly)
      .put("maxToolIterations", role.maxToolIterations)

    private fun fromJson(obj: JSONObject): AgentRole? = runCatching {
      AgentRoles.custom(
        id = obj.optString("id"),
        name = obj.optString("name"),
        purpose = obj.optString("purpose"),
        systemPrompt = obj.optString("systemPrompt"),
        scope = obj.optString("scope"),
        toolNames = obj.optJSONArray("toolNames").stringList(),
        modelId = obj.optString("modelId"),
        readOnly = obj.optBoolean("readOnly", false),
        // A file written before "unlimited" was the default has a number here; a
        // file written without one means no ceiling, not forty rounds.
        maxToolIterations = obj.optInt("maxToolIterations", UNLIMITED_ITERATIONS)
      )
    }.getOrNull()

    private fun JSONArray?.stringList(): List<String> {
      if (this == null) return emptyList()
      return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
  }
}
