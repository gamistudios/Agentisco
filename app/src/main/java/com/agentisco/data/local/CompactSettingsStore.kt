package com.agentisco.data.local

import android.content.Context
import com.agentisco.agent.compact.CompactPolicyConfig
import org.json.JSONObject
import java.io.File

/**
 * Durable compaction settings (Settings → Context & compaction).
 *
 * Stored as JSON under the app-private dir, degrading to in-memory operation
 * when [context] is null (tests / previews) — same contract as the other
 * stores in this package.
 */
data class CompactSettings(
  /** Master switch for automatic compaction. */
  val autoCompactEnabled: Boolean = true,
  /** Also clear old tool results locally (the cheap tier). */
  val microcompactEnabled: Boolean = true,
  /** Manual "Compact now" from the session menu. */
  val manualCompactEnabled: Boolean = true,
  /** Assistant-started rounds kept verbatim after a summary. */
  val keepRecentRounds: Int = CompactPolicyConfig.DEFAULT_KEEP_RECENT_ROUNDS,
  /** Tool-result groups kept verbatim by the local tier. */
  val keepRecentToolResultGroups: Int = CompactPolicyConfig.DEFAULT_KEEP_RECENT_TOOL_RESULT_GROUPS,
  /** Consecutive failed compactions before the circuit breaker opens. */
  val maxConsecutiveFailures: Int = CompactPolicyConfig.DEFAULT_MAX_CONSECUTIVE_FAILURES,
  /**
   * Percentage of the available context window at which compaction runs
   * (100 = with the safety buffer already subtracted). Lower values compact
   * earlier and more often.
   */
  val thresholdPercent: Int = CompactPolicyConfig.DEFAULT_THRESHOLD_PERCENT,
  /** Show the live context percentage next to the composer dropdowns. */
  val showContextUsage: Boolean = true
) {
  /**
   * Projects the user's settings onto the internal policy, keeping the
   * context window and output reserve of the *selected model* as the only
   * runtime inputs.
   */
  fun toPolicyConfig(contextWindow: Int?, maxOutputTokens: Int?): CompactPolicyConfig =
    CompactPolicyConfig.forModel(
      contextWindow = contextWindow,
      maxOutputTokens = maxOutputTokens,
      overrides = CompactPolicyConfig(
        enabled = autoCompactEnabled,
        thresholdPercent = thresholdPercent,
        maxConsecutiveFailures = maxConsecutiveFailures.coerceAtLeast(1),
        keepRecentRounds = keepRecentRounds.coerceAtLeast(0),
        microcompactEnabled = microcompactEnabled,
        keepRecentToolResultGroups = keepRecentToolResultGroups.coerceAtLeast(1),
        microcompactTools = CompactPolicyConfig.DEFAULT_MICROCOMPACT_TOOLS
      )
    )
}

class CompactSettingsStore(private val context: Context? = null) {

  private val configFile: File? = context?.getDir("agentisco", Context.MODE_PRIVATE)?.resolve("compact.json")
  private var cached: CompactSettings? = null

  @Synchronized
  fun get(): CompactSettings {
    cached?.let { return it }
    val loaded = configFile?.takeIf { it.isFile }?.let { file ->
      runCatching {
        val obj = JSONObject(file.readText())
        CompactSettings(
          autoCompactEnabled = obj.optBoolean("autoCompactEnabled", true),
          microcompactEnabled = obj.optBoolean("microcompactEnabled", true),
          manualCompactEnabled = obj.optBoolean("manualCompactEnabled", true),
          keepRecentRounds = obj.optInt("keepRecentRounds", CompactPolicyConfig.DEFAULT_KEEP_RECENT_ROUNDS),
          keepRecentToolResultGroups = obj.optInt(
            "keepRecentToolResultGroups",
            CompactPolicyConfig.DEFAULT_KEEP_RECENT_TOOL_RESULT_GROUPS
          ),
          maxConsecutiveFailures = obj.optInt("maxConsecutiveFailures", CompactPolicyConfig.DEFAULT_MAX_CONSECUTIVE_FAILURES),
          thresholdPercent = obj.optInt("thresholdPercent", CompactPolicyConfig.DEFAULT_THRESHOLD_PERCENT),
          showContextUsage = obj.optBoolean("showContextUsage", true)
        )
      }.getOrNull()
    } ?: CompactSettings()
    cached = loaded
    return loaded
  }

  @Synchronized
  fun update(transform: (CompactSettings) -> CompactSettings): CompactSettings {
    val next = transform(get())
    cached = next
    runCatching {
      val file = configFile ?: return@runCatching
      file.parentFile?.mkdirs()
      file.writeText(
        JSONObject().apply {
          put("autoCompactEnabled", next.autoCompactEnabled)
          put("microcompactEnabled", next.microcompactEnabled)
          put("manualCompactEnabled", next.manualCompactEnabled)
          put("keepRecentRounds", next.keepRecentRounds)
          put("keepRecentToolResultGroups", next.keepRecentToolResultGroups)
          put("maxConsecutiveFailures", next.maxConsecutiveFailures)
          put("thresholdPercent", next.thresholdPercent)
          put("showContextUsage", next.showContextUsage)
        }.toString(2)
      )
    }
    return next
  }
}
