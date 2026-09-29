package com.agentisco

import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.PermissionMode
import com.agentisco.agent.tool.ToolContext
import com.agentisco.agent.tool.WriteGate
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

/**
 * Shared scaffolding for the workspace tool tests: every tool runs against a real
 * temporary folder, because these tests exist precisely to prove what lands on
 * disk — line endings, byte-exact moves, and what a failed batch leaves behind.
 */

internal class TestWorkspace(val root: File) {
  val project = Project(
    id = "ws",
    name = "ToolTest",
    branch = "main",
    lastActivity = "now",
    path = root.absolutePath
  )

  init {
    root.mkdirs()
  }

  fun write(relPath: String, content: String): File =
    File(root, relPath).apply {
      parentFile?.mkdirs()
      writeText(content)
    }

  fun read(relPath: String): String = File(root, relPath).readText()

  fun bytes(relPath: String): ByteArray = File(root, relPath).readBytes()

  fun exists(relPath: String): Boolean = File(root, relPath).exists()

  /** A path sitting next to the workspace root — outside every tool's reach. */
  fun outside(name: String): File = File(root.absoluteFile.parentFile, name)

  fun dispose() {
    root.deleteRecursively()
  }
}

internal fun newWorkspace(tag: String): TestWorkspace =
  TestWorkspace(File(System.getProperty("java.io.tmpdir"), "scoos_tools_${tag}_${System.nanoTime()}"))

/** Everything the tools asked the user to approve, in order. */
internal class ApprovalLog {
  val requests = mutableListOf<PendingApproval>()
  var approve = true
  var answer: String? = null
  /** True when the turn was stopped before the user decided. */
  var terminated = false
  /** What the user typed alongside the decision — usually why they refused. */
  var rationale: String? = null

  fun last(): PendingApproval = requests.last()
}

internal fun contextFor(
  workspace: TestWorkspace,
  fileEditing: PermissionMode = PermissionMode.ALLOW_ALL,
  terminalCommands: PermissionMode = PermissionMode.ALLOW_ALL,
  deleteFiles: Boolean = true,
  log: ApprovalLog = ApprovalLog(),
  toolCallId: String = ""
): ToolContext = ToolContext(
  project = workspace.project,
  permissions = {
    AgentPermissions(
      fileEditing = fileEditing,
      terminalCommands = terminalCommands,
      deleteFiles = deleteFiles
    )
  },
  terminalSession = TerminalSession(id = "term-1", name = "main", currentDir = workspace.root.absolutePath),
  // A "no" from a terminated turn is the harness ending the request, not the
  // user refusing it — the same distinction the live runtime makes. The
  // rationale rides along so a test's refusal can say why.
  onApprovalTerminated = { _ -> log.terminated },
  onApprovalRationale = { _ -> log.rationale },
  requestApproval = { approval ->
    log.requests.add(approval)
    !log.terminated && log.approve
  },
  activeSessions = { emptyList() },
  toolCallId = toolCallId,
  askUser = { approval ->
    log.requests.add(approval)
    if (log.terminated) null else log.answer
  }
)

/** The write gate the registry installs, with the approval prompt stripped out. */
internal val allowWrite: WriteGate = { _, _ -> null }

internal fun args(json: String): JSONObject = JSONObject(json)

/**
 * An HTTP client that answers every request with a fixed response, so the web
 * tools are tested on real markup without a network. [capture] sees the request
 * the tool actually built.
 */
internal fun stubHttp(
  status: Int,
  contentType: String,
  body: String,
  capture: (okhttp3.Request) -> Unit = {}
): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
  capture(chain.request())
  okhttp3.Response.Builder()
    .request(chain.request())
    .protocol(okhttp3.Protocol.HTTP_1_1)
    .code(status)
    .message(if (status in 200..299) "OK" else "Error")
    .header("Content-Type", contentType)
    .body(body.toResponseBody(contentType.toMediaType()))
    .build()
}.build()
