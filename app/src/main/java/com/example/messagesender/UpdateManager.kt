package com.example.messagesender

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * In-app updater. Checks <server>/app/version.json for a newer versionCode and,
 * if found, offers to download and install <server>/app/alfa-sms.apk (installed
 * over the current app — no manual uninstall). Android still shows its own
 * install confirmation. The server URL is the one this device paired with, or
 * the build default before pairing.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun base(context: android.content.Context): String {
        val paired = DeviceStore.serverUrl(context)
        return (if (paired.isNotBlank()) paired else BuildConfig.DEFAULT_SERVER_URL).trimEnd('/')
    }

    fun checkForUpdate(activity: Activity) {
        io.execute {
            try {
                val json = httpGet("${base(activity)}/app/version.json") ?: return@execute
                val obj = JSONObject(json)
                val latest = obj.optInt("versionCode", 0)
                val name = obj.optString("versionName", "")
                val notes = obj.optString("notes", "")
                if (latest > BuildConfig.VERSION_CODE) {
                    main.post { if (!activity.isFinishing) promptUpdate(activity, name, notes) }
                }
            } catch (e: Exception) {
                Log.i(TAG, "Update check skipped: ${e.message}")
            }
        }
    }

    private fun promptUpdate(activity: Activity, name: String, notes: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(activity.getString(R.string.update_message, name, notes))
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(activity) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startUpdate(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(activity, R.string.update_allow_sources, Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (_: Exception) {}
            return
        }

        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        io.execute {
            val file = downloadApk(activity)
            main.post {
                if (file == null) Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                else installApk(activity, file)
            }
        }
    }

    private fun downloadApk(activity: Activity): File? {
        return try {
            val dir = activity.externalCacheDir ?: activity.cacheDir
            val file = File(dir, "alfa-sms-update.apk")
            val conn = URL("${base(activity)}/app/alfa-sms.apk").openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            file
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            null
        }
    }

    private fun installApk(activity: Activity, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun httpGet(urlStr: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
