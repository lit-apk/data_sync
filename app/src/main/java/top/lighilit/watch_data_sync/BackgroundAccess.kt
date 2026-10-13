package top.lighilit.watch_data_sync

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Keeps the app usable while the phone is locked: the watch sends "next"/"previous" and
 * the phone must still be allowed to answer. Android's battery optimization (and Xiaomi's
 * Battery saver, "No restrictions") otherwise suspends the app in the background.
 */
internal object BackgroundAccess {
    /** True when the system battery optimization no longer restricts this app. */
    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Asks the system to exempt the app (one confirmation dialog). If the device does not
     * offer that dialog, opens this app's settings page, where Battery saver can be set to
     * "No restrictions" manually.
     */
    @SuppressLint("BatteryLife") // Exemption is the core feature: answering the watch while locked.
    fun request(context: Context) {
        val exempt = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        try {
            context.startActivity(exempt)
        } catch (_: ActivityNotFoundException) {
            openAppSettings(context)
        }
    }

    /**
     * Xiaomi/Redmi/POCO: [request] already opens the Battery saver page ("No restrictions"),
     * so a separate app-settings button would duplicate it.
     */
    fun isXiaomi(): Boolean = Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** This app's system settings page (Battery saver / autostart live there on MIUI/HyperOS). */
    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        )
    }
}
