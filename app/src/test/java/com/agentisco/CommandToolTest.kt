package com.agentisco

import com.agentisco.agent.model.PermissionMode
import com.agentisco.agent.tool.BuildTool
import com.agentisco.agent.tool.InterruptTerminalTool
import com.agentisco.agent.tool.RunCommandTool
import com.agentisco.agent.tool.TerminalOutputTool
import com.agentisco.agent.tool.TestTool
import com.agentisco.agent.tool.WriteTerminalInputTool
import com.agentisco.workspace.terminal.TerminalProcessManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Command tools. What is tested here is the contract the agent depends on: a
 * command can never bypass the permission policy, a scripted tool is not a way
 * around it, and a missing Linux environment is reported instead of faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandToolTest {

  private var workspace: TestWorkspace? = null

  private fun ws(): TestWorkspace = newWorkspace("cmd").also { workspace = it }

  @After
  fun cleanUp() {
    workspace?.dispose()
    workspace = null
  }

  /** The manager with no Linux environment: every command must fail honestly. */
  private fun unavailableTerminal(): TerminalProcessManager = TerminalProcessManager { null }

  @Test
  fun `a command blocked by policy is refused before anything starts`() {
    val ws = ws()
    val log = ApprovalLog()
    val ctx = contextFor(ws, terminalCommands = PermissionMode.NEVER_ALLOW, log = log)
    val result = runBlocking {
      RunCommandTool(unavailableTerminal()).execute(args("""{"command": "ls"}"""), ctx)
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("permission policy"))
    assertEquals(-1, result.exitCode)
    assertTrue(log.requests.isEmpty())
  }

  @Test
  fun `a refused command is reported to the model as a refusal`() {
    val ws = ws()
    val log = ApprovalLog().apply { approve = false; terminated = false }
    val ctx = contextFor(ws, terminalCommands = PermissionMode.ALWAYS_ASK, log = log)
    val result = runBlocking {
      RunCommandTool(unavailableTerminal()).execute(args("""{"command": "npm publish"}"""), ctx)
    }
    assertFalse(result.success)
    assertEquals(1, log.requests.size)
    assertEquals("npm publish", log.requests.single().command)
    assertTrue(result.error!!.contains("denied permission"))
  }

  @Test
  fun `a stopped turn is never reported to the model as the user refusing`() {
    val ws = ws()
    val log = ApprovalLog().apply { approve = false; terminated = true }
    val ctx = contextFor(ws, terminalCommands = PermissionMode.ALWAYS_ASK, log = log)
    val result = runBlocking {
      RunCommandTool(unavailableTerminal()).execute(args("""{"command": "npm publish"}"""), ctx)
    }
    assertFalse(result.success)
    assertEquals(1, log.requests.size)
    assertEquals("npm publish", log.requests.single().command)
    // The command never ran, and the transcript must not claim the user refused.
    assertFalse(result.error!!.contains("denied permission"))
    assertTrue(result.error!!.contains("stopped"))
  }

  @Test
  fun `a destructive command always reaches the user as destructive`() {
    val ws = ws()
    val log = ApprovalLog().apply { approve = false; terminated = false }
    val ctx = contextFor(ws, terminalCommands = PermissionMode.ALLOW_ALL, log = log)
    val result = runBlocking {
      RunCommandTool(unavailableTerminal()).execute(args("""{"command": "rm -rf /"}"""), ctx)
    }
    assertFalse(result.success)
    assertTrue(log.requests.isNotEmpty())
    assertTrue(log.requests.single().isDestructive)
  }

  @Test
  fun `an unavailable linux environment is reported not swallowed`() {
    val ws = ws()
    val result = runBlocking {
      RunCommandTool(unavailableTerminal()).execute(args("""{"command": "echo hi"}"""), contextFor(ws))
    }
    assertFalse(result.success)
    assertTrue(result.output.contains("not ready"))
  }

  @Test
  fun `a background command says so when the environment cannot host it`() {
    val ws = ws()
    val result = runBlocking {
      RunCommandTool(unavailableTerminal()).execute(
        args("""{"command": "npm run dev", "run_in_background": true}"""),
        contextFor(ws)
      )
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("Linux environment"))
  }

  @Test
  fun `an empty command never reaches the shell`() {
    val ws = ws()
    val result = runBlocking {
      RunCommandTool(unavailableTerminal()).execute(args("""{"command": "   "}"""), contextFor(ws))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("non-empty"))
  }

  @Test
  fun `terminal_output without a runner reports that none exist`() {
    val result = runBlocking {
      TerminalOutputTool(unavailableTerminal()).execute(args("{}"), contextFor(ws()))
    }
    assertTrue(result.success)
    assertTrue(result.output.contains("No background commands"))
  }

  @Test
  fun `terminal_output with a wrong runner id tells the model how to find the right one`() {
    val result = runBlocking {
      TerminalOutputTool(unavailableTerminal()).execute(args("""{"runner_id": "ghost-1"}"""), contextFor(ws()))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("Unknown runner_id"))
    assertTrue(result.error!!.contains("terminal_output"))
  }

  @Test
  fun `interrupt_terminal reports what is actually still running`() {
    val result = runBlocking {
      InterruptTerminalTool(unavailableTerminal()).execute(args("{}"), contextFor(ws()))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("Nothing is running"))
  }

  @Test
  fun `terminal tools never touch the user's own terminal session`() {
    val tm = unavailableTerminal()
    val ctx = contextFor(ws())

    val stopped = runBlocking {
      InterruptTerminalTool(tm).execute(args("""{"runner_id": "term-1"}"""), ctx)
    }
    assertFalse(stopped.success)
    assertTrue(stopped.error!!.contains("not a command the agent started"))

    val typed = runBlocking {
      WriteTerminalInputTool(tm).execute(args("""{"input": "rm -rf /", "runner_id": "term-1"}"""), ctx)
    }
    assertFalse(typed.success)
    assertTrue(typed.error!!.contains("not a command the agent started"))
  }

  @Test
  fun `input is only sent to a command that is really waiting for it`() {
    val tm = unavailableTerminal()
    val none = runBlocking {
      WriteTerminalInputTool(tm).execute(args("""{"input": "y"}"""), contextFor(ws()))
    }
    assertFalse(none.success)
    assertTrue(none.error!!.contains("No command is running"))
    assertTrue(none.error!!.contains("run_command"))

    val finished = runBlocking {
      WriteTerminalInputTool(tm).execute(
        args("""{"input": "y", "runner_id": "term-1-run-a1b2c3d4"}"""),
        contextFor(ws())
      )
    }
    assertFalse(finished.success)
    assertTrue(finished.error!!.contains("term-1-run-a1b2c3d4 has already finished"))
    assertTrue(finished.error!!.contains("terminal_output"))
  }

  @Test
  fun `an empty input is a valid answer because it presses Enter`() {
    val tool = WriteTerminalInputTool(unavailableTerminal())
    val blank = args("""{"input": ""}""")
    assertEquals("", tool.parseAndValidate(blank.toString()).getString("input"))
    assertThrows(Exception::class.java) { tool.parseAndValidate("{}") }
  }

  @Test
  fun `writing a line to a command leaves its input open`() {
    var closed = false
    val sink = object : java.io.OutputStream() {
      val bytes = java.io.ByteArrayOutputStream()
      override fun write(b: Int) {
        bytes.write(b)
      }

      override fun close() {
        closed = true
      }
    }
    assertTrue(TerminalProcessManager { null }.writeLine(sink, "yes"))
    assertEquals("yes\n", String(sink.bytes.toByteArray()))
    assertFalse("closing stdin would end the command instead of answering it", closed)
  }

  @Test
  fun `build and test refuse to invent a script for a project that has none`() {
    val ctx = contextFor(ws())
    val build = runBlocking { BuildTool(unavailableTerminal()).execute(args("{}"), ctx) }
    assertFalse(build.success)
    assertTrue(build.error!!.contains("No package.json"))
    assertTrue(build.error!!.contains("run_command"))

    val test = runBlocking { TestTool(unavailableTerminal()).execute(args("{}"), ctx) }
    assertFalse(test.success)
    assertTrue(test.error!!.contains("No package.json"))
  }

  @Test
  fun `build inherits the run_command permission policy instead of bypassing it`() {
    val ws = ws()
    File(ws.root, "package.json").writeText("""{"scripts": {"build": "tsc"}}""")
    val log = ApprovalLog().apply { approve = false; terminated = false }
    val ctx = contextFor(ws, terminalCommands = PermissionMode.ALWAYS_ASK, log = log)
    val result = runBlocking { BuildTool(unavailableTerminal()).execute(args("""{"args": "--verbose"}"""), ctx) }
    assertFalse(result.success)
    val command = log.requests.single().command
    assertTrue(command, command.contains("npm run build"))
    assertTrue(command, command.contains("--verbose"))
  }
}
