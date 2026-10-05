/**
 * The conversation-compaction subsystem.
 *
 * Files in this package:
 *  - `CompactPolicy.kt`      thresholds, decision reasons, circuit breaker
 *  - `CompactTokens.kt`      token estimation + the live usage meter
 *  - `CompactRounds.kt`      assistant-started round grouping
 *  - `Microcompact.kt`       local tool-result clearing (no LLM)
 *  - `CompactPrompt.kt`      summary prompt + post-summary continuation message
 *  - `ManualCompact.kt`      round selection, transcript assembly, boundary
 *  - `CompactCoordinator.kt` runs both tiers over one transcript
 *
 * [com.awaki.agent.runtime.AgentRuntime] is the only caller: it compacts
 * automatically before each provider request, and on demand through
 * [com.awaki.agent.runtime.AgentRuntime.compactNow]. The persisted chat
 * transcript is never rewritten — only the message list that goes to the
 * provider is compacted, so the UI keeps every message the user has ever seen.
 */
package com.awaki.agent.compact
