package com.claudecodesetup.managers

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.workers.ScheduledPromptWorker
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Manages scheduled prompts via WorkManager.
 *
 * Each prompt fires once per day at the configured time. WorkManager
 * minimum interval is 15 minutes; exact-time delivery is approximate (~15 min window).
 *
 * Stored JSON format:
 * [{ "id": "uuid", "prompt": "...", "hour": 8, "minute": 0,
 *    "days": [2,3,4,5,6], "enabled": true }]
 * days = Calendar.DAY_OF_WEEK values (1=Sun … 7=Sat)
 */
object ScheduledPromptsManager {

    fun getAll(prefs: AppPreferences): List<ScheduledPrompt> {
        return try {
            val arr = JSONArray(prefs.getScheduledPromptsJson())
            (0 until arr.length()).map { ScheduledPrompt.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, prefs: AppPreferences, prompts: List<ScheduledPrompt>) {
        val arr = JSONArray()
        prompts.forEach { arr.put(it.toJson()) }
        prefs.saveScheduledPromptsJson(arr.toString())
        rescheduleAll(context, prompts)
    }

    fun add(context: Context, prefs: AppPreferences, prompt: ScheduledPrompt) {
        val current = getAll(prefs).toMutableList()
        current.add(prompt)
        save(context, prefs, current)
    }

    fun remove(context: Context, prefs: AppPreferences, id: String) {
        val current = getAll(prefs).filter { it.id != id }
        save(context, prefs, current)
        WorkManager.getInstance(context).cancelUniqueWork("scheduled_prompt_$id")
    }

    fun rescheduleAll(context: Context, prompts: List<ScheduledPrompt>) {
        val wm = WorkManager.getInstance(context)
        prompts.forEach { p ->
            wm.cancelUniqueWork("scheduled_prompt_${p.id}")
            if (!p.enabled) return@forEach

            val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            if (today !in p.days) return@forEach  // skip if today not in scheduled days

            val initialDelay = calcInitialDelay(p.hour, p.minute)
            val data = Data.Builder()
                .putString(ScheduledPromptWorker.KEY_PROMPT, p.prompt)
                .putString(ScheduledPromptWorker.KEY_PROMPT_ID, p.id)
                .build()

            val request = PeriodicWorkRequestBuilder<ScheduledPromptWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()

            wm.enqueueUniquePeriodicWork(
                "scheduled_prompt_${p.id}",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }

    private fun calcInitialDelay(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }
}

data class ScheduledPrompt(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7)  // all days by default (Calendar.DAY_OF_WEEK: 1=Sun…7=Sat)
) {
    val timeLabel: String get() = String.format("%02d:%02d", hour, minute)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("prompt", prompt)
        put("hour", hour)
        put("minute", minute)
        put("enabled", enabled)
        put("days", JSONArray().also { arr -> days.forEach { arr.put(it) } })
    }

    companion object {
        fun fromJson(j: JSONObject) = ScheduledPrompt(
            id      = j.optString("id", UUID.randomUUID().toString()),
            prompt  = j.optString("prompt"),
            hour    = j.optInt("hour", 9),
            minute  = j.optInt("minute", 0),
            enabled = j.optBoolean("enabled", true),
            days    = j.optJSONArray("days")?.let { arr ->
                (0 until arr.length()).map { arr.getInt(it) }
            } ?: listOf(1, 2, 3, 4, 5, 6, 7)
        )
    }
}
