package com.agentisco

import com.agentisco.workspace.terminal.RootfsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsCatalogTest {

  @Test
  fun `arm64 entry has valid checksum and bundled archive name`() {
    val entry = RootfsCatalog.forAbi("arm64-v8a")
    assertNotNull(entry)
    entry!!
    assertEquals("librootfs64.so", entry.bundledName)
    assertEquals("ubuntu-base-24.04.5-base-arm64.tar.gz", entry.fileName)
    assertTrue("checksum must be 64 hex chars", entry.sha256.length == 64 && entry.sha256.all { it.isDigit() || it in 'a'..'f' })
  }

  @Test
  fun `armhf entry exists for 32-bit devices`() {
    val entry = RootfsCatalog.forAbi("armeabi-v7a")
    assertNotNull(entry)
    entry!!
    assertEquals("librootfs32.so", entry.bundledName)
    assertEquals(64, entry.sha256.length)
  }
}
