package io.github.ccch1mneyyy.dsh_app.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives USB hot-plug and the per-device permission result the system delivers to the app's
 * [android.app.PendingIntent]. It only forwards to the in-process listeners ([UsbHostBus]); it
 * never opens or touches a device connection itself.
 */
class UsbHostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice ?: return
        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                UsbHostBus.dispatchPermission(device, granted)
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> UsbHostBus.dispatchAttach(device)
            UsbManager.ACTION_USB_DEVICE_DETACHED -> UsbHostBus.dispatchDetach(device)
        }
    }

    companion object {
        /** Our own result action, so it can't be confused with a system broadcast. */
        const val ACTION_USB_PERMISSION = "io.github.ccch1mneyyy.dsh_app.USB_PERMISSION"
    }
}

/**
 * The in-process hand-off point from [UsbHostReceiver] to anyone speaking USB ([UsbHostPlugin]
 * over MethodChannel and [UsbHostAccess] for the agent's phone tool). Listeners fan out so the
 * two owners never clobber each other's permission result.
 */
object UsbHostBus {
    private val permissionListeners = CopyOnWriteArrayList<(UsbDevice, Boolean) -> Unit>()
    private val attachListeners = CopyOnWriteArrayList<(UsbDevice) -> Unit>()
    private val detachListeners = CopyOnWriteArrayList<(UsbDevice) -> Unit>()

    fun addPermissionListener(listener: (UsbDevice, Boolean) -> Unit) { permissionListeners += listener }
    fun removePermissionListener(listener: (UsbDevice, Boolean) -> Unit) { permissionListeners -= listener }
    fun addAttachListener(listener: (UsbDevice) -> Unit) { attachListeners += listener }
    fun removeAttachListener(listener: (UsbDevice) -> Unit) { attachListeners -= listener }
    fun addDetachListener(listener: (UsbDevice) -> Unit) { detachListeners += listener }
    fun removeDetachListener(listener: (UsbDevice) -> Unit) { detachListeners -= listener }

    fun dispatchPermission(device: UsbDevice, granted: Boolean) = permissionListeners.forEach { it(device, granted) }
    fun dispatchAttach(device: UsbDevice) = attachListeners.forEach { it(device) }
    fun dispatchDetach(device: UsbDevice) = detachListeners.forEach { it(device) }
}
