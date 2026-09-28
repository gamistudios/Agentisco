package com.agentisco

import com.agentisco.agent.tool.AskUserTool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ask_user`: the agent hands a decision back to the user instead of guessing.
 * The answer must reach the model verbatim, and a dismissed question must be
 * distinguishable from an answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskUserToolTest {

  private var workspace: TestWorkspace? = null

  private fun ws(): TestWorkspace = newWorkspace("ask").also { workspace = it }

  @After
  fun cleanUp() {
    workspace?.dispose()
    workspace = null
  }

  @Test
  fun `a chosen option is reported as the user's answer`() {
    val log = ApprovalLog().apply { answer = "project folder" }
    val result = runBlocking {
      AskUserTool().execute(
        args("""{"question": "Cache the model in the project folder or in app storage?", "options": ["project folder", "app storage"]}"""),
        contextFor(ws(), log = log)
      )
    }
    assertTrue(result.success)
    assertEquals("User answered: project folder", result.output.trim())
    assertEquals("true", result.metadata["answered"])

    val request = log.requests.single()
    assertTrue(request.isQuestion)
    assertEquals("The agent has a question", request.title)
    assertEquals(listOf("project folder", "app storage"), request.options)
    assertTrue(request.allowFreeText)
  }

  @Test
  fun `a dismissed question is not an answer and says not to ask again`() {
    val log = ApprovalLog().apply { answer = null; terminated = false }
    val result = runBlocking {
      AskUserTool().execute(
        args("""{"question": "Which framework?", "options": ["Compose", "Views"]}"""),
        contextFor(ws(), log = log)
      )
    }
    assertTrue(result.success)
    assertTrue(result.output.contains("did not answer"))
    assertTrue(result.output.contains("do not ask the same question again"))
    assertEquals("false", result.metadata["answered"])
  }

  @Test
  fun `a stopped turn is not an answer, just as a dismissal is not`() {
    val log = ApprovalLog().apply { answer = null; terminated = true }
    val result = runBlocking {
      AskUserTool().execute(
        args("""{"question": "Which framework?", "options": ["Compose", "Views"]}"""),
        contextFor(ws(), log = log)
      )
    }
    assertTrue(result.success)
    assertEquals("false", result.metadata["answered"])
    assertFalse(result.output.contains("User answered"))
  }

  @Test
  fun `a free-text only question is allowed but options without free text are not`() {
    val ws = ws()
    val ctx = contextFor(ws)
    val freeTextOnly = runBlocking {
      AskUserTool().execute(args("""{"question": "What should the folder be called?"}"""), ctx)
    }
    assertTrue(freeTextOnly.success)

    val noWayToAnswer = runBlocking {
      AskUserTool().execute(
        args("""{"question": "Pick one", "allow_free_text": false}"""),
        contextFor(ws, log = ApprovalLog().apply { answer = "ignored"; terminated = false })
      )
    }
    assertFalse(noWayToAnswer.success)
    assertTrue(noWayToAnswer.error!!.contains("no way to reply"))
  }

  @Test
  fun `two options are a valid question, not a validation error`() {
    val log = ApprovalLog().apply { answer = "Compose" }
    val result = runBlocking {
      AskUserTool().execute(
        args("""{"question": "Which framework?", "options": ["Compose", "Views"]}"""),
        contextFor(ws(), log = log)
      )
    }
    assertTrue(result.success)
    assertEquals("User answered: Compose", result.output.trim())
    assertEquals(listOf("Compose", "Views"), log.requests.single().options)
  }

  @Test
  fun `three options are a valid question too`() {
    val log = ApprovalLog().apply { answer = "b" }
    val result = runBlocking {
      AskUserTool().execute(
        args("""{"question": "Which one?", "options": ["a", "b", "c"]}"""),
        contextFor(ws(), log = log)
      )
    }
    assertTrue(result.success)
    assertEquals(3, log.requests.single().options.size)
  }

  @Test
  fun `a single option is not a question`() {
    val log = ApprovalLog()
    val result = runBlocking {
      AskUserTool().execute(
        args("""{"question": "Which framework?", "options": ["Compose"]}"""),
        contextFor(ws(), log = log)
      )
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("2 or more"))
    // A non-question must never reach the user.
    assertTrue(log.requests.isEmpty())
  }

  @Test
  fun `too many options are rejected so the dialog stays tappable`() {
    val result = runBlocking {
      AskUserTool().execute(
        args("""{"question": "Pick one", "options": ["a", "b", "c", "d", "e"]}"""),
        contextFor(ws())
      )
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("at most 4"))
  }

  @Test
  fun `a blank question is refused before bothering the user`() {
    val log = ApprovalLog()
    val result = runBlocking {
      AskUserTool().execute(args("""{"question": "   "}"""), contextFor(ws(), log = log))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("non-empty"))
    assertTrue(log.requests.isEmpty())
  }
}
