// BootReceiver.kt
package com.datafy.foottraffic.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.datafy.foottraffic.service.CounterService
import timber.log.Timber

/**
 * BroadcastReceiver that starts the CounterService when device boots up
 */
//class BootReceiver : BroadcastReceiver() {
//
//    override fun onReceive(context: Context, intent: Intent) {
//        // Check if this is a boot completed action
//        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
//            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
//
//            Timber.d("Boot completed, starting CounterService")
//
//            // Create intent to start service
//            val serviceIntent = Intent(context, CounterService::class.java)
//
//            // Start as foreground service for Android O+
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                context.startForegroundService(serviceIntent)
//            } else {
//                context.startService(serviceIntent)
//            }
//
//            Timber.d("CounterService start command sent")
//        }
//    }
//}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {

            Timber.d("Boot completed; posting start notification")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                StartPromptNotification.show(context) // see next file
            }
        }
    }
}