package com.agentisco

import com.agentisco.ui.ActionBlock
import com.agentisco.ui.AgentTurnItem
import com.agentisco.ui.ChatItem
import com.agentisco.ui.PlanStepState
import com.agentisco.ui.TurnBlock
import com.agentisco.ui.TurnStatus
import com.agentisco.ui.agentPlanFrom
import com.agentisco.ui.isPlanPublish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pinned plan card is derived from the transcript, so what it shows is only
 * as correct as this reading. These cover the guarantees the card depends on:
 * one plan at a time, the newest publish winning, real durations, and a
 * delegated agent's plan staying out of the mainline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentPlanTest {

  private fun plan(argsJson: String, at: Long) = ActionBlock(
    id = "plan-$at",
    name = "task_plan",
    argsJson = argsJson,
    running = false,
    success = true,
    summary = "",
    detail = "",
    exitCode = 0,
    callId = "call-$at",
    createdAt = at
  )

  private fun turn(blocks: List<TurnBlock>, status: TurnStatus = TurnStatus.RUNNING, id: String = "turn-1") =
    AgentTurnItem(id = id, status = status, statusMessage = "", blocks = blocks)

  private fun items(vararg turns: AgentTurnItem): List<ChatItem> = turns.toList()

  @Test
  fun `a conversation with no plan publishes no plan`() {
    val notAPlan = plan("""{"steps":["a"]}""", 1_000).copy(name = "write_file")
    assertNull(agentPlanFrom(items(turn(listOf(notAPlan)))))
    assertNull(agentPlanFrom(emptyList()))
  }

  @Test
  fun `a publish yields its title note and numbered states`() {
    val args = """{"title":"Needle Retrieval Benchmark","note":"Evaluate with a verified set","steps":[""" +
      """{"content":"Download corpus","status":"done"},""" +
      """{"content":"Build question set","status":"done","detail":"86 questions"},""" +
      """{"content":"Verify passages","status":"in_progress"},""" +
      """{"content":"Report findings"}]}"""
    val plan = agentPlanFrom(items(turn(listOf(plan(args, 1_000)))))!!

    assertEquals("Needle Retrieval Benchmark", plan.title)
    assertEquals("Evaluate with a verified set", plan.note)
    assertEquals(4, plan.total)
    assertEquals(2, plan.done)
    assertEquals(0.5f, plan.progress, 0.001f)
    assertEquals(listOf(1, 2, 3, 4), plan.steps.map { it.number })
    assertEquals(
      listOf(PlanStepState.DONE, PlanStepState.DONE, PlanStepState.RUNNING, PlanStepState.PENDING),
      plan.steps.map { it.state }
    )
    assertEquals("86 questions", plan.steps[1].detail)
    assertEquals("Verify passages", plan.current?.content)
    assertTrue(plan.isOpen)
  }

  @Test
  fun `bare string steps are a plan of pending steps`() {
    val plan = agentPlanFrom(items(turn(listOf(plan("""{"steps":["inspect","apply","verify"]}""", 1_000)))))!!
    assertEquals(3, plan.total)
    assertTrue(plan.steps.all { it.state == PlanStepState.PENDING })
    assertEquals(0, plan.done)
  }

  @Test
  fun `a step echoed in the tools own marker format keeps its state`() {
    val plan = agentPlanFrom(
      items(
        turn(
          listOf(
            plan(
              """{"steps":["[x] Download corpus","[>] Build question set","[ ] Verify passages",""" +
                """"[!] Report findings","plain step"]}""",
              1_000
            )
          )
        )
      )
    )!!

    assertEquals(
      listOf(
        PlanStepState.DONE, PlanStepState.RUNNING, PlanStepState.PENDING,
        PlanStepState.FAILED, PlanStepState.PENDING
      ),
      plan.steps.map { it.state }
    )
    assertEquals(
      listOf("Download corpus", "Build question set", "Verify passages", "Report findings", "plain step"),
      plan.steps.map { it.content }
    )
    assertEquals(1, plan.done)
  }

  @Test
  fun `the newest publish replaces the plan instead of adding one`() {
    val plan = agentPlanFrom(
      items(
        turn(
          listOf(
            plan("""{"steps":[{"content":"a","status":"done"},{"content":"b"},{"content":"c"}]}""", 1_000),
            plan("""{"title":"Revised","steps":[{"content":"a","status":"done"},{"content":"b","status":"done"}]}""", 9_000)
          )
        )
      )
    )!!

    assertEquals("Revised", plan.title)
    assertEquals(2, plan.total)
    assertEquals(2, plan.done)
    assertFalse(plan.isOpen)
  }

  @Test
  fun `a step is timed between the publish that opened it and the one that closed it`() {
    val plan = agentPlanFrom(
      items(
        turn(
          listOf(
            plan("""{"steps":[{"content":"a","status":"in_progress"},{"content":"b"}]}""", 1_000),
            plan("""{"steps":[{"content":"a","status":"done"},{"content":"b","status":"in_progress"}]}""", 13_200),
            plan("""{"steps":[{"content":"a","status":"done"},{"content":"b","status":"done"}]}""", 40_000)
          )
        )
      )
    )!!

    assertEquals(12.2, plan.steps[0].seconds, 0.001)
    assertEquals(26.8, plan.steps[1].seconds, 0.001)
  }

  @Test
  fun `an open step carries the moment it started so the card can count up`() {
    val plan = agentPlanFrom(
      items(
        turn(
          listOf(
            plan("""{"steps":[{"content":"a","status":"done"},{"content":"b","status":"in_progress"}]}""", 5_000)
          )
        )
      )
    )!!

    assertEquals(5_000L, plan.stepStartedAt)
    assertTrue(plan.running)
    assertEquals(0.0, plan.steps[1].seconds, 0.001)
  }

  @Test
  fun `a finished turn leaves the plan stating its result`() {
    val plan = agentPlanFrom(
      items(turn(listOf(plan("""{"steps":["a"]}""", 1_000)), status = TurnStatus.COMPLETED))
    )!!
    assertFalse(plan.running)
    assertTrue(plan.isOpen)
  }

  @Test
  fun `failed and skipped steps read as themselves and never count as done`() {
    val plan = agentPlanFrom(
      items(
        turn(
          listOf(
            plan(
              """{"steps":[{"content":"a","status":"completed"},{"content":"b","status":"failed"},""" +
                """{"content":"c","status":"skipped"},{"content":"d","status":"active"}]}""",
              1_000
            )
          )
        )
      )
    )!!

    assertEquals(
      listOf(PlanStepState.DONE, PlanStepState.FAILED, PlanStepState.SKIPPED, PlanStepState.RUNNING),
      plan.steps.map { it.state }
    )
    assertEquals(1, plan.done)
    assertEquals("a", plan.steps[0].content)
  }

  @Test
  fun `an unparsable publish is ignored rather than erasing the plan`() {
    val good = """{"steps":[{"content":"a","status":"done"},{"content":"b"}]}"""
    val plan = agentPlanFrom(
      items(
        turn(
          listOf(
            plan(good, 1_000),
            plan("this is not json", 2_000),
            plan("""{"steps":"a string, not an array"}""", 3_000),
            plan("""{"note":"no steps at all"}""", 4_000)
          )
        )
      )
    )!!

    assertEquals(2, plan.total)
    assertEquals(1, plan.done)
  }

  @Test
  fun `a delegated agent's plan stays nested and does not pin the card`() {
    val delegate = ActionBlock(
      id = "d1",
      name = "delegate",
      argsJson = """{"role":"explore","prompt":"map the config"}""",
      running = false,
      success = true,
      summary = "",
      detail = "",
      exitCode = 0,
      callId = "call-d",
      children = listOf(plan("""{"steps":[{"content":"a","status":"done"}]}""", 1_000))
    )

    assertNull(agentPlanFrom(items(turn(listOf(delegate)))))
  }

  @Test
  fun `only a mainline task plan call belongs to the pinned card`() {
    assertTrue(plan("""{"steps":["a"]}""", 1).isPlanPublish())
    assertFalse(plan("""{"steps":["a"]}""", 1).copy(name = "write_file").isPlanPublish())
    assertFalse(
      ActionBlock(
        id = "t", name = "text", argsJson = "", running = false, success = null,
        summary = "", detail = "", exitCode = null
      ).isPlanPublish()
    )
  }
}
