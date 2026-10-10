package com.awaki.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Reads whether Awaki may touch files on shared storage, and opens the one system
 * screen that grants it.
 *
 * The manifest declares `MANAGE_EXTERNAL_STORAGE`, but a declaration grants nothing:
 * from Android 11 "All files access" is a special toggle the user flips in system
 * settings, and from Android 13 the legacy storage escape hatch that apps with
 * targetSdk 28 used to rely on is gone. So on a modern device every direct read of
 * `/storage/emulated/0` — projects, model files, the proot bind — fails until this
 * access exists. Callers ask once at startup and record a refusal, so the interrupt
 * is a single setup step instead of a recurring prompt.
 */
object StorageAccess {

  /** True when direct file paths under shared storage are readable and writable. */
  fun hasAccess(context: Context): Boolean = decide(
    modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
    isAllFilesManager = { runCatching { Environment.isExternalStorageManager() }.getOrDefault(false) },
    legacyPermissions = legacyMissingPermissions(context)
  )

  /** Legacy runtime storage permissions still missing below Android 11; empty when none are. */
  fun missingLegacyPermissions(context: Context): Array<String> = legacyMissingPermissions(context).toTypedArray()

  /**
   * The whole version split in one pure function: from Android 11 access is the
   * "All files access" manager flag, below it both runtime permissions. Testable
   * without a device, which is where getting this wrong would stay invisible.
   */
  internal fun decide(
    modern: Boolean,
    isAllFilesManager: () -> Boolean,
    legacyPermissions: List<String>
  ): Boolean = if (modern) isAllFilesManager() else legacyPermissions.isEmpty()

  private fun legacyMissingPermissions(context: Context): List<String> =
    listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
      .filterNot { runtimeGranted(context, it) }

  /**
   * The system page that grants file access on this device: the "All files access"
   * screen where it exists, the app details page where it does not. Returned rather
   * than started so the startup ask can observe the result and remember a refusal.
   */
  fun accessIntent(context: Context): Intent {
    val packageUri = Uri.parse("package:${context.packageName}")
    val allFiles = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).setData(packageUri)
    val resolver = context.packageManager
    return if (allFiles.resolveActivity(resolver) != null) allFiles
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)
  }

  /** Opens [accessIntent]. False when even the fallback page cannot be started. */
  fun openAccessSettings(context: Context): Boolean = startIntent(context, accessIntent(context))

  private fun runtimeGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

  private fun startIntent(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
  }.getOrDefault(false)
}
