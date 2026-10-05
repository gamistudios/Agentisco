package com.awaki

import com.awaki.agent.skill.AgentSkill
import com.awaki.agent.skill.SkillScope
import com.awaki.agent.skill.SkillStore
import com.awaki.agent.tool.AgentToolRegistry
import com.awaki.agent.tool.PlanMode
import com.awaki.agent.tool.UseSkillTool
import com.awaki.workspace.filesystem.ProjectFileSystem
import com.awaki.workspace.git.GitRepositoryManager
import com.awaki.workspace.git.GitRunResult
import com.awaki.workspace.terminal.TerminalProcessManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one tool an agent uses to pull in a skill.
 *
 * What is proven here is that a skill reaches the model as written: the index it
 * chooses from, and then the file's own instructions. Also that a wrong name costs
 * the model one turn to correct rather than a dead end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentSkillTest {

  private val ws = newWorkspace("skilltool")

  @After
  fun cleanUp() {
    ws.dispose()
  }

  private fun writeSkill(folder: String, text: String) =
    ws.write("${SkillStore.PROJECT_DIR}/$folder/SKILL.md", text)

  private val tool = UseSkillTool(SkillStore(null))

  private fun call(vararg pairs: Pair<String, String>) = runBlocking {
    val args = JSONObject()
    pairs.forEach { (k, v) -> args.put(k, v) }
    tool.execute(tool.parseAndValidate(args.toString()), contextFor(ws))
  }

  @Test
  fun `a skill loads as its own text, not a summary of it`() {
    writeSkill(
      "testing",
      """
      ---
      name: testing
      description: Use when adding a test to this repo
      ---

      # Writing tests

      Every behaviour gets one test. Name it as a sentence.
      Never mock the database.
      """.trimIndent()
    )

    val result = call("name" to "testing")
    assertTrue(result.error ?: result.output, result.success)
    assertTrue(result.output, result.output.contains("Use when adding a test to this repo"))
    assertTrue(result.output, result.output.contains("Never mock the database."))
    // The agent can be told where a rule came from.
    assertTrue(result.output, result.output.contains(".awaki/skills/testing/SKILL.md"))
    assertEquals("testing", result.metadata["skill"])
  }

  @Test
  fun `no name lists what exists instead of failing`() {
    writeSkill("testing", "---\nname: testing\ndescription: how we test\n---\nBody.\n")
    writeSkill("release", "---\nname: release\ndescription: how we ship\n---\nBody.\n")

    val output = call().output
    assertTrue(output, output.contains("- testing: how we test"))
    assertTrue(output, output.contains("- release: how we ship"))
  }

  /** A model that guesses a name deserves the list, not a dead end. */
  @Test
  fun `a name that matches nothing says which names do`() {
    writeSkill("testing", "---\nname: testing\ndescription: how we test\n---\nBody.\n")
    val result = call("name" to "unit-tests")
    assertTrue(result.error ?: "succeeded", !result.success)
    assertTrue(result.error ?: "", result.error!!.contains("testing"))
  }

  @Test
  fun `a loosely typed name still finds the skill, and a label counts`() {
    writeSkill("testing", "---\nname: testing\ndescription: how we test\n---\n# Writing Tests\nBody.\n")

    val skills = SkillStore(null).discover(File(ws.project.path))
    val match: (String) -> AgentSkill? = { wanted -> with(UseSkillTool) { skills.match(wanted) } }
    assertEquals("testing", match("Writing Tests")?.name)
    assertEquals("testing", match("TESTING")?.name)
    // A prefix is only a match while it is unambiguous.
    assertEquals("testing", match("test")?.name)
    assertEquals(null, match("zzz"))
  }

  @Test
  fun `a project with no skills says where one goes`() {
    val output = call().output
    assertTrue(output, output.contains(".awaki/skills"))
  }

  /** Skills are paper: reading one changes nothing, so planning may read one. */
  @Test
  fun `use_skill is offered to every role including research`() {
    assertTrue(PlanMode.delegatedToolNames.contains("use_skill"))

    val fileSystem = ProjectFileSystem(File(ws.root, "fsbase"))
    val registry = AgentToolRegistry(
      fileSystem = fileSystem,
      gitManager = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") },
      terminalManager = TerminalProcessManager { null },
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {},
      skillStore = SkillStore(null)
    )
    assertTrue(registry.tools.any { it.name == "use_skill" })
    // A research run is handed it too, not only the working set.
    assertTrue(registry.forDelegation(com.awaki.agent.model.AgentRoles.EXPLORE).tools.any { it.name == "use_skill" })
  }

  @Test
  fun `the index is one line per skill and names the tool that loads it`() {
    val index = UseSkillTool.index(
      listOf(
        AgentSkill("testing", "Testing", "how we write tests", "x", SkillScope.PROJECT, "p"),
        AgentSkill("release", "Release", "how we ship", "y", SkillScope.APP, "q")
      )
    )
    assertTrue(index, index.contains("- testing: how we write tests"))
    assertTrue(index, index.contains("- release: how we ship"))
    assertTrue(index, index.contains("use_skill"))
    assertEquals(3, index.lines().count { it.isNotBlank() })
  }
}
