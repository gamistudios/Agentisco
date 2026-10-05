package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.local.LocalModelPaths
import com.awaki.workspace.terminal.NativeBinaries
import com.awaki.workspace.terminal.ProotArgsBuilder
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The claim the whole Python design rests on: a model the app downloaded is readable from
 * inside the Linux environment at the path the virtualenv was built in.
 *
 * Without that bind, the guest would be serving a directory the app never writes to, and the
 * failure would surface as a model that "is not installed" on a phone holding its bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalModelGuestBindTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun builder(rootfs: File, binds: List<Pair<String, String>>): ProotArgsBuilder {
    // proot is resolved out of the native library directory, which Robolectric leaves unset:
    // a test that wants a real command line has to give the resolver somewhere to look.
    val nativeDir = File(context.filesDir, "nativedir").apply { mkdirs() }
    listOf("libproot.so", "libproot-loader.so", "libtalloc.so").forEach { File(nativeDir, it).writeText("x") }
    context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
    return ProotArgsBuilder(NativeBinaries(context), rootfs, binds)
  }

  private fun bindsOf(argv: List<String>): Set<String> =
    argv.filterIndexed { index, _ -> argv.getOrNull(index - 1) == "-b" }.toSet()

  @Test
  fun `every guest command sees the model directory at the home path the venv lives in`() {
    val models = File(context.filesDir, LocalModelPaths.HOST_DIR_NAME).apply { mkdirs() }
    val (argv, _) = builder(File(context.filesDir, "linux-rootfs"), modelBinds(models))
      .buildCommand(listOf("/bin/true"))

    assertTrue(argv.toString(), bindsOf(argv).contains("${models.absolutePath}:${LocalModelPaths.GUEST_DIR}"))
  }

  /** proot fails the whole command over a bind whose source is missing, so it is dropped. */
  @Test
  fun `a model directory that does not exist yet is simply not bound`() {
    val missing = File(context.filesDir, "not-there-yet")
    val (argv, _) = builder(File(context.filesDir, "linux-rootfs"), modelBinds(missing))
      .buildCommand(listOf("/bin/true"))

    assertFalse(argv.toString(), bindsOf(argv).any { it.endsWith(":${LocalModelPaths.GUEST_DIR}") })
  }

  /** The workspace bind is per command; the model bind is for every one, and both may apply. */
  @Test
  fun `the model bind survives alongside the workspace a command runs in`() {
    val models = File(context.filesDir, LocalModelPaths.HOST_DIR_NAME).apply { mkdirs() }
    val project = File(context.filesDir, "project").apply { mkdirs() }

    val (argv, _) = builder(File(context.filesDir, "linux-rootfs"), modelBinds(models))
      .buildCommand(listOf("/bin/true"), workingDir = ProotArgsBuilder.WORKSPACE_GUEST_PATH, bindHostDir = project)

    val binds = bindsOf(argv)
    assertTrue(binds.toString(), binds.contains("${models.absolutePath}:${LocalModelPaths.GUEST_DIR}"))
    assertTrue(binds.toString(), binds.contains("${project.absolutePath}:${ProotArgsBuilder.WORKSPACE_GUEST_PATH}"))
  }

  private fun modelBinds(models: File): List<Pair<String, String>> =
    listOf(models.absolutePath to LocalModelPaths.GUEST_DIR)
}
