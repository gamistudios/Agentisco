package com.agentisco

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.agentisco.core.model.AppDestination
import com.agentisco.data.model.Project
import com.agentisco.data.model.ProjectKind
import com.agentisco.data.model.WorkspaceStorageInfo
import com.agentisco.ui.components.AgentIDEBottomBar
import com.agentisco.ui.components.AgentIDETopAppBar
import com.agentisco.ui.screens.ProjectsScreenContent
import com.agentisco.ui.theme.AgentiscoTheme
import com.agentisco.ui.theme.DarkBackground
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the redesigned Projects/Workspace screen (global IDE header, IDE
 * toolbar, workspace overview, project cards, bottom navigation) with
 * representative sample projects and records PNGs under `src/test/screenshots`.
 *
 * The screen is exercised through the stateless [ProjectsScreenContent], so no
 * ViewModel, database or real workspace folder is involved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class ProjectsScreenScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val minute = 60_000L
  private val hour = 60 * minute
  private val day = 24 * hour

  /** A real PNG written to a temp folder, to exercise the "project has an icon" path. */
  private val realIconPath: String? by lazy {
    runCatching {
      val dir = File(System.getProperty("java.io.tmpdir"), "agentisco_shot_assets").apply { mkdirs() }
      val file = File(dir, "logo.png")
      val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
      Canvas(bitmap).apply {
        drawColor(0xFF6366F1.toInt())
        drawCircle(
          48f, 48f, 26f,
          Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F5F9.toInt() }
        )
      }
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      bitmap.recycle()
      file.absolutePath
    }.getOrNull()
  }

  private val activeProject = Project(
    id = "proj-agentisco-app",
    name = "agentisco-app",
    branch = "main",
    lastActivity = "Just now",
    changedFilesCount = 4,
    isDirty = true,
    description = "Agentisco mobile IDE",
    path = "/data/user/0/com.agentisco/files/rootfs/root/projects/agentisco-app",
    sizeBytes = 48_231_424,
    lastModified = System.currentTimeMillis() - 9 * minute,
    iconPath = realIconPath,
    kind = ProjectKind.ANDROID
  )

  private val sampleProjects = listOf(
    activeProject,
    Project(
      id = "proj-sco-cloud-api",
      name = "sco-cloud-api",
      branch = "main",
      lastActivity = "2h ago",
      description = "Sync backend",
      path = "/data/user/0/com.agentisco/files/rootfs/root/projects/sco-cloud-api",
      isImported = true,
      sourcePath = "/storage/emulated/0/Dev/sco-cloud-api",
      sizeBytes = 3_452_928,
      lastModified = System.currentTimeMillis() - 2 * hour,
      kind = ProjectKind.NODE
    ),
    Project(
      id = "proj-proot-rootfs-tools",
      name = "proot-rootfs-tools",
      branch = "main",
      lastActivity = "3d ago",
      description = "proot wrapper scripts",
      path = "/data/user/0/com.agentisco/files/rootfs/root/projects/proot-rootfs-tools",
      sizeBytes = 812_544,
      lastModified = System.currentTimeMillis() - 3 * day,
      kind = ProjectKind.RUST
    ),
    Project(
      id = "proj-legacy-design-system",
      name = "legacy-design-system",
      branch = "main",
      lastActivity = "12d ago",
      description = "Archived component library",
      path = "/data/user/0/com.agentisco/files/rootfs/root/projects/legacy-design-system",
      isMissing = true
    )
  )

  private val storage = WorkspaceStorageInfo(
    freeBytes = 27_412_987_904L, // ~25.5 GB
    totalBytes = 63_200_000_000L // ~58.9 GB
  )

  @Composable
  private fun Screen(projects: List<Project>, active: Project) {
    Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
      AgentIDETopAppBar(
        activeProject = active,
        currentDestination = AppDestination.PROJECTS,
        onNavigate = {},
        onOpenModelSheet = {},
        onOpenCommandPalette = {},
        hasNewUpdate = true,
        onUpdateClick = {}
      )
      ProjectsScreenContent(
        projects = projects,
        activeProject = active,
        workspacePath = "~/projects",
        storage = storage,
        onNewProject = {},
        onOpenFolder = {},
        onProjectClick = {},
        onCopyPath = {},
        onProjectAction = { _, _ -> },
        modifier = Modifier.weight(1f)
      )
      AgentIDEBottomBar(
        currentDestination = AppDestination.PROJECTS,
        isAgentWorking = false,
        onNavigate = {}
      )
    }
  }

  @Test
  fun projects_screen_screenshot() {
    composeTestRule.setContent {
      AgentiscoTheme { Screen(projects = sampleProjects, active = activeProject) }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/projects_screen.png")
  }

  /** The same screen while the Agent is working, to show the live bottom-bar state. */
  @Test
  fun projects_screen_agent_working_screenshot() {
    composeTestRule.setContent {
      AgentiscoTheme {
        Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
          AgentIDETopAppBar(
            activeProject = activeProject,
            currentDestination = AppDestination.PROJECTS,
            onNavigate = {},
            onOpenModelSheet = {},
            onOpenCommandPalette = {},
            hasNewUpdate = true,
            onUpdateClick = {}
          )
          androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
          AgentIDEBottomBar(
            currentDestination = AppDestination.PROJECTS,
            isAgentWorking = true,
            onNavigate = {},
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/ide_header_and_nav.png")
  }
}
