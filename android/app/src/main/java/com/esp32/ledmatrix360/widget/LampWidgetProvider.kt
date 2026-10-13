package com.esp32.ledmatrix360.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.esp32.ledmatrix360.MainActivity
import com.esp32.ledmatrix360.R
import com.esp32.ledmatrix360.ble.BleManager

class LampWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_POWER = "com.esp32.ledmatrix360.TOGGLE_POWER"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_POWER) {
            val ble = BleManager.getInstance(context)
            if (ble.isConnected.value) {
                val curMode = ble.telemetryState.value.mode
                if (curMode == 9) {
                    ble.setEffect(1)
                } else {
                    ble.setEffect(9)
                }
            } else {
                ble.startScan()
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_lamp_control)

        // Click mở app
        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_container, openAppPendingIntent)

        // Click bật/tắt nguồn
        val toggleIntent = Intent(context, LampWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_POWER
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_widget_power, togglePendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
