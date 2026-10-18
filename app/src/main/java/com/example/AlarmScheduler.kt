package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object AlarmScheduler {

  private const val PREFS_NAME = "alarms_storage"
  private const val KEY_ALARMS = "stored_alarms"
  private const val TAG = "AlarmScheduler"

  data class StoredAlarm(
      val id: Int,
      val title: String,
      val body: String,
      val triggerTimeMs: Long
  )

  private fun getPrefs(context: Context): SharedPreferences {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  fun schedule(context: Context, id: Int, title: String, body: String, triggerTimeMs: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val now = System.currentTimeMillis()

    if (triggerTimeMs <= now) {
      Log.d(TAG, "Skipping past alarm: id=$id, triggerTime=$triggerTimeMs vs now=$now")
      return
    }

    val intent =
        Intent(context, AlarmReceiver::class.java).apply {
          action = "com.aistudio.madiriat.ALARM_ACTION"
          putExtra(AlarmReceiver.EXTRA_ID, id)
          putExtra(AlarmReceiver.EXTRA_TITLE, title)
          putExtra(AlarmReceiver.EXTRA_BODY, body)
        }

    val pendingIntent =
        PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTimeMs,
            pendingIntent
        )
      } else {
        alarmManager.setExact(
            AlarmManager.RTC_WAKEUP,
            triggerTimeMs,
            pendingIntent
        )
      }
      storeAlarm(context, StoredAlarm(id, title, body, triggerTimeMs))
      Log.d(TAG, "Scheduled exact alarm: id=$id at $triggerTimeMs (in ${(triggerTimeMs - now) / 1000}s)")
    } catch (e: SecurityException) {
      Log.e(TAG, "Exact alarm permission missing, fallback to inexact", e)
      alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
      storeAlarm(context, StoredAlarm(id, title, body, triggerTimeMs))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to schedule alarm id=$id", e)
    }
  }

  fun cancel(context: Context, id: Int) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val intent =
        Intent(context, AlarmReceiver::class.java).apply {
          action = "com.aistudio.madiriat.ALARM_ACTION"
        }
    val pendingIntent =
        PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    if (pendingIntent != null) {
      alarmManager.cancel(pendingIntent)
      pendingIntent.cancel()
    }
    removeStoredAlarm(context, id)
    Log.d(TAG, "Cancelled alarm id=$id")
  }

  fun cancelAll(context: Context) {
    val alarms = getStoredAlarms(context)
    for (alarm in alarms) {
      cancel(context, alarm.id)
    }
    getPrefs(context).edit().clear().apply()
  }

  fun getPendingIds(context: Context): List<Int> {
    val now = System.currentTimeMillis()
    return getStoredAlarms(context).filter { it.triggerTimeMs > now }.map { it.id }
  }

  fun rescheduleAll(context: Context) {
    val alarms = getStoredAlarms(context)
    val now = System.currentTimeMillis()
    Log.d(TAG, "Rescheduling ${alarms.size} alarms after boot/app start")
    for (alarm in alarms) {
      if (alarm.triggerTimeMs > now) {
        schedule(context, alarm.id, alarm.title, alarm.body, alarm.triggerTimeMs)
      } else {
        removeStoredAlarm(context, alarm.id)
      }
    }
  }

  private fun storeAlarm(context: Context, alarm: StoredAlarm) {
    val alarms = getStoredAlarms(context).filter { it.id != alarm.id }.toMutableList()
    alarms.add(alarm)
    saveStoredAlarms(context, alarms)
  }

  fun removeStoredAlarm(context: Context, id: Int) {
    val alarms = getStoredAlarms(context).filter { it.id != id }
    saveStoredAlarms(context, alarms)
  }

  private fun getStoredAlarms(context: Context): List<StoredAlarm> {
    val jsonString = getPrefs(context).getString(KEY_ALARMS, null) ?: return emptyList()
    return try {
      val jsonArray = JSONArray(jsonString)
      val list = mutableListOf<StoredAlarm>()
      for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
            StoredAlarm(
                id = obj.getInt("id"),
                title = obj.getString("title"),
                body = obj.getString("body"),
                triggerTimeMs = obj.getLong("triggerTimeMs")
            )
        )
      }
      list
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read stored alarms", e)
      emptyList()
    }
  }

  private fun saveStoredAlarms(context: Context, alarms: List<StoredAlarm>) {
    try {
      val jsonArray = JSONArray()
      for (alarm in alarms) {
        val obj =
            JSONObject().apply {
              put("id", alarm.id)
              put("title", alarm.title)
              put("body", alarm.body)
              put("triggerTimeMs", alarm.triggerTimeMs)
            }
        jsonArray.put(obj)
      }
      getPrefs(context).edit().putString(KEY_ALARMS, jsonArray.toString()).apply()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save stored alarms", e)
    }
  }
}
