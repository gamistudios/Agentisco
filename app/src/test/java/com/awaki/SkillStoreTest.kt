package com.awaki

import androidx.test.core.app.ApplicationProvider
import com.awaki.agent.skill.AgentSkill
import com.awaki.agent.skill.SkillScope
import com.awaki.agent.skill.SkillStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Skills as files on disk.
 *
 * There is no database behind a skill, so everything that matters is decided here:
 * what counts as a skill, how its name is derived, which one wins when two folders
 * say the same thing, and that what the agent is told is the file's own text rather
 * than a paraphrase of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillStoreTest {

  private val ws = newWorkspace("skills")

  private fun skillFile(rel: String, text: String): File =
    ws.write("${SkillStore.PROJECT_DIR}/$rel", text)

  private fun discover() = SkillStore(null).discover(File(ws.project.path))

  @Test
  fun `a folder with a SKILL_md is one skill`() {
    skillFile(
      "testing/SKILL.md",
      """
      ---
      name: Writing tests
      description: Use when adding a test to this repo, to match how the suite is written
      ---

      # Writing tests

      One test per behaviour. Name it as a sentence.
      """.trimIndent()
    )

    val skill = discover().single()
    assertEquals("writing-tests", skill.name)
    assertEquals("Writing tests", skill.displayName)
    assertEquals("Use when adding a test to this repo, to match how the suite is written", skill.description)
    assertEquals(SkillScope.PROJECT, skill.scope)
    assertEquals(".awaki/skills/testing/SKILL.md", skill.path)
    // The agent reads the body, not a summary of it.
    assertTrue(skill.instructions, skill.instructions.contains("One test per behaviour."))
    ws.dispose()
  }

  /** The folder name is the address the agent types, so it must survive a rename. */
  @Test
  fun `an unnamed skill takes the folder it lives in`() {
    skillFile("proot-notes/SKILL.md", "Some notes about the terminal layer.\n")
    val skill = discover().single()
    assertEquals("proot-notes", skill.name)
    assertEquals("Some notes about the terminal layer.", skill.description)
    ws.dispose()
  }

  /** The folder is the address; a heading only makes it readable. */
  @Test
  fun `a plain markdown skill still reads - heading for a label, first line for a use`() {
    skillFile("release/SKILL.md", "# Cutting a release\n\nTag it, then build the signed APK.\n")
    val skill = discover().single()
    assertEquals("release", skill.name)
    assertEquals("Cutting a release", skill.displayName)
    assertEquals("Tag it, then build the signed APK.", skill.description)
    ws.dispose()
  }

  @Test
  fun `a folder that is not a skill is left out`() {
    ws.write("${SkillStore.PROJECT_DIR}/README.md", "not a skill folder")
    skillFile("empty/SKILL.md", "   \n")
    skillFile("front-only/SKILL.md", "---\nname: Nothing\n---\n")
    assertEquals(emptyList<AgentSkill>(), discover().map { it.name })
    ws.dispose()
  }

  @Test
  fun `line endings and unknown frontmatter do not break a hand-written file`() {
    skillFile(
      "crlf/SKILL.md",
      "---\r\nname: Windows\r\ndescription: A skill written in Notepad\r\nlicense: MIT\r\n---\r\n\r\nStep one.\r\n"
    )
    val skill = discover().single()
    assertEquals("windows", skill.name)
    assertEquals("A skill written in Notepad", skill.description)
    assertTrue(skill.instructions, skill.instructions.contains("Step one."))
    ws.dispose()
  }

  @Test
  fun `saving and deleting go through the same file the agent reads`() {
    val store = SkillStore(null)
    val root = File(ws.project.path)
    val written = store.save(
      root,
      AgentSkill(
        // The editor chooses the address; renaming the label must not mint a
        // second skill, so the folder is only ever what the caller names it.
        name = "migration-review",
        displayName = "Review my migration",
        description = "Use before merging a schema change",
        instructions = "Check the Room version bump and the fallback strategy.",
        scope = SkillScope.PROJECT,
        path = ""
      )
    ).single()

    assertEquals("migration-review", written.name)
    assertEquals(".awaki/skills/migration-review/SKILL.md", written.path)
    assertTrue(File(root, written.path).isFile)
    // Re-reading the file it just wrote is the round trip that matters.
    val edited = store.save(
      root,
      written.copy(displayName = "Review a Room migration")
    ).single()
    assertEquals("migration-review", edited.name)
    assertEquals("Review a Room migration", edited.displayName)
    assertEquals("Use before merging a schema change", edited.description)

    assertEquals(emptyList<AgentSkill>(), store.delete(root, edited.name, SkillScope.PROJECT))
    assertFalse(File(root, written.path).exists())
    ws.dispose()
  }

  /**
   * The repo's own skill beats an installed one of the same name: whoever works in
   * this project wrote the more specific instructions.
   */
  @Test
  fun `a project skill overrides the installed one of the same name and both scopes coexist`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val installed = context.getDir("awaki", android.content.Context.MODE_PRIVATE).resolve("skills")
    File(installed, "testing").apply { mkdirs() }.resolve("SKILL.md")
      .writeText("---\ndescription: the general way\n---\nGeneric advice.\n")
    File(installed, "release").apply { mkdirs() }.resolve("SKILL.md")
      .writeText("---\ndescription: how this app ships\n---\nBuild, sign, upload.\n")
    skillFile("testing/SKILL.md", "---\ndescription: project's own way\n---\nDo it this repo's way.\n")

    val skills = SkillStore(context).discover(File(ws.project.path)).associateBy { it.name }
    assertEquals("project's own way", skills["testing"]!!.description)
    assertEquals(SkillScope.PROJECT, skills["testing"]!!.scope)
    assertEquals("how this app ships", skills["release"]!!.description)
    assertEquals(SkillScope.APP, skills["release"]!!.scope)
    assertEquals(".awaki/skills/testing/SKILL.md", skills["testing"]!!.path)
    assertEquals("skills/release/SKILL.md", skills["release"]!!.path)
    ws.dispose()
  }

  @Test
  fun `an id an agent can type is what a messy name becomes`() {
    assertEquals("git-lfs", SkillStore.slug("  Git  LFS!!  "))
    assertEquals("a-b", SkillStore.slug("a/../b"))
    assertEquals("", SkillStore.slug("///"))
    ws.dispose()
  }
}
