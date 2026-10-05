package com.awaki.background

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Reads the real system state behind [BackgroundPermissionSnapshot] and opens the
 * settings the user needs.
 *
 * Every request is wrapped, because the battery-optimization dialog in particular
 * is absent or crashes on a fair number of OEM builds - a missing screen must
 * never take a running agent turn down with it.
 */
object BackgroundPermissions {

  fun snapshot(context: Context, foregroundServiceAllowed: Boolean): BackgroundPermissionSnapshot =
    BackgroundPermissionSnapshot(
      notificationsEnabled = notificationsEnabled(context),
      batteryExempt = isIgnoringBatteryOptimizations(context),
      backgroundDataRestricted = restrictsBackgroundData(context),
      foregroundServiceAllowed = foregroundServiceAllowed
    )

  fun requirements(
    context: Context,
    allowBackgroundExecution: Boolean,
    foregroundServiceAllowed: Boolean
  ): List<BackgroundRequirement> = buildBackgroundRequirements(
    sdkInt = Build.VERSION.SDK_INT,
    snapshot = snapshot(context, foregroundServiceAllowed),
    allowBackgroundExecution = allowBackgroundExecution
  )

  fun notificationsEnabled(context: Context): Boolean =
    ContextCompat.getSystemService(context, NotificationManager::class.java)?.areNotificationsEnabled() ?: true

  /**
   * True when the user already granted it, or when this Android version has no
   * optimization to opt out of (nothing to ask, so nothing is missing).
   */
  fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val manager = ContextCompat.getSystemService(context, PowerManager::class.java) ?: return true
    return runCatching { manager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
  }

  /**
   * Whether the system's background-data restriction currently applies to this app.
   * The reading is global (a per-app override is not exposed), so the checklist row
   * always links to the screen where the user can see and change it.
   */
  fun restrictsBackgroundData(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
    val manager = ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return false
    return runCatching {
      manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)
  }

  /**
   * Asks the system to put Awaki on the doze allow-list. Returns false when the
   * device cannot show the dialog, so the caller can fall back to settings guidance.
   */
  fun requestBatteryExemption(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
      .setData(Uri.parse("package:${context.packageName}"))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return startIntent(context, intent)
  }

  fun openBatteryOptimizationList(context: Context): Boolean =
    startIntent(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

  fun openNotificationSettings(context: Context): Boolean {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
    }
    return startIntent(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  fun openBackgroundDataSettings(context: Context): Boolean {
    val intent = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS)
      .setData(Uri.parse("package:${context.packageName}"))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return startIntent(context, intent) || openAppSettings(context)
  }

  fun openAppSettings(context: Context): Boolean =
    startIntent(
      context,
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

  /**
   * Opens whatever screen fixes [action]. False means the caller still has work to
   * do: the runtime notification ask belongs to the UI, and a device may simply not
   * have the settings page - failing silently is not an option for a checklist the
   * user is relying on.
   */
  fun perform(context: Context, action: RequirementAction): Boolean = when (action) {
    RequirementAction.NONE -> false
    RequirementAction.REQUEST_NOTIFICATIONS -> false
    RequirementAction.OPEN_NOTIFICATION_SETTINGS -> openNotificationSettings(context)
    RequirementAction.REQUEST_BATTERY_EXEMPTION ->
      requestBatteryExemption(context) || openBatteryOptimizationList(context)
    RequirementAction.OPEN_BATTERY_SETTINGS -> openBatteryOptimizationList(context)
    RequirementAction.OPEN_BACKGROUND_DATA_SETTINGS -> openBackgroundDataSettings(context)
    RequirementAction.OPEN_APP_SETTINGS -> openAppSettings(context)
  }

  private fun startIntent(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent)
    true
  }.getOrDefault(false)
}
