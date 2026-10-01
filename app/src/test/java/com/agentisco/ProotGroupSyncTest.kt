package com.agentisco

import com.agentisco.workspace.terminal.FIRST_BOOT_SETUP
import com.agentisco.workspace.terminal.GROUP_SYNC
import com.agentisco.workspace.terminal.guestStartupScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android hands the PTY process supplementary GIDs the Debian rootfs knows
 * nothing about, so `groups` printed "cannot find name for group ID" on devices
 * whose numbers nobody could have predicted. That is exactly why the snippet has
 * to discover them at runtime: these tests police the shape of the loop, and
 * above all that no id is baked into it.
 */
class ProotGroupSyncTest {

  @Test
  fun `the inherited groups are read from the process, never listed by number`() {
    assertTrue(GROUP_SYNC.contains("id -G"))
    // Device GIDs are 1079, 3003, 9997, 20450... nothing that long may appear;
    // the small digits are the shell's own redirection descriptors.
    assertFalse(
      "a device-specific GID was hardcoded: $GROUP_SYNC",
      Regex("\\d{3,}").containsMatchIn(GROUP_SYNC)
    )
    assertTrue(GROUP_SYNC.contains("\"grp\$gid\""))
  }

  @Test
  fun `only an unresolvable group gets created, and existing ones are untouched`() {
    assertTrue(GROUP_SYNC.contains("if ! getent group \"\$gid\""))
    assertTrue(GROUP_SYNC.contains("groupadd -g \"\$gid\" \"grp\$gid\""))
    // Adding by number can only ever create: an existing name is never rewritten.
    assertFalse(GROUP_SYNC.contains("groupmod"))
    assertFalse(GROUP_SYNC.contains("groupdel"))
  }

  @Test
  fun `a group that fails to be added cannot stop the terminal`() {
    assertTrue(GROUP_SYNC.contains("2>/dev/null || true"))
    // Nothing in the loop may abort the script before the shell is reached.
    assertFalse(GROUP_SYNC.contains("set -e"))
  }

  @Test
  fun `the sync runs on every session and the shell is still execed last`() {
    val script = guestStartupScript()

    // Outside the first-boot guard, so a second terminal re-checks the groups.
    assertEquals(FIRST_BOOT_SETUP.length, script.indexOf(GROUP_SYNC))
    assertTrue(script.endsWith("exec bash -l"))
  }

  @Test
  fun `the first-boot apt setup still runs once before the sync`() {
    val script = guestStartupScript()

    assertTrue(script.contains(FIRST_BOOT_SETUP))
    assertTrue(script.contains(".scoos-firstboot"))
    assertTrue(script.contains("apt-get install -y --no-install-recommends"))
    assertTrue(script.indexOf(FIRST_BOOT_SETUP) < script.indexOf(GROUP_SYNC))
  }
}
