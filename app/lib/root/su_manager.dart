// Root manager detection (MethodChannel `dsh/su`, android/root/SuManagerPlugin.kt).
// Read-only: reports which of Magisk / KernelSU / APatch the device runs, without
// ever executing su. The privileged background path is the only thing that runs su.

import 'package:flutter/services.dart';

/// What [SuManager.detect] found.
class SuStatus {
  final bool rooted;
  final String manager;
  final String? suPath;

  const SuStatus({required this.rooted, required this.manager, this.suPath});

  /// True when a root manager or an executable su binary is present.
  bool get hasManager => manager != 'unknown';
}

abstract final class SuManager {
  static const _channel = MethodChannel('dsh/su');

  static Future<SuStatus> detect() async {
    final m = await _channel.invokeMapMethod<String, dynamic>('detect') ?? const {};
    return SuStatus(
      rooted: m['rooted'] == true,
      manager: m['manager'] as String? ?? 'unknown',
      suPath: m['suPath'] as String?,
    );
  }
}
