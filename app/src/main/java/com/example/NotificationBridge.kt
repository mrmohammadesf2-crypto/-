package com.example

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

class NotificationBridge(private val activity: Activity) {

  companion object {
    private const val TAG = "NotificationBridge"
  }

  @JavascriptInterface
  fun checkPermissions(): String {
    val granted = isNotificationPermissionGranted()
    val status = if (granted) "granted" else "denied"
    Log.d(TAG, "checkPermissions: $status")
    return JSONObject().put("display", status).toString()
  }

  @JavascriptInterface
  fun requestPermissions(): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (!isNotificationPermissionGranted()) {
        activity.runOnUiThread {
          ActivityCompat.requestPermissions(
              activity,
              arrayOf(Manifest.permission.POST_NOTIFICATIONS),
              1001
          )
        }
      }
    }
    val granted = isNotificationPermissionGranted()
    val status = if (granted) "granted" else "denied"
    Log.d(TAG, "requestPermissions: $status")
    return JSONObject().put("display", status).toString()
  }

  @JavascriptInterface
  fun checkExactNotificationSetting(): String {
    var isGranted = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
      isGranted = alarmManager?.canScheduleExactAlarms() ?: true
    }
    val status = if (isGranted) "granted" else "denied"
    Log.d(TAG, "checkExactNotificationSetting: $status")
    return JSONObject().put("exact_alarm", status).toString()
  }

  @JavascriptInterface
  fun changeExactNotificationSetting() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        val intent =
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
              data = Uri.parse("package:${activity.packageName}")
            }
        activity.startActivity(intent)
      } catch (e: Exception) {
        Log.e(TAG, "Could not open exact alarm settings", e)
      }
    }
  }

  @JavascriptInterface
  fun getPending(): String {
    val ids = AlarmScheduler.getPendingIds(activity)
    val array = JSONArray()
    for (id in ids) {
      array.put(JSONObject().put("id", id))
    }
    return JSONObject().put("notifications", array).toString()
  }

  @JavascriptInterface
  fun cancel(optionsJson: String?) {
    if (optionsJson.isNullOrBlank()) return
    try {
      val obj = JSONObject(optionsJson)
      val notifications = obj.optJSONArray("notifications")
      if (notifications != null) {
        for (i in 0 until notifications.length()) {
          val item = notifications.getJSONObject(i)
          val id = item.getInt("id")
          AlarmScheduler.cancel(activity, id)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error cancelling notifications: $optionsJson", e)
    }
  }

  @JavascriptInterface
  fun schedule(optionsJson: String?) {
    if (optionsJson.isNullOrBlank()) return
    try {
      val obj = JSONObject(optionsJson)
      val notifications = obj.optJSONArray("notifications") ?: return

      for (i in 0 until notifications.length()) {
        val item = notifications.getJSONObject(i)
        val id = item.getInt("id")
        val title = item.optString("title", "یادآوری مدیریت")
        val body = item.optString("body", "موعد یادآوری فرا رسیده است")

        val scheduleObj = item.optJSONObject("schedule")
        val triggerTimeMs = parseTriggerTime(scheduleObj)

        if (triggerTimeMs > 0) {
          AlarmScheduler.schedule(activity, id, title, body, triggerTimeMs)
        } else {
          Log.w(TAG, "Invalid trigger time for notification id=$id")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error scheduling notifications: $optionsJson", e)
    }
  }

  @JavascriptInterface
  fun scheduleAlarmDirect(id: Int, title: String, body: String, triggerTimeMs: Long): Boolean {
    val now = System.currentTimeMillis()
    return if (triggerTimeMs > now) {
      AlarmScheduler.schedule(activity, id, title, body, triggerTimeMs)
      true
    } else {
      false
    }
  }

  private fun parseTriggerTime(scheduleObj: JSONObject?): Long {
    if (scheduleObj == null) return 0L

    val atVal = scheduleObj.opt("at") ?: return 0L

    if (atVal is Number) {
      return atVal.toLong()
    }

    val atStr = atVal.toString().trim()
    val asLong = atStr.toLongOrNull()
    if (asLong != null) {
      return asLong
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        return java.time.Instant.parse(atStr).toEpochMilli()
      } catch (_: Exception) {}
      try {
        return java.time.OffsetDateTime.parse(atStr).toInstant().toEpochMilli()
      } catch (_: Exception) {}
    }

    val formats =
        arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
    for (format in formats) {
      try {
        val sdf = SimpleDateFormat(format, Locale.US).apply {
          timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = sdf.parse(atStr)
        if (date != null) {
          return date.time
        }
      } catch (_: Exception) {}
    }

    return 0L
  }

  private fun isNotificationPermissionGranted(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(
          activity,
          Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }
}
