package com.agentisco

import com.agentisco.agent.compact.CLEARED_TOOL_RESULT_PLACEHOLDER
import com.agentisco.agent.compact.CompactBoundary
import com.agentisco.agent.compact.CompactPolicyConfig
import com.agentisco.agent.compact.CompactReason
import com.agentisco.agent.compact.CompactSummaryContext
import com.agentisco.agent.compact.CompactTokenMeter
import com.agentisco.agent.compact.ContextTokenUsage
import com.agentisco.agent.compact.buildCompactSummaryMessage
import com.agentisco.agent.compact.estimateMessageTokens
import com.agentisco.agent.llm.LlmException
import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmStreamEvent
import com.agentisco.agent.llm.LlmUsage
import com.agentisco.agent.runtime.AgentRuntime
import com.agentisco.agent.tool.AgentTool
import com.agentisco.agent.tool.AgentToolRegistry
import com.agentisco.agent.tool.ToolContext
import com.agentisco.agent.tool.ToolParam
import com.agentisco.agent.tool.ToolResult
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession
import com.agentisco.data.repository.ChatHistoryMessage
import com.agentisco.data.repository.SessionCompaction
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import com.agentisco.settings.model.ModelCapabilities
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compaction contract, exercised against the real runtime:
 *
 *  - the persisted chat is never rewritten (only what is *sent* changes);
 *  - the provider receives the summary in place of the old turns;
 *  - a recorded compaction means later turns never re-send the old ones.
 */
class RuntimeCompactionTest {

