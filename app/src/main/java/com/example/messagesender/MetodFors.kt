package com.example.messagesender

import android.content.Context
import org.json.JSONObject

/**
 * Parsed "Метод Форс" flow configuration, delivered by the server (so the exact
 * Beeline screen labels, package name and rule times can be re-tuned without an
 * APK rebuild). See `metodForsConfig()` on the server for the source of truth.
 */
data class MfRule(val fireSec: Int, val prepLeadSec: Int)

data class MetodForsConfig(
    val beelinePackage: String,
    val steps: List<String>,
    val cardFieldHint: String,
    val continueLabel: String,
    val sendLabel: String,
    val backToFinanceLabel: String,
    val repeatLabel: String,
    val symbolWord: String,
    val replyText: String,
    val successWord: String,
    val ruleA: MfRule,
    val ruleB: MfRule,
) {
    companion object {
        fun defaults() = MetodForsConfig(
            beelinePackage = "ru.beeline.services",
            steps = listOf("Сервисы", "Перевести деньги", "Перевод на карту зарубеж",
                "Таджикистан", "По номеру карты", "Мой номер"),
            cardFieldHint = "Введите номер карты",
            continueLabel = "Продолжить",
            sendLabel = "Отправить",
            backToFinanceLabel = "Вернуться в финансы",
            repeatLabel = "Повторить",
            symbolWord = "символ",
            replyText = "Ок",
            successWord = "успешно",
            ruleA = MfRule(779, 149),   // xx:12:59, prep from xx:10:30
            ruleB = MfRule(3599, 300),  // xx:59:59, prep from xx:54:59
        )

        fun from(context: Context): MetodForsConfig {
            val raw = DeviceStore.mfConfigJson(context)
            if (raw.isBlank()) return defaults()
            return try { parse(JSONObject(raw)) } catch (e: Exception) { defaults() }
        }

        private fun parse(o: JSONObject): MetodForsConfig {
            val d = defaults()
            val steps = o.optJSONArray("steps")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }?.takeIf { it.isNotEmpty() } ?: d.steps
            fun rule(name: String, def: MfRule): MfRule {
                val r = o.optJSONObject(name) ?: return def
                return MfRule(r.optInt("fireSec", def.fireSec), r.optInt("prepLeadSec", def.prepLeadSec))
            }
            return MetodForsConfig(
                beelinePackage = o.optString("beelinePackage", d.beelinePackage).ifBlank { d.beelinePackage },
                steps = steps,
                cardFieldHint = o.optString("cardFieldHint", d.cardFieldHint).ifBlank { d.cardFieldHint },
                continueLabel = o.optString("continueLabel", d.continueLabel).ifBlank { d.continueLabel },
                sendLabel = o.optString("sendLabel", d.sendLabel).ifBlank { d.sendLabel },
                backToFinanceLabel = o.optString("backToFinanceLabel", d.backToFinanceLabel).ifBlank { d.backToFinanceLabel },
                repeatLabel = o.optString("repeatLabel", d.repeatLabel).ifBlank { d.repeatLabel },
                symbolWord = o.optString("symbolWord", d.symbolWord).ifBlank { d.symbolWord },
                replyText = o.optString("replyText", d.replyText).ifBlank { d.replyText },
                successWord = o.optString("successWord", d.successWord).ifBlank { d.successWord },
                ruleA = rule("ruleA", d.ruleA),
                ruleB = rule("ruleB", d.ruleB),
            )
        }
    }
}

/**
 * Coordination between the SMS receiver and the "Метод Форс" automation engine.
 *
 * The engine drives the Beeline UI; the actual «символ» → «Ок» → «успешно»
 * handshake still arrives as ordinary SMS from 8464, handled by [SmsReceiver].
 * When Метод Форс is active the receiver replies «Ок» and stamps the times here;
 * the engine's per-transfer state machine waits on these stamps.
 */
object MetodFors {

    @Volatile var lastSymbolAt: Long = 0L
        private set
    @Volatile var lastSuccessAt: Long = 0L
        private set

    fun onSymbol() { lastSymbolAt = System.currentTimeMillis() }
    fun onSuccess() { lastSuccessAt = System.currentTimeMillis() }

    /** Wait for a «символ» stamped strictly after [sinceMs]. Returns its time or 0. */
    fun awaitSymbol(sinceMs: Long, timeoutMs: Long): Long = awaitAfter({ lastSymbolAt }, sinceMs, timeoutMs)

    /** Wait for a «успешно» stamped strictly after [sinceMs]. Returns its time or 0. */
    fun awaitSuccess(sinceMs: Long, timeoutMs: Long): Long = awaitAfter({ lastSuccessAt }, sinceMs, timeoutMs)

    private inline fun awaitAfter(read: () -> Long, sinceMs: Long, timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val v = read()
            if (v > sinceMs) return v
            try { Thread.sleep(200L) } catch (e: InterruptedException) { return 0L }
        }
        return 0L
    }
}
