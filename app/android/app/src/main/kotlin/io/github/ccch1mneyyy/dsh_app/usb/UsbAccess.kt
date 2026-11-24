package io.github.ccch1mneyyy.dsh_app.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The agent-facing OTG USB surface. The phone tool calls [list]/[read]/[write] against devices
 * plugged into the phone. Read/write target the first bulk IN/OUT endpoints the device offers
 * (serial adapters such as CDC-ACM, FTDI, CH340, plus storage and simple HID). Access is per
 * device and granted by the person through the system dialog; the first read/write on an
 * unpermitted device waits (up to [UsbHostAccess.PERMISSION_WAIT_SECONDS]) for that answer.
 */
interface UsbAccess {
    fun list(): List<Map<String, Any?>>
    fun read(deviceId: Int, maxSize: Int): Map<String, Any?>
    fun write(deviceId: Int, data: ByteArray): Map<String, Any?>
}

/** Android [UsbAccess]; one instance per process, held by [io.github.ccch1mneyyy.dsh_app.phone.PhoneControl]. */
class UsbHostAccess(private val context: Context) : UsbAccess {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val links = ConcurrentHashMap<Int, Link>()

    private class Link(val connection: UsbDeviceConnection, val inEndpoint: UsbEndpoint?, val outEndpoint: UsbEndpoint?)

    init {
        // Drop the open connection the moment the device is unplugged, so a later call re-enumerates.
        UsbHostBus.addDetachListener { device -> drop(device.deviceId) }
    }

    override fun list(): List<Map<String, Any?>> = usbManager.deviceList.values.map { device ->
        mapOf(
            "id" to device.deviceId,
            "vendorId" to device.vendorId,
            "productId" to device.productId,
            "deviceClass" to device.deviceClass,
            "name" to device.deviceName,
            "manufacturer" to device.manufacturerName,
            "serial" to device.serialNumber,
            "hasPermission" to usbManager.hasPermission(device),
        )
    }

    private fun find(deviceId: Int): UsbDevice? = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }

    override fun read(deviceId: Int, maxSize: Int): Map<String, Any?> {
        val device = find(deviceId) ?: return mapOf("error" to "notFound")
        if (!ensurePermission(device)) return mapOf("error" to "noPermission")
        val link = open(device) ?: return mapOf("error" to "openFailed")
        val endpoint = link.inEndpoint ?: return mapOf("error" to "noEndpoint")
        val size = maxSize.coerceIn(1, MAX_READ)
        val buffer = ByteArray(size)
        val n = link.connection.bulkTransfer(endpoint, buffer, size, TRANSFER_TIMEOUT_MS)
        return if (n < 0) mapOf("error" to "transferFailed")
        else mapOf("data" to buffer.copyOf(n).map { it.toInt() and 0xff }, "read" to n)
    }

    override fun write(deviceId: Int, data: ByteArray): Map<String, Any?> {
        val device = find(deviceId) ?: return mapOf("error" to "notFound")
        if (!ensurePermission(device)) return mapOf("error" to "noPermission")
        val link = open(device) ?: return mapOf("error" to "openFailed")
        val endpoint = link.outEndpoint ?: return mapOf("error" to "noEndpoint")
        if (data.isEmpty()) return mapOf("written" to 0)
        val n = link.connection.bulkTransfer(endpoint, data, data.size, TRANSFER_TIMEOUT_MS)
        return mapOf("written" to n)
    }

    private fun open(device: UsbDevice): Link? {
        links[device.deviceId]?.let { return it }
        val connection = usbManager.openDevice(device) ?: return null
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            runCatching { connection.claimInterface(iface, true) }
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> if (inEndpoint == null) inEndpoint = endpoint
                    UsbConstants.USB_DIR_OUT -> if (outEndpoint == null) outEndpoint = endpoint
                }
            }
        }
        val link = Link(connection, inEndpoint, outEndpoint)
        links[device.deviceId] = link
        return link
    }

    /** Blocks (up to [PERMISSION_WAIT_SECONDS]) while the person answers the system dialog. */
    private fun ensurePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val granted = AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)
        val listener: (UsbDevice, Boolean) -> Unit = { d, ok ->
            if (d.deviceId == device.deviceId) { granted.set(ok); latch.countDown() }
        }
        UsbHostBus.addPermissionListener(listener)
        try {
            val intent = Intent(UsbHostReceiver.ACTION_USB_PERMISSION)
                .setClassName(context, UsbHostReceiver::class.java.name)
            val pi = PendingIntent.getBroadcast(context, device.deviceId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pi)
            return try {
                latch.await(PERMISSION_WAIT_SECONDS, TimeUnit.SECONDS) && granted.get() == true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        } catch (_: Exception) {
            return false
        } finally {
            UsbHostBus.removePermissionListener(listener)
        }
    }

    private fun drop(deviceId: Int) {
        links.remove(deviceId)?.let { runCatching { it.connection.close() } }
    }

    companion object {
        const val MAX_READ = 64 * 1024
        const val TRANSFER_TIMEOUT_MS = 2000
        const val PERMISSION_WAIT_SECONDS = 30L
    }
}
