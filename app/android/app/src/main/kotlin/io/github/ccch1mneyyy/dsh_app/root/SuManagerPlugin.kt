package io.github.ccch1mneyyy.dsh_app.root

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Reports the device's root manager to Dart (MethodChannel `dsh/su`, lib/root/su_manager.dart).
 * Read-only detection: it never executes su. The privileged background path (`phone/background/`)
 * is the only thing that runs su, behind an explicit user action.
 */
class SuManagerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, CHANNEL).also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "detect" -> result.success(detect())
            else -> result.notImplemented()
        }
    }

    private fun detect(): Map<String, Any?> {
        val fileExists = { path: String -> File(path).exists() }
        val executable = { path: String -> File(path).canExecute() }
        val manager = SuManager.detect(fileExists, executable)
        return mapOf(
            "rooted" to (manager != SuManager.Manager.UNKNOWN || SuManager.rooted(executable)),
            "manager" to manager.label,
            "suPath" to SuManager.suPath(executable),
        )
    }

    companion object {
        const val CHANNEL = "dsh/su"
    }
}
