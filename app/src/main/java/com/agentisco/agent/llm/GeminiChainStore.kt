package com.agentisco.agent.llm

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Server-side continuation state for one conversation on a stateful protocol.
 *
 * The Gemini Interactions API keeps the transcript on Google's servers: a turn
 * sends only its own delta plus the [interactionId] of the turn before it. The
 * chain is transitive, so every finished response replaces the stored id.
 */
data class GeminiChainState(
  val interactionId: String,
  val environmentId: String? = null,
  val updatedAt: Long = 0L
)

interface GeminiChainStore {
  fun load(key: String): GeminiChainState?
  fun save(key: String, state: GeminiChainState)
  fun clear(key: String)
}

/**
 * Durable chain storage under app-private filesDir, so an app restart resumes
 * the same server-side conversation. Holds only opaque interaction ids — never
 * credentials, never message content. Degrades to memory when [context] is null
 * (unit tests / preview).
 */
class GeminiChainStoreImpl(context: Context? = null) : GeminiChainStore {

  private companion object {
    const val MAX_ENTRIES = 200
  }

  private val file: File? = context?.getDir("agentisco", Context.MODE_PRIVATE)?.resolve("interactions.json")
  private val entries = java.util.concurrent.ConcurrentHashMap<String, GeminiChainState>()

  init {
    val raw = file?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    if (raw != null) {
      runCatching {
        val obj = JSONObject(raw)
        for (key in obj.keys()) {
          val e = obj.optJSONObject(key) ?: continue
          val id = e.optString("interactionId").takeIf { it.isNotBlank() } ?: continue
          entries[key] = GeminiChainState(
            interactionId = id,
            environmentId = e.optString("environmentId").takeIf { it.isNotBlank() },
            updatedAt = e.optLong("updatedAt")
          )
        }
      }
    }
  }

  override fun load(key: String): GeminiChainState? = entries[key]

  override fun save(key: String, state: GeminiChainState) {
    entries[key] = state
    prune()
    flush()
  }

  override fun clear(key: String) {
    if (entries.remove(key) != null) flush()
  }

  /** Drops the least recently updated chains once the cap is exceeded. */
  private fun prune() {
    if (entries.size <= MAX_ENTRIES) return
    entries.entries.sortedBy { it.value.updatedAt }
      .take(entries.size - MAX_ENTRIES)
      .forEach { entries.remove(it.key) }
  }

  private fun flush() {
    val target = file ?: return
    runCatching {
      val obj = JSONObject()
      for ((key, state) in entries) {
        obj.put(key, JSONObject().apply {
          put("interactionId", state.interactionId)
          state.environmentId?.let { put("environmentId", it) }
          put("updatedAt", state.updatedAt)
        })
      }
      target.writeText(obj.toString())
    }
  }
}