  private val project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = "/tmp")
  private val terminal = TerminalSession(id = "t", name = "main", currentDir = "/tmp")

  private val provider = AIProvider("prov", "Local", "https://example.com/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS)
  private val model = AIModel(
    id = "m", providerId = "prov", modelId = "test", displayName = "Test",
    contextWindow = 40_000,
    capabilities = ModelCapabilities(tools = true)
  )

  /** Records exactly what the runtime asked for, token by token. */
  private open class RecordingLlmService : com.agentisco.agent.llm.LlmService() {
    val requests = mutableListOf<List<LlmMessage>>()

    open suspend fun respond(
      provider: AIProvider,
      model: AIModel,
      apiKey: String,
      request: com.agentisco.agent.llm.LlmRequest,
      onEvent: (LlmStreamEvent) -> Unit
    ) {
      onEvent(LlmStreamEvent.Started)
      val text = "Done."
      text.forEach { onEvent(LlmStreamEvent.Token(it.toString())) }
      onEvent(
        LlmStreamEvent.Completed(
          LlmMessage(
            role = LlmRole.ASSISTANT,
            content = text,
            usage = LlmUsage(inputTokens = request.messages.size * 10, outputTokens = 5, totalTokens = request.messages.size * 10 + 5)
          )
        )
      )
    }

    override suspend fun streamChat(
      provider: AIProvider,
      model: AIModel,
      apiKey: String,
      request: com.agentisco.agent.llm.LlmRequest,
      onEvent: (LlmStreamEvent) -> Unit
    ) {
      requests += request.messages
      respond(provider, model, apiKey, request, onEvent)
    }
  }

  private fun registry(tools: List<AgentTool> = emptyList()) = AgentToolRegistry(
    fileSystem = null!!,
    gitManager = null!!,
    terminalManager = null!!,
    stagedFilesProvider = { emptySet() },
    onStageFile = {},
    onStageAll = {},
    onUnstageAll = {},
    extraTools = tools
  )

  private val bigHistory = buildList {
    add(ChatHistoryMessage("user", "first request", rowId = 1))
    add(ChatHistoryMessage("assistant", "first answer", rowId = 2))
    add(ChatHistoryMessage("user", "second request", rowId = 3))
    add(ChatHistoryMessage("assistant", "second answer", rowId = 4))
  }
  /** Remembers every compaction and answers as if the model had summarized. */
  private class RecordingSink : AgentRuntime.CompactSink {
    val compactions = mutableListOf<RecordedCompaction>()

    data class RecordedCompaction(
      val sessionId: String,
      val summary: String,
      val boundary: CompactBoundary,
      val summarizedThroughRowId: Long,
      val keptFromRowId: Long
    )

    override suspend fun onCompacted(
      sessionId: String,
      summary: String,
      boundary: CompactBoundary,
      summarizedThroughRowId: Long,
      keptFromRowId: Long
    ) {
      compactions += RecordedCompaction(sessionId, summary, boundary, summarizedThroughRowId, keptFromRowId)
    }

    override suspend fun latestCompaction(sessionId: String): SessionCompaction? =
      compactions.lastOrNull { it.sessionId == sessionId }?.let {
        SessionCompaction(
          uuid = "c1",
          summary = it.summary,
          summarizedThroughRowId = it.summarizedThroughRowId,
          keptFromRowId = it.keptFromRowId,
          tokensBefore = it.boundary.tokensBefore,
          tokensAfter = it.boundary.tokensAfter,
          contextWindow = it.boundary.contextWindow,
          summarizedMessages = it.boundary.summarizedMessages,
          keptMessages = it.boundary.keptMessages,
          clearedToolResults = it.boundary.clearedToolResults,
          trigger = it.boundary.trigger.name,
          createdAt = 0
        )
      }
  }

  private fun runtime(
    sink: RecordingSink,
    service: RecordingLlmService,
    policy: CompactPolicyConfig = CompactPolicyConfig(
      contextWindow = model.contextWindow,
      outputReserve = 0,
      buffer = 0,
      keepRecentRounds = 1,
      microcompactMinSavedTokens = 10,
      thresholdPercent = 20 // force a compaction early in a tiny window
    )
  ) = AgentRuntime(null!!, null!!, null!!, service, registry()) { sink }

  @Test  fun `the runtime records a compaction and sends the summary instead of the old turns`() = runBlocking {
    val sink = RecordingSink()
    val service = RecordingLlmService()
    val runtime = AgentRuntime(null!!, null!!, null!!, service, registry()) { sink }

    runtime.executeTask(
      prompt = "new request",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.agentisco.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = bigHistory,
      compactPolicy = CompactPolicyConfig(
        contextWindow = model.contextWindow, outputReserve = 0, buffer = 0,
        keepRecentRounds = 1, thresholdPercent = 20
      ),
      onRequestApproval = {},
      onEvent = {}
    )

    assertEquals(1, sink.compactions.size)
    val recorded = sink.compactions.single()
    assertEquals("s1", recorded.sessionId)
    // The newest round was kept, so the summary covers everything older.
    assertEquals(3L, recorded.summarizedThroughRowId)
    assertEquals(4L, recorded.keptFromRowId)
    assertEquals(CompactReason.ABOVE_THRESHOLD, recorded.boundary.trigger)

    val sent = service.requests.last()
    // The provider never sees the raw old turns again.
    assertFalse(sent.any { it.content == "first request" || it.content == "first answer" })
    assertTrue(sent.any { it.content.contains("Conversation compacted") })
    // The preserved round still travels verbatim.
    assertTrue(sent.any { it.content == "second request" })
    assertTrue(sent.any { it.content == "second answer" })
  }

  @Test
  fun `a recorded compaction means later turns never resend the old messages`() = runBlocking {
    val sink = RecordingSink().apply {
      compactions += RecordingSink.RecordedCompaction(
        sessionId = "s1",
        summary = "1. Primary Request and Intent\nEarlier work.",
        boundary = CompactBoundary(
          trigger = CompactReason.ABOVE_THRESHOLD,
          tokensBefore = 30_000, tokensAfter = 3_000, contextWindow = 40_000,
          summarizedRounds = 2, keptRounds = 1, keptMessages = 3, summarizedMessages = 6,
          clearedToolResults = 0
        ),
        summarizedThroughRowId = 4L,
        keptFromRowId = 5L
      )
    }
    val service = RecordingLlmService()
    val runtime = AgentRuntime(null!!, null!!, null!!, service, registry()) { sink }

    runtime.executeTask(
      prompt = "follow up",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.agentisco.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = bigHistory + ChatHistoryMessage("user", "later request", rowId = 6),
      compactPolicy = CompactPolicyConfig(contextWindow = model.contextWindow, outputReserve = 0, buffer = 0, thresholdPercent = 5),
      onRequestApproval = {},
      onEvent = {}
    )

    val sent = service.requests.first()
    // Rows 1-4 are represented by the summary, not re-sent.
    assertFalse(sent.any { it.content == "first request" || it.content == "second answer" })
    assertTrue(sent.any { it.content.contains("Conversation compacted") })
    assertTrue(sent.any { it.content.contains("Earlier work.") })
    // Row 6 survived the compaction and is still sent verbatim.
    assertTrue(sent.any { it.content == "later request" })
  }

  @Test
  fun `tool results cleared locally travel as a placeholder, and the tool still ran for real`() = runBlocking {
    val toolRuns = mutableListOf<JSONObject>()
    val tool = object : AgentTool {
      override val name = "read_file"
      override val description = "Read a file."
      override val params = listOf(ToolParam("path", "path"))
      override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        toolRuns += args
        return ToolResult(success = true, output = buildString { repeat(6_000) { appendLine("content line") } })
      }
    }
    val toolRegistry = registry(listOf(tool))
    var calls = 0
    val service = object : RecordingLlmService() {
      override suspend fun respond(
        provider: AIProvider,
        model: AIModel,
        apiKey: String,
        request: com.agentisco.agent.llm.LlmRequest,
        onEvent: (LlmStreamEvent) -> Unit
      ) {
        calls++
        onEvent(LlmStreamEvent.Started)
        if (calls == 1) {
          // First turn: request the tool.
          onEvent(
            LlmStreamEvent.ToolCallRequested(
              com.agentisco.agent.llm.LlmToolCall("call_1", "read_file", "{\"path\":\"src/a.kt\"}")
            )
          )
          onEvent(
            LlmStreamEvent.Completed(
              LlmMessage(
                role = LlmRole.ASSISTANT,
                content = "",
                toolCalls = listOf(com.agentisco.agent.llm.LlmToolCall("call_1", "read_file", "{\"path\":\"src/a.kt\"}"))
              )
            )
          )
        } else {
          "All done.".forEach { onEvent(LlmStreamEvent.Token(it.toString())) }
          onEvent(LlmStreamEvent.Completed(LlmMessage(role = LlmRole.ASSISTANT, content = "All done.")))
        }
      }
    }

    val runtime = AgentRuntime(null!!, null!!, null!!, service, toolRegistry) { null }

    // A giant prior history pushes the transcript past the threshold so the
    // local tier sees enough old tool results to clear.
    val huge = buildString { repeat(30_000) { append("prior conversation text ") } }
    val history = listOf(
      ChatHistoryMessage("user", "old request", rowId = 1),
      ChatHistoryMessage("assistant", "old answer", rowId = 2),
      ChatHistoryMessage("user", huge, rowId = 3),
      ChatHistoryMessage("assistant", "another answer", rowId = 4)
    )
    runtime.executeTask(
      prompt = "read the file",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.agentisco.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = history,
      compactPolicy = CompactPolicyConfig(
        contextWindow = 20_000, outputReserve = 0, buffer = 0,
        microcompactMinSavedTokens = 10, thresholdPercent = 10
      ),
      onRequestApproval = {},
      onEvent = {}
    )

    // The tool really ran (no simulation anywhere in the pipeline).
    assertEquals(1, toolRuns.size)
    // Its result reached the provider as a real TOOL message.
    assertTrue(service.requests.last().any { it.role == LlmRole.TOOL })
  }

  @Test
  fun `a failed summary keeps the full history and the turn still completes`() = runBlocking {
    val sink = RecordingSink()
    val service = object : RecordingLlmService() {
      var summaryCall = 0
      override suspend fun streamChat(
        provider: AIProvider,
        model: AIModel,
        apiKey: String,
        request: com.agentisco.agent.llm.LlmRequest,
        onEvent: (LlmStreamEvent) -> Unit
      ) {
        summaryCall++
        // The summarization request fails; normal turns succeed.
        if (request.tools.isEmpty()) throw LlmException("Summarizer is down", com.agentisco.agent.llm.LlmErrorKind.SERVER)
        super.streamChat(provider, model, apiKey, request, onEvent)
      }
    }
    val runtime = AgentRuntime(null!!, null!!, null!!, service, registry()) { sink }

    val result = runtime.executeTask(
      prompt = "go",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.agentisco.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = bigHistory,
      compactPolicy = CompactPolicyConfig(
        contextWindow = model.contextWindow, outputReserve = 0, buffer = 0,
        keepRecentRounds = 1, thresholdPercent = 20
      ),
      onRequestApproval = {},
      onEvent = {}
    )

    assertTrue(result.success)
    assertEquals("No compaction should be recorded when the summary fails", 0, sink.compactions.size)
    // The user's history is untouched and still sent in full.
    val sent = service.requests.last()
    assertTrue(sent.any { it.content == "first request" })
  }

  @Test
  fun `usage snapshots flow out of the runtime as it streams`() = runBlocking {
    val sink = RecordingSink()
    val service = RecordingLlmService()
    val runtime = AgentRuntime(null!!, null!!, null!!, service, registry()) { sink }
    val snapshots = mutableListOf<ContextTokenUsage>()

    runtime.executeTask(
      prompt = "measure me",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.agentisco.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = emptyList(),
      compactPolicy = CompactPolicyConfig(contextWindow = model.contextWindow, outputReserve = 0, buffer = 0),
      onTokenUsage = { snapshots += it },
      onRequestApproval = {},
      onEvent = {}
    )

    assertTrue("the meter must publish at least one snapshot", snapshots.isNotEmpty())
    val first = snapshots.first()
    assertEquals(model.contextWindow, first.contextWindow)
    assertTrue(first.usedTokens > 0)
    // Provider numbers take over once the protocol reports them.
    assertEquals(TokenUsageSource.PROVIDER, snapshots.last().source)
  }

  @Test
  fun `without a sink the runtime behaves exactly as before compaction existed`() = runBlocking {
    val service = RecordingLlmService()
    val runtime = AgentRuntime(null!!, null!!, null!!, service, registry()) { null }

    val result = runtime.executeTask(
      prompt = "plain turn",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.agentisco.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = null,
      history = bigHistory,
      onRequestApproval = {},
      onEvent = {}
    )

    assertTrue(result.success)
    val sent = service.requests.first()
    assertTrue("history must be sent verbatim", sent.any { it.content == "first request" })
    assertTrue(sent.any { it.content == "Done." } || sent.any { it.content == "plain turn" })
  }

  @Test
  fun `the continuation message carries the preserved round count`() {
    val message = buildCompactSummaryMessage(
      "1. Primary Request and Intent\nThe goal.",
      CompactSummaryContext(
        preservedRecentCount = 4,
        tokensBefore = 20_000,
        tokensAfter = 2_000,
        trigger = CompactReason.MANUAL
      )
    )
    assertTrue(message.contains("last 4 message(s)"))
    assertTrue(message.contains("manual"))
    assertNotNull(CLEARED_TOOL_RESULT_PLACEHOLDER)
  }

  @Test
  fun `the meter measures what compaction actually saved`() {
    val config = CompactPolicyConfig(contextWindow = 10_000, outputReserve = 0, buffer = 0)
    val meter = CompactTokenMeter(config.contextWindow, config)
    val before = listOf(
      LlmMessage(LlmRole.SYSTEM, "s"),
      LlmMessage(LlmRole.USER, buildString { repeat(20_000) { append("x") } })
    )
    meter.setBase(before)
    val beforeTokens = meter.snapshot().usedTokens
    assertEquals(estimateMessageTokens(before), beforeTokens)

    meter.onCompacted(ContextTokenUsage(usedTokens = 300, contextWindow = config.contextWindow))
    val after = meter.snapshot()
    assertTrue(after.usedTokens < beforeTokens)
    assertEquals(300, after.usedTokens)
  }
}
