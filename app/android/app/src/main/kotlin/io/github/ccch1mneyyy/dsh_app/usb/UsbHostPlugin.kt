package io.github.ccch1mneyyy.dsh_app.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * OTG USB host: enumerate what is plugged into the phone, ask the system for access, and
 * read/write a device's bulk endpoints. MethodChannel `dsh/usb` (lib/usb/usb_host.dart).
 *
 * - `list` → [{id, vendorId, productId, deviceClass, deviceName, manufacturer, serial, hasPermission}]
 * - `requestPermission` (deviceId) → {granted} (resolved by [UsbHostReceiver])
 * - `open` (deviceId) → {opened}
 * - `close` (deviceId)
 * - `read` (deviceId, maxSize) → {data, read}
 * - `write` (deviceId, data) → {written}
 *
 * Read/write use the first bulk IN/OUT endpoints the device offers: serial adapters (CDC-ACM,
 * FTDI, CH340), storage and simple HID devices are the common cases. Access is per-device and
 * granted by the person through the system dialog; this plugin never touches a device without
 * that grant, and every transfer failure returns a stable error instead of throwing across the
 * channel.
 */
class UsbHostPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null
    private lateinit var context: Context
    private lateinit var usbManager: UsbManager
    private val links = ConcurrentHashMap<Int, UsbLink>()
    private val pending = ConcurrentHashMap<Int, MethodChannel.Result>()
    private val permissionListener: (UsbDevice, Boolean) -> Unit = { device, granted -> resolvePermission(device, granted) }
    private val detachListener: (UsbDevice) -> Unit = { device -> drop(device.deviceId) }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        channel = MethodChannel(binding.binaryMessenger, CHANNEL).also { it.setMethodCallHandler(this) }
        UsbHostBus.addPermissionListener(permissionListener)
        UsbHostBus.addDetachListener(detachListener)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        UsbHostBus.removePermissionListener(permissionListener)
        UsbHostBus.removeDetachListener(detachListener)
        channel?.setMethodCallHandler(null)
        channel = null
        links.values.forEach { runCatching { it.connection.close() } }
        links.clear()
        pending.clear()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "list" -> result.success(deviceList())
            "requestPermission" -> requestPermission(call.argument<Int>("deviceId"), result)
            "open" -> result.success(open(call.argument<Int>("deviceId")))
            "close" -> close(call.argument<Int>("deviceId"), result)
            "read" -> read(call.argument<Int>("deviceId"), call.argument<Int>("maxSize"), result)
            "write" -> write(call.argument<Int>("deviceId"), call.argument<ByteArray>("data"), result)
            else -> result.notImplemented()
        }
    }

    private fun deviceList(): List<Map<String, Any?>> = usbManager.deviceList.values.map { device ->
        mapOf(
            "id" to device.deviceId,
            "vendorId" to device.vendorId,
            "productId" to device.productId,
            "deviceClass" to device.deviceClass,
            "deviceName" to device.deviceName,
            "manufacturer" to device.manufacturerName,
            "serial" to device.serialNumber,
            "hasPermission" to usbManager.hasPermission(device),
        )
    }

    private fun findDevice(deviceId: Int): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }

    private fun requestPermission(deviceId: Int?, result: MethodChannel.Result) {
        val device = deviceId?.let { findDevice(it) }
        if (device == null) {
            result.success(mapOf("granted" to false, "error" to "notFound"))
            return
        }
        if (usbManager.hasPermission(device)) {
            result.success(mapOf("granted" to true))
            return
        }
        pending[device.deviceId] = result
        val intent = Intent(UsbHostReceiver.ACTION_USB_PERMISSION)
            .setClassName(context, UsbHostReceiver::class.java.name)
        val pi = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pi)
    }

    private fun resolvePermission(device: UsbDevice, granted: Boolean) {
        pending.remove(device.deviceId)?.success(mapOf("granted" to granted))
    }

    private fun open(deviceId: Int?): Map<String, Any?> {
        if (deviceId == null) return failure("badArguments")
        links[deviceId]?.let { return mapOf("opened" to true) }
        val device = findDevice(deviceId) ?: return failure("notFound")
        if (!usbManager.hasPermission(device)) return failure("noPermission")
        val connection = usbManager.openDevice(device) ?: return failure("openFailed")
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
        links[deviceId] = UsbLink(connection, inEndpoint, outEndpoint)
        return mapOf("opened" to true)
    }

    private fun close(deviceId: Int?, result: MethodChannel.Result) {
        if (deviceId != null) drop(deviceId)
        result.success(null)
    }

    private fun read(deviceId: Int?, maxSize: Int?, result: MethodChannel.Result) {
        val link = deviceId?.let { links[it] }
        val endpoint = link?.inEndpoint
        if (link == null || endpoint == null) {
            result.success(mapOf("data" to emptyList<Int>(), "error" to "notOpen"))
            return
        }
        val size = (maxSize ?: DEFAULT_READ_SIZE).coerceIn(1, MAX_READ_SIZE)
        val buffer = ByteArray(size)
        val n = link.connection.bulkTransfer(endpoint, buffer, size, TRANSFER_TIMEOUT_MS)
        if (n < 0) {
            result.success(mapOf("data" to emptyList<Int>(), "error" to "transferFailed"))
        } else {
            result.success(mapOf("data" to buffer.copyOf(n).map { it.toInt() and 0xff }, "read" to n))
        }
    }

    private fun write(deviceId: Int?, data: ByteArray?, result: MethodChannel.Result) {
        val link = deviceId?.let { links[it] }
        val endpoint = link?.outEndpoint
        if (link == null || endpoint == null) {
            result.success(mapOf("written" to -1, "error" to "notOpen"))
            return
        }
        val bytes = data ?: ByteArray(0)
        if (bytes.isEmpty()) {
            result.success(mapOf("written" to 0))
            return
        }
        val n = link.connection.bulkTransfer(endpoint, bytes, bytes.size, TRANSFER_TIMEOUT_MS)
        result.success(mapOf("written" to n))
    }

    private fun drop(deviceId: Int) {
        links.remove(deviceId)?.let { runCatching { it.connection.close() } }
        pending.remove(deviceId)?.success(mapOf("granted" to false, "error" to "detached"))
    }

    private fun failure(code: String): Map<String, Any?> = mapOf("opened" to false, "error" to code)

    companion object {
        const val CHANNEL = "dsh/usb"
        const val DEFAULT_READ_SIZE = 4096
        const val MAX_READ_SIZE = 64 * 1024
        const val TRANSFER_TIMEOUT_MS = 2000
    }
}

/** An opened device and the endpoints its transfers use. */
private class UsbLink(
    val connection: UsbDeviceConnection,
    val inEndpoint: UsbEndpoint?,
    val outEndpoint: UsbEndpoint?,
)
