package com.example

import com.example.data.repository.DestructiveCommandGuard
import com.example.data.repository.DestructiveSeverity
import org.junit.Assert.*
import org.junit.Test

class DestructiveCommandGuardTest {

  @Test
  fun testSafeCommands_notBlocked() {
    assertNull(DestructiveCommandGuard.assess("git remote -v"))
    assertNull(DestructiveCommandGuard.assess("git status"))
    assertNull(DestructiveCommandGuard.assess("git branch -a"))
    assertNull(DestructiveCommandGuard.assess("git log --oneline"))
    assertNull(DestructiveCommandGuard.assess("git diff"))
    assertNull(DestructiveCommandGuard.assess("git config --list"))
    assertNull(DestructiveCommandGuard.assess("neofetch"))
    assertNull(DestructiveCommandGuard.assess("apt update"))
    assertNull(DestructiveCommandGuard.assess("ls -la"))
    assertNull(DestructiveCommandGuard.assess("cat README.md"))
    assertNull(DestructiveCommandGuard.assess("python3 -c 'print(1)'"))
    assertNull(DestructiveCommandGuard.assess("node -v"))
  }

  @Test
  fun testDestructiveRmRf_detected() {
    val rmRoot = DestructiveCommandGuard.assess("rm -rf /")
    assertNotNull(rmRoot)
    assertEquals(DestructiveSeverity.CRITICAL, rmRoot?.severity)

    val rmStar = DestructiveCommandGuard.assess("rm -rf *")
    assertNotNull(rmStar)
    assertEquals(DestructiveSeverity.CRITICAL, rmStar?.severity)

    val rmFolder = DestructiveCommandGuard.assess("rm -r node_modules")
    assertNotNull(rmFolder)
    assertEquals(DestructiveSeverity.HIGH, rmFolder?.severity)
  }

  @Test
  fun testGitHardReset_detected() {
    val gitReset = DestructiveCommandGuard.assess("git reset --hard HEAD~1")
    assertNotNull(gitReset)
    assertEquals("Git Hard Reset Guard", gitReset?.title)
  }

  @Test
  fun testGitCleanForce_detected() {
    val gitClean = DestructiveCommandGuard.assess("git clean -fd")
    assertNotNull(gitClean)
    assertEquals("Git Clean Guard", gitClean?.title)
  }

  @Test
  fun testChmodRecursiveRoot_detected() {
    val chmod = DestructiveCommandGuard.assess("chmod -R 777 /")
    assertNotNull(chmod)
    assertEquals(DestructiveSeverity.CRITICAL, chmod?.severity)
  }
}
