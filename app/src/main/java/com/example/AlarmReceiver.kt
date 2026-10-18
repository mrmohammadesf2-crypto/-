package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {

  companion object {
    const val EXTRA_ID = "alarm_id"
    const val EXTRA_TITLE = "alarm_title"
    const val EXTRA_BODY = "alarm_body"
    private const val TAG = "AlarmReceiver"
  }

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent == null) return

    val id = intent.getIntExtra(EXTRA_ID, 0)
    val title = intent.getStringExtra(EXTRA_TITLE) ?: "یادآوری مدیریت"
    val body = intent.getStringExtra(EXTRA_BODY) ?: "موعد یادآوری فرا رسیده است"

    Log.d(TAG, "Alarm triggered: id=$id, title=$title, body=$body")

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val wakeLock =
        powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "madiriat:alarm_wakelock"
        )
    wakeLock?.acquire(5000L)

    try {
      NotificationHelper.showNotification(context, id, title, body)
      AlarmScheduler.removeStoredAlarm(context, id)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to display notification", e)
    } finally {
      if (wakeLock?.isHeld == true) {
        wakeLock.release()
      }
    }
  }
}
