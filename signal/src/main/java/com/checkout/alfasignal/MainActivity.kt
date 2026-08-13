package com.checkout.alfasignal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
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
 * The whole app: shows the webhook URL, the trigger word, an on/off switch,
 * requests SMS permission, and lets the user test the webhook. The actual work
 * happens in [SmsWatcher] whenever an SMS arrives — even with this screen closed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlField: EditText
    private lateinit var wordField: EditText
    private lateinit var logView: TextView
    private lateinit var permBtn: Button

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
        root.addView(TextView(this).apply {
            text = "Ловит входящие SMS и, если в тексте есть слово-триггер, шлёт GET-запрос на вебхук. От любого номера."
            textSize = 14f
            setPadding(0, dp(6), 0, dp(16))
        })

        // On/off master switch
        val sw = Switch(this).apply {
            text = "  Слежение включено"
            textSize = 16f
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, v ->
                Prefs.setEnabled(this@MainActivity, v)
                toast(if (v) "Включено" else "Выключено")
            }
        }
        root.addView(sw)

        // Webhook URL
        root.addView(label("Вебхук (GET):"))
        urlField = EditText(this).apply {
            setText(Prefs.url(this@MainActivity))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            textSize = 14f
            setSingleLine(false)
        }
        root.addView(urlField)

        // Trigger word
        root.addView(label("Слово-триггер:"))
        wordField = EditText(this).apply {
            setText(Prefs.word(this@MainActivity))
            textSize = 16f
            setSingleLine(true)
        }
        root.addView(wordField)

        // Buttons
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val saveBtn = Button(this).apply {
            text = "Сохранить"
            setOnClickListener { save() }
        }
        val testBtn = Button(this).apply {
            text = "Тест"
            setOnClickListener { test() }
        }
        row.addView(saveBtn, lp())
        row.addView(testBtn, lp())
        root.addView(row)

        permBtn = Button(this).apply {
            setOnClickListener { requestSms() }
        }
        root.addView(permBtn)

        // Log
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerm()
        refreshLog()
    }

    private fun save() {
        Prefs.setUrl(this, urlField.text.toString())
        Prefs.setWord(this, wordField.text.toString())
        toast("Сохранено")
    }

    private fun test() {
        save()
        val url = Prefs.url(this)
        toast("Отправляю тест…")
        Thread {
            val code = Webhook.fireGet(url)
            Prefs.addLog(this, "${Webhook.now()}  🧪 ТЕСТ → ${if (code >= 0) "HTTP $code" else "нет сети"}")
            runOnUiThread {
                refreshLog()
                toast(if (code in 200..299) "OK: HTTP $code" else if (code >= 0) "Ответ HTTP $code" else "Нет связи")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun requestSms() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), 1)
    }

    private fun hasSms(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerm()
    }

    private fun refreshPerm() {
        if (hasSms()) {
            permBtn.text = "Доступ к SMS: выдан ✅"
            permBtn.isEnabled = false
        } else {
            permBtn.text = "Выдать доступ к SMS"
            permBtn.isEnabled = true
        }
    }

    private fun refreshLog() {
        val log = Prefs.log(this)
        logView.text = if (log.isBlank()) "— пока пусто —" else log
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun lp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
