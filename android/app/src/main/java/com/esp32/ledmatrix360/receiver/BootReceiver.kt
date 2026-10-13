package com.esp32.ledmatrix360.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.esp32.ledmatrix360.service.BleForegroundService
import com.esp32.ledmatrix360.worker.WeatherTimeWorker

/**
 * Tự động khởi động lại BleForegroundService và Worker khi điện thoại khởi động lại
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("BootReceiver", "Thiết bị vừa khởi động lại ($action). Kích hoạt lại Service đèn...")
            BleForegroundService.startService(context)
            WeatherTimeWorker.schedule(context)
        }
    }
}
