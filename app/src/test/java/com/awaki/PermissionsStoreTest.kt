package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.agent.model.AgentPermissions
import com.awaki.agent.model.PermissionMode
import com.awaki.agent.model.UNLIMITED_ITERATIONS
import com.awaki.data.local.PermissionsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The whole of Settings → AI & Agent used to live in one `MutableStateFlow`, so every
 * row on that page — which tools the agent may use, how often it asks, how long it may
 * loop — went back to its shipped value on the next launch. What has to hold here: a
 * written permission is what a new reader of the file gets, *every* field is written
 * (a seventeenth that quietly never persists is the same bug wearing a different hat),
 * and a file this build does not fully understand still yields usable permissions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionsStoreTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private val file: File
    get() = File(context.getDir("awaki", Context.MODE_PRIVATE), "permissions.json")

  @Before
  fun clearPersistedPermissions() {
    file.delete()
  }

  @Test
  fun `a fresh store starts on the shipped permissions`() {
    val permissions = PermissionsStore(context).get()

    assertEquals(AgentPermissions(), permissions)
    assertEquals(PermissionMode.ALWAYS_ASK, permissions.fileEditing)
    assertFalse("deleting files is not on by default", permissions.deleteFiles)
    assertFalse("nor is pushing", permissions.gitPush)
    assertFalse(permissions.planMode)
    assertEquals(UNLIMITED_ITERATIONS, permissions.maxToolIterations)
  }

  @Test
  fun `every permission survives the process it was set in`() {
    val changed = AgentPermissions(
      fileEditing = PermissionMode.AUTO_APPROVE_PROJECT,
      terminalCommands = PermissionMode.NEVER_ALLOW,
      networkAccess = false,
      maxToolIterations = 25,
      readFiles = false,
      createFiles = false,
      modifyFiles = false,
      deleteFiles = true,
      runCommands = false,
      installPackages = true,
      networkCommands = true,
      gitStatus = false,
      gitDiff = false,
      gitCommit = false,
      gitPush = true,
      alwaysAskDangerous = false,
      planMode = true
    )
    PermissionsStore(context).update { changed }

    val reread = PermissionsStore(context).get()
    assertEquals(changed, reread)
    assertEquals(
      "one key per permission, so a new one cannot be left out of the write",
      setOf(
        "fileEditing", "terminalCommands", "networkAccess", "maxToolIterations",
        "readFiles", "createFiles", "modifyFiles", "deleteFiles", "runCommands",
        "installPackages", "networkCommands", "gitStatus", "gitDiff", "gitCommit",
        "gitPush", "alwaysAskDangerous", "planMode"
      ),
      JSONObject(file.readText()).keys().asSequence().toSet()
    )
  }

  @Test
  fun `one switch changed on the row keeps every other answer`() {
    PermissionsStore(context).update { it.copy(deleteFiles = true, gitPush = true) }
    val second = PermissionsStore(context)
    second.update { it.copy(maxToolIterations = 8) }

    val reread = PermissionsStore(context).get()
    assertTrue("the first choice must still be there", reread.deleteFiles)
    assertTrue(reread.gitPush)
    assertEquals(8, reread.maxToolIterations)
    assertEquals(PermissionMode.ALWAYS_ASK, reread.fileEditing)
  }

  @Test
  fun `a file missing a key keeps that default instead of a false`() {
    file.writeText("""{"readFiles":false,"terminalCommands":"ALLOW_ALL"}""")

    val permissions = PermissionsStore(context).get()
    assertFalse(permissions.readFiles)
    assertEquals(PermissionMode.ALLOW_ALL, permissions.terminalCommands)
    assertTrue("gitStatus is on unless the file says otherwise", permissions.gitStatus)
    assertTrue("network access too", permissions.networkAccess)
    assertEquals(UNLIMITED_ITERATIONS, permissions.maxToolIterations)
  }

  @Test
  fun `values this build does not recognise fall back instead of failing the page`() {
    file.writeText(
      """{"fileEditing":"AUTO_APPROVE_EVERYTHING","maxToolIterations":0,"installPackages":"maybe"}"""
    )

    val permissions = PermissionsStore(context).get()

    assertEquals(PermissionMode.ALWAYS_ASK, permissions.fileEditing)
    assertEquals("a budget of zero is not a budget", UNLIMITED_ITERATIONS, permissions.maxToolIterations)
    assertFalse("a value that is not a boolean does not opt the agent in", permissions.installPackages)
  }

  @Test
  fun `a file the app cannot read costs the user nothing but that one page`() {
    file.writeText("this was never json {{{")

    assertEquals(AgentPermissions(), PermissionsStore(context).get())

    PermissionsStore(context).update { it.copy(planMode = true) }
    assertTrue("and the next write repairs the file", PermissionsStore(context).get().planMode)
  }

  @Test
  fun `with no application the store still remembers for this process`() {
    val store = PermissionsStore(null)

    store.update { it.copy(terminalCommands = PermissionMode.NEVER_ALLOW) }

    assertEquals(PermissionMode.NEVER_ALLOW, store.get().terminalCommands)
  }
}
