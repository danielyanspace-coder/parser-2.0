package com.example.messagesender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.messagesender.databinding.ActivityMainBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors

/**
 * The whole user-facing app: a QR scanner and a status screen. All settings now
 * live in the Telegram mini-app; the phone only pairs (by scanning the QR the
 * mini-app shows) and then obeys the server via [SenderService].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val background = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val statusPoller = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 1000)
        }
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) onQrScanned(contents)
    }

    private val requestSmsPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.SEND_SMS] != true) {
            Toast.makeText(this, R.string.permission_sms_needed, Toast.LENGTH_LONG).show()
        }
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort; service still runs without it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonScan.setOnClickListener { launchScanner() }
        binding.buttonUnpair.setOnClickListener { confirmUnpair() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (DeviceStore.isPaired(this)) SenderService.start(this)

        // Offer an in-app update if the server has a newer build.
        UpdateManager.checkForUpdate(this)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(statusPoller)
        ui.post(statusPoller)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(statusPoller)
    }

    // --- Scanning / pairing ---

    private fun launchScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    private fun onQrScanned(contents: String) {
        val uri = runCatching { Uri.parse(contents) }.getOrNull()
        val server = uri?.getQueryParameter("u")
        val code = uri?.getQueryParameter("c")
        if (uri?.scheme != "alfasms" || server.isNullOrBlank() || code.isNullOrBlank()) {
            Toast.makeText(this, R.string.pair_bad_qr, Toast.LENGTH_LONG).show()
            return
        }

        binding.textPairState.text = getString(R.string.pairing)
        binding.textStatus.text = ""
        val hardwareId = DeviceStore.hardwareId(this)

        background.execute {
            val outcome = ControlClient.pair(server, code, hardwareId)
            runOnUiThread {
                if (outcome.ok) {
                    DeviceStore.savePairing(this, server, outcome.deviceId, outcome.secret, outcome.name)
                    Toast.makeText(this, R.string.pair_success, Toast.LENGTH_SHORT).show()
                    ensureSmsPermissions()
                    SenderService.start(this)
                    maybeRequestBatteryExemption()
                } else {
                    Toast.makeText(
                        this, getString(R.string.pair_failed, outcome.error), Toast.LENGTH_LONG
                    ).show()
                }
                refreshUi()
            }
        }
    }

    private fun ensureSmsPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.SEND_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECEIVE_SMS)
        if (need.isNotEmpty()) requestSmsPermissions.launch(need.toTypedArray())
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair)
            .setMessage(R.string.unpair_confirm)
            .setPositiveButton(R.string.unpair) { _, _ ->
                stopService(Intent(this, SenderService::class.java))
                DeviceStore.clearPairing(this)
                refreshUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Aggressive battery optimization is the usual reason a background service dies. */
    private fun maybeRequestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_allow) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) { /* nothing more we can do */ }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Status UI ---

    private fun refreshUi() {
        val paired = DeviceStore.isPaired(this)
        binding.buttonUnpair.visibility = if (paired) View.VISIBLE else View.GONE
        binding.buttonScan.setText(if (paired) R.string.rescan_button else R.string.scan_button)

        if (!paired) {
            binding.textPairState.text = getString(R.string.not_paired)
            binding.textStatus.text = getString(R.string.not_paired_hint)
            return
        }

        binding.textPairState.text = getString(R.string.paired_as, DeviceStore.name(this))
        binding.textStatus.text = buildStatusLine()
    }

    private fun buildStatusLine(): String {
        val base = when {
            !DeviceStore.tokenValid(this) -> getString(R.string.state_token_invalid)
            SenderStatus.offline -> getString(R.string.state_offline)
            !DeviceStore.active(this) -> getString(R.string.state_inactive)
            !DeviceStore.run(this) -> getString(R.string.state_stopped)
            DeviceStore.isPaused(this) -> getString(R.string.state_paused)
            else -> getString(R.string.state_working, SenderStatus.sentCount)
        }
        val working = DeviceStore.run(this) && DeviceStore.active(this) && DeviceStore.tokenValid(this)
        if (!working) return base

        var line = base
        val payments = DeviceStore.payments(this)
        if (payments.isNotEmpty()) {
            val idx = (DeviceStore.paymentIndex(this) + 1).coerceAtMost(payments.size)
            line += "\n" + getString(R.string.state_payment, idx, payments.size) +
                " • " + getString(R.string.state_triggers, DeviceStore.triggerCount(this))
        }
        val err = SenderStatus.lastError
        if (err != null) line += "\n" + getString(R.string.state_error, err)
        return line
    }
}
