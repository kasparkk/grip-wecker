package de.kasparkk.gripwecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val i = Intent(c, AlarmService::class.java).setAction(AlarmService.ACT_START)
        intent.extras?.let { i.putExtras(it) }
        c.startForegroundService(i)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Scheduler.reschedule(c)
    }
}
