package com.example.bus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.king.app.updater.AppUpdater
import org.json.JSONObject

object UpdateChecker {

    enum class Status { UPDATE, UP_TO_DATE, ERROR }

    private const val REPO_API = "https://api.github.com/repos/Reit437/Bus/releases/latest"
    private const val APK_NAME = "app-debug.apk"

    fun check(context: Context, onResult: (Status, String?) -> Unit) {
        Thread {
            try {
                val conn = java.net.URL(REPO_API).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Bus-App")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val response = conn.inputStream.bufferedReader().readText()
                val latestTag = JSONObject(response).getString("tag_name").removePrefix("v")
                val current = BuildConfig.VERSION_NAME.removePrefix("v")

                Log.d("UpdateCheck", "GitHub: $latestTag, local: $current")

                Handler(Looper.getMainLooper()).post {
                    if (latestTag != current) {
                        val apkUrl = "https://github.com/Reit437/Bus/releases/download/$latestTag/$APK_NAME"
                        Log.d("UpdateCheck", "update: $apkUrl")
                        AppUpdater(context, apkUrl).start()
                        onResult(Status.UPDATE, latestTag)
                    } else {
                        onResult(Status.UP_TO_DATE, current)
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateCheck", "err: ${e.message}", e)
                Handler(Looper.getMainLooper()).post { onResult(Status.ERROR, null) }
            }
        }.start()
    }
}