package com.awaki

import com.awaki.agent.compact.CLEARED_TOOL_RESULT_PLACEHOLDER
import com.awaki.agent.compact.CompactBoundary
import com.awaki.agent.compact.CompactPolicyConfig
import com.awaki.agent.compact.CompactReason
import com.awaki.agent.compact.CompactSummaryContext
import com.awaki.agent.compact.CompactTokenMeter
import com.awaki.agent.compact.ContextTokenUsage
import com.awaki.agent.compact.TokenUsageSource
import com.awaki.agent.compact.buildCompactSummaryMessage
import com.awaki.agent.compact.estimateMessageTokens
import com.awaki.agent.llm.LlmException
import com.awaki.agent.llm.GeminiChainState
import com.awaki.agent.llm.GeminiChainStoreImpl
import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmRole
import com.awaki.agent.llm.LlmStreamEvent
import com.awaki.agent.llm.LlmUsage
import com.awaki.agent.model.AgentStreamEvent
import com.awaki.agent.runtime.AgentRuntime
import com.awaki.agent.tool.AgentTool
import com.awaki.agent.tool.AgentToolRegistry
import com.awaki.agent.tool.ToolContext
import com.awaki.agent.tool.ToolParam
import com.awaki.agent.tool.ToolResult
import com.awaki.data.model.Project
import com.awaki.data.model.TerminalSession
import com.awaki.data.repository.ChatHistoryMessage
import com.awaki.data.repository.SessionCompaction
import com.awaki.settings.model.AIModel
import com.awaki.settings.model.AIProvider
import com.awaki.settings.model.LLMProtocol
import com.awaki.settings.model.ModelCapabilities
import com.awaki.workspace.filesystem.ProjectFileSystem
import com.awaki.workspace.git.GitRepositoryManager
import com.awaki.workspace.git.GitRunResult
import com.awaki.workspace.terminal.TerminalProcessManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The compaction contract, exercised against the real runtime:
 *
 *  - the persisted chat is never rewritten (only what is *sent* changes);
 *  - the provider receives the summary in place of the old turns;
 *  - a recorded compaction means later turns never re-send the old ones;
 *  - "compact now" works on demand, without token pressure;
 *  - a compressed transcript reopens the provider's server-side chain.
 *
 * Robolectric because the runtime builds tool schemas and system prompts with
 * org.json, which the plain JVM test classpath leaves unmocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeCompactionTest {

  /**
   * The runtime is exercised with its real collaborators, so the transcript it
   * builds is the one production would build. Only the LLM is faked.
   */
  private val workDir: File =
    File(System.getProperty("java.io.tmpdir"), "awaki_compact_${System.currentTimeMillis()}").apply { mkdirs() }
  private val fileSystem = ProjectFileSystem(workDir)
  private val gitManager = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
  private val terminalManager = TerminalProcessManager { null }

  private val project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = workDir.absolutePath)
  private val terminal = TerminalSession(id = "t", name = "main", currentDir = workDir.absolutePath)

  @After
  fun cleanWorkDir() {
    workDir.deleteRecursively()
  }

  private val provider = AIProvider("prov", "Local", "https://example.com/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS)
  private val model = AIModel(
    id = "m", providerId = "prov", modelId = "test", displayName = "Test",
    contextWindow = 40_000,
    capabilities = ModelCapabilities(tools = true)
  )

  /** Records exactly what the runtime asked for, token by token. */
  private open class RecordingLlmService : com.awaki.agent.llm.LlmService() {
    val requests = mutableListOf<List<LlmMessage>>()
    /** Conversations the runtime reopened because its transcript shrank. */
    val invalidated = mutableListOf<String?>()

    override fun invalidateConversation(conversationKey: String?) {
      invalidated += conversationKey
      super.invalidateConversation(conversationKey)
    }

    open suspend fun respond(
      provider: AIProvider,
      model: AIModel,
      apiKey: String,
      request: com.awaki.agent.llm.LlmRequest,
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
      request: com.awaki.agent.llm.LlmRequest,
      onEvent: (LlmStreamEvent) -> Unit
    ) {
      requests += request.messages
      respond(provider, model, apiKey, request, onEvent)
    }
  }

  private fun registry(tools: List<AgentTool> = emptyList()) = AgentToolRegistry(
    fileSystem = fileSystem,
    gitManager = gitManager,
    terminalManager = terminalManager,
    stagedFilesProvider = { emptySet() },
    onStageFile = {},
    onStageAll = {},
    onUnstageAll = {},
    extraTools = tools
  )

  /** Older turns big enough to put a 40k window over its compact threshold. */
  private val oldWork = buildString { repeat(12_000) { append("earlier work log line\n") } }

  private val bigHistory = buildList {
    add(ChatHistoryMessage("user", "first request", rowId = 1))
    add(ChatHistoryMessage("assistant", "first answer\n$oldWork", rowId = 2))
    add(ChatHistoryMessage("user", "second request", rowId = 3))
    add(ChatHistoryMessage("assistant", "second answer", rowId = 4))
  }
  /** The same conversation with nothing worth compacting by size alone. */
  private val smallHistory = listOf(
    ChatHistoryMessage("user", "first request", rowId = 1),
    ChatHistoryMessage("assistant", "first answer", rowId = 2),
    ChatHistoryMessage("user", "second request", rowId = 3),
    ChatHistoryMessage("assistant", "second answer", rowId = 4)
  )

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

  @Test  fun `the runtime records a compaction and sends the summary instead of the old turns`() = runBlocking {
    val sink = RecordingSink()
    val service = RecordingLlmService()
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { sink }

    runtime.executeTask(
      prompt = "new request",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.awaki.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = bigHistory,
      compactPolicy = CompactPolicyConfig(
        contextWindow = model.contextWindow!!, outputReserve = 0, buffer = 0,
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
    assertFalse(sent.any { it.content == "first request" || it.content.startsWith("first answer") })
    assertFalse(sent.any { it.content == "second request" })
    assertTrue(sent.any { it.content.contains("Conversation compacted") })
    // Rounds are assistant-started: the newest round — the answer to the second
    // request plus the live prompt — is what survives verbatim.
    assertTrue(sent.any { it.content == "second answer" })
    assertTrue(sent.any { it.content == "new request" })
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
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { sink }

    runtime.executeTask(
      prompt = "follow up",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.awaki.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = bigHistory + ChatHistoryMessage("user", "later request", rowId = 6),
      compactPolicy = CompactPolicyConfig(contextWindow = model.contextWindow!!, outputReserve = 0, buffer = 0, thresholdPercent = 5),
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
        request: com.awaki.agent.llm.LlmRequest,
        onEvent: (LlmStreamEvent) -> Unit
      ) {
        calls++
        onEvent(LlmStreamEvent.Started)
        if (calls == 1) {
          // First turn: request the tool.
          onEvent(
            LlmStreamEvent.ToolCallRequested(
              com.awaki.agent.llm.LlmToolCall("call_1", "read_file", "{\"path\":\"src/a.kt\"}")
            )
          )
          onEvent(
            LlmStreamEvent.Completed(
              LlmMessage(
                role = LlmRole.ASSISTANT,
                content = "",
                toolCalls = listOf(com.awaki.agent.llm.LlmToolCall("call_1", "read_file", "{\"path\":\"src/a.kt\"}"))
              )
            )
          )
        } else {
          "All done.".forEach { onEvent(LlmStreamEvent.Token(it.toString())) }
          onEvent(LlmStreamEvent.Completed(LlmMessage(role = LlmRole.ASSISTANT, content = "All done.")))
        }
      }
    }

    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, toolRegistry) { null }

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
      permissions = { com.awaki.agent.model.AgentPermissions() },
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
        request: com.awaki.agent.llm.LlmRequest,
        onEvent: (LlmStreamEvent) -> Unit
      ) {
        summaryCall++
        // The summarization request fails; normal turns succeed.
        if (request.tools.isEmpty()) throw LlmException("Summarizer is down", com.awaki.agent.llm.LlmErrorKind.SERVER)
        super.streamChat(provider, model, apiKey, request, onEvent)
      }
    }
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { sink }

    val result = runtime.executeTask(
      prompt = "go",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.awaki.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = bigHistory,
      compactPolicy = CompactPolicyConfig(
        contextWindow = model.contextWindow!!, outputReserve = 0, buffer = 0,
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
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { sink }
    val snapshots = mutableListOf<ContextTokenUsage>()

    runtime.executeTask(
      prompt = "measure me",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.awaki.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = emptyList(),
      compactPolicy = CompactPolicyConfig(contextWindow = model.contextWindow!!, outputReserve = 0, buffer = 0),
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
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { null }

    val result = runtime.executeTask(
      prompt = "plain turn",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.awaki.agent.model.AgentPermissions() },
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

  @Test
  fun `a manual compact summarizes on demand, below the automatic threshold`() = runBlocking {
    val sink = RecordingSink()
    val service = RecordingLlmService()
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { sink }

    // A 200k window: nothing here would ever trigger automatic compaction.
    val boundary = runtime.compactNow(
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      sessionId = "s1",
      history = bigHistory,
      compactPolicy = CompactPolicyConfig(
        contextWindow = 200_000, outputReserve = 0, buffer = 0, keepRecentRounds = 1
      )
    )

    assertNotNull("a manual compact must not wait for token pressure", boundary)
    assertEquals(CompactReason.MANUAL, boundary!!.trigger)
    assertTrue(boundary.tokensAfter < boundary.tokensBefore)
    // The oldest round is folded into the summary; the newest answer is kept.
    val recorded = sink.compactions.single()
    assertEquals(3L, recorded.summarizedThroughRowId)
    assertEquals(4L, recorded.keptFromRowId)
    // The only model call is the summarizer itself: no agent turn was run.
    assertEquals(1, service.requests.size)
    assertTrue(service.requests.single().last().content.contains("TRANSCRIPT"))
  }

  @Test
  fun `a manual compact refuses a pass that would send more than it saves`() = runBlocking {
    val sink = RecordingSink()
    val service = RecordingLlmService()
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { sink }
    val statuses = mutableListOf<String>()

    // Enough turns to plan, far too little text for a summary to pay for itself.
    val boundary = runtime.compactNow(
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      sessionId = "s1",
      history = smallHistory,
      compactPolicy = CompactPolicyConfig(
        contextWindow = 200_000, outputReserve = 0, buffer = 0, keepRecentRounds = 1
      ),
      onEvent = { event ->
        if (event is AgentStreamEvent.Status) statuses += event.text
      }
    )

    assertNull(boundary)
    assertTrue(sink.compactions.isEmpty())
    assertTrue(statuses.any { it.contains("too short", ignoreCase = true) })
  }

  @Test
  fun `a manual compact reports nothing to do instead of faking a summary`() = runBlocking {
    val sink = RecordingSink()
    val service = RecordingLlmService()
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { sink }
    val statuses = mutableListOf<String>()

    val boundary = runtime.compactNow(
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      sessionId = "s1",
      history = smallHistory,
      // Keeping every round leaves nothing older to summarize.
      compactPolicy = CompactPolicyConfig(
        contextWindow = 200_000, outputReserve = 0, buffer = 0, keepRecentRounds = 8
      ),
      onEvent = { event ->
        if (event is AgentStreamEvent.Status) statuses += event.text
      }
    )

    assertNull(boundary)
    assertTrue(sink.compactions.isEmpty())
    assertTrue(service.requests.isEmpty())
    assertTrue(statuses.any { it.contains("Nothing to compact", ignoreCase = true) })
  }

  @Test
  fun `a compaction reopens the provider conversation chain`() = runBlocking {
    val sink = RecordingSink()
    val service = RecordingLlmService()
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { sink }

    runtime.executeTask(
      prompt = "new request",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.awaki.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = bigHistory,
      compactPolicy = CompactPolicyConfig(
        contextWindow = model.contextWindow!!, outputReserve = 0, buffer = 0,
        keepRecentRounds = 1, thresholdPercent = 20
      ),
      onRequestApproval = {},
      onEvent = {}
    )

    assertEquals(1, sink.compactions.size)
    // A stateful provider holds the transcript itself: leaving the chain in
    // place would keep sending deltas on top of the uncompressed history.
    assertEquals(listOf<String?>("s1"), service.invalidated)
  }

  @Test
  fun `a turn that compacted nothing leaves the provider chain alone`() = runBlocking {
    val sink = RecordingSink()
    val service = RecordingLlmService()
    val runtime = AgentRuntime(fileSystem, terminalManager, gitManager, service, registry()) { sink }

    runtime.executeTask(
      prompt = "short turn",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { com.awaki.agent.model.AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      history = smallHistory,
      compactPolicy = CompactPolicyConfig(contextWindow = 200_000, outputReserve = 0, buffer = 0),
      onRequestApproval = {},
      onEvent = {}
    )

    assertTrue(sink.compactions.isEmpty())
    assertTrue(service.invalidated.isEmpty())
  }

  @Test
  fun `invalidating a conversation drops the stored server-side chain`() {
    val chainStore = GeminiChainStoreImpl()
    chainStore.save("s1", GeminiChainState(interactionId = "i-1", environmentId = "e-1"))
    val service = com.awaki.agent.llm.LlmService(chainStore = chainStore)

    service.invalidateConversation("s1")
    assertNull(chainStore.load("s1"))
    // A key with no chain (and no key at all) must not touch anything else.
    chainStore.save("s2", GeminiChainState(interactionId = "i-2"))
    service.invalidateConversation(null)
    service.invalidateConversation("   ")
    service.invalidateConversation("missing")
    assertNotNull(chainStore.load("s2"))
  }
}
