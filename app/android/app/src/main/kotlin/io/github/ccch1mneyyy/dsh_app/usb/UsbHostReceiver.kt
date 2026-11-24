package io.github.ccch1mneyyy.dsh_app.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Receives USB hot-plug and the per-device permission result the system delivers to the app's
 * [android.app.PendingIntent]. It only forwards to an in-process listener ([UsbHostBus]); it
 * never opens or touches a device connection itself.
 */
class UsbHostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice ?: return
        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                UsbHostBus.permissionListener?.invoke(device, granted)
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> UsbHostBus.attachListener?.invoke(device)
            UsbManager.ACTION_USB_DEVICE_DETACHED -> UsbHostBus.detachListener?.invoke(device)
        }
    }

    companion object {
        /** Our own result action, so it can't be confused with a system broadcast. */
        const val ACTION_USB_PERMISSION = "io.github.ccch1mneyyy.dsh_app.USB_PERMISSION"
    }
}

/** The single in-process hand-off point from [UsbHostReceiver] to [UsbHostPlugin]. */
object UsbHostBus {
    @Volatile var permissionListener: ((UsbDevice, Boolean) -> Unit)? = null
    @Volatile var attachListener: ((UsbDevice) -> Unit)? = null
    @Volatile var detachListener: ((UsbDevice) -> Unit)? = null
}
