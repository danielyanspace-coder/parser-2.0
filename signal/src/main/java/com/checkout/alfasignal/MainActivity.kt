package com.checkout.alfasignal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The whole app. Two jobs:
 *   1. Signal watcher — when an incoming SMS contains the trigger word, fire the
 *      webhook (see [SmsWatcher]).
 *   2. Periodic sender — send a chosen SMS to a chosen number every N seconds
 *      (see [SmsSender]).
 * Both keep working with this screen closed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlField: EditText
    private lateinit var wordField: EditText
    private lateinit var msgField: EditText
    private lateinit var numberField: EditText
    private lateinit var intervalField: EditText
    private lateinit var sendBtn: Button
    private lateinit var logView: TextView
    private lateinit var permBtn: Button
    private lateinit var battBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "ALFA Signal"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // ---------- Section 1: signal watcher ----------
        root.addView(section("Сигнал по входящей SMS"))
        root.addView(TextView(this).apply {
            text = "Ловит входящие SMS и, если в тексте есть слово-триггер, шлёт GET-запрос на вебхук. От любого номера."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })

        val sw = Switch(this).apply {
            text = "  Слежение включено"
            textSize = 16f
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, v ->
                Prefs.setEnabled(this@MainActivity, v)
                toast(if (v) "Слежение включено" else "Слежение выключено")
            }
        }
        root.addView(sw)

        root.addView(label("Вебхук (GET):"))
        urlField = EditText(this).apply {
            setText(Prefs.url(this@MainActivity))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            textSize = 14f
            setSingleLine(false)
        }
        root.addView(urlField)

        root.addView(label("Слово-триггер:"))
        wordField = EditText(this).apply {
            setText(Prefs.word(this@MainActivity))
            textSize = 16f
            setSingleLine(true)
        }
        root.addView(wordField)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        row1.addView(Button(this).apply {
            text = "Сохранить сигнал"
            setOnClickListener { saveSignal() }
        }, lp())
        row1.addView(Button(this).apply {
            text = "Тест вебхука"
            setOnClickListener { testWebhook() }
        }, lp())
        root.addView(row1)

        // ---------- Section 2: periodic sender ----------
        root.addView(section("Отправка SMS по интервалу"))

        root.addView(label("Текст сообщения:"))
        msgField = EditText(this).apply {
            setText(Prefs.msg(this@MainActivity))
            textSize = 15f
            setSingleLine(false)
        }
        root.addView(msgField)

        root.addView(label("Номер получателя:"))
        numberField = EditText(this).apply {
            setText(Prefs.number(this@MainActivity))
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 16f
            setSingleLine(true)
        }
        root.addView(numberField)

        root.addView(label("Интервал (секунды):"))
        intervalField = EditText(this).apply {
            setText(Prefs.intervalSec(this@MainActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setSingleLine(true)
        }
        root.addView(intervalField)

        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        presets.addView(Button(this).apply {
            text = "30 сек"
            setOnClickListener { intervalField.setText("30") }
        }, lp())
        presets.addView(Button(this).apply {
            text = "60 сек"
            setOnClickListener { intervalField.setText("60") }
        }, lp())
        root.addView(presets)

        sendBtn = Button(this).apply {
            setOnClickListener { toggleSending() }
        }
        root.addView(sendBtn)

        // ---------- Permissions ----------
        root.addView(section("Доступ"))
        permBtn = Button(this).apply {
            setOnClickListener { requestPerms() }
        }
        root.addView(permBtn)

        battBtn = Button(this).apply {
            setOnClickListener { requestIgnoreBattery() }
        }
        root.addView(battBtn)
        root.addView(TextView(this).apply {
            text = "Чтобы отправка и приём работали в фоне, отключи экономию батареи для приложения и разреши автозапуск в настройках телефона."
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })

        // ---------- Log ----------
        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        logRow.addView(TextView(this).apply {
            text = "Журнал"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp())
        logRow.addView(Button(this).apply {
            text = "Очистить"
            setOnClickListener { Prefs.clearLog(this@MainActivity); refreshLog() }
        })
        root.addView(logRow)

        logView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerm()
        refreshBattBtn()
        refreshSendBtn()
        refreshLog()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Экономия уже отключена"); return }
        try {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e2: Exception) { toast("Открой настройки батареи вручную") }
        }
    }

    private fun isBatteryFree(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBattBtn() {
        if (isBatteryFree()) {
            battBtn.text = "Экономия батареи: отключена ✅"
            battBtn.isEnabled = false
        } else {
            battBtn.text = "⚠ Отключить экономию батареи"
            battBtn.isEnabled = true
        }
    }

    // --- Signal ---
    private fun saveSignal() {
        Prefs.setUrl(this, urlField.text.toString())
        Prefs.setWord(this, wordField.text.toString())
        toast("Сохранено")
    }

    private fun testWebhook() {
        saveSignal()
        val url = Prefs.url(this)
        toast("Отправляю тест…")
        Thread {
            val code = Webhook.fireGet(url)
            Prefs.addLog(this, "${Webhook.now()}  🧪 ТЕСТ вебхука → ${if (code >= 0) "HTTP $code" else "нет сети"}")
            runOnUiThread {
                refreshLog()
                toast(if (code in 200..299) "OK: HTTP $code" else if (code >= 0) "Ответ HTTP $code" else "Нет связи")
            }
        }.apply { isDaemon = true }.start()
    }

    // --- Sender ---
    private fun saveSender() {
        Prefs.setMsg(this, msgField.text.toString())
        Prefs.setNumber(this, numberField.text.toString())
        val sec = intervalField.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: Prefs.DEFAULT_INTERVAL
        Prefs.setIntervalSec(this, sec)
        intervalField.setText(sec.toString())
    }

    private fun toggleSending() {
        if (Prefs.sending(this)) {
            SmsSender.stop(this)
            Prefs.setSending(this, false)
            toast("Отправка остановлена")
            refreshSendBtn()
            return
        }
        if (!hasSend()) {
            toast("Нужен доступ к отправке SMS")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 3)
            return
        }
        saveSender()
        SmsSender.start(this)
        toast("Отправка запущена: каждые ${Prefs.intervalSec(this)} c")
        refreshSendBtn()
        // Background reliability hinges on this — prompt if not yet whitelisted.
        if (!isBatteryFree()) requestIgnoreBattery()
    }

    private fun refreshSendBtn() {
        if (Prefs.sending(this)) {
            sendBtn.text = "■ Остановить отправку"
        } else {
            sendBtn.text = "▶ Запустить отправку"
        }
    }

    // --- Permissions ---
    private fun requestPerms() {
        val need = mutableListOf<String>()
        if (!hasReceive()) need += Manifest.permission.RECEIVE_SMS
        if (!hasSend()) need += Manifest.permission.SEND_SMS
        if (need.isEmpty()) { toast("Все разрешения выданы"); return }
        ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    private fun hasReceive() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    private fun hasSend() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerm()
        // If the user granted SEND while trying to start, start now.
        if (requestCode == 3 && hasSend()) {
            saveSender(); SmsSender.start(this); refreshSendBtn()
            toast("Отправка запущена")
        }
    }

    private fun refreshPerm() {
        val r = hasReceive(); val s = hasSend()
        when {
            r && s -> { permBtn.text = "Доступ к SMS: выдан ✅"; permBtn.isEnabled = false }
            else -> {
                permBtn.text = "Выдать доступ к SMS (приём" +
                    (if (!r) " ✗" else " ✓") + " / отправка" + (if (!s) " ✗" else " ✓") + ")"
                permBtn.isEnabled = true
            }
        }
    }

    private fun refreshLog() {
        val log = Prefs.log(this)
        logView.text = if (log.isBlank()) "— пока пусто —" else log
    }

    // --- helpers ---
    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(4))
    }
    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, dp(12), 0, dp(4))
    }
    private fun lp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
