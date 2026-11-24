// OTG USB host access (MethodChannel `dsh/usb`, android/usb/UsbHostPlugin.kt):
// enumerate what is plugged into the phone, ask for permission, and read/write a
// device's bulk endpoints (serial adapters, storage, simple HID devices).

import 'dart:typed_data';

import 'package:flutter/services.dart';

/// One USB device the phone sees over OTG.
class UsbDeviceInfo {
  final int id;
  final int vendorId;
  final int productId;
  final int deviceClass;
  final String? deviceName;
  final String? manufacturer;
  final String? serial;
  final bool hasPermission;

  const UsbDeviceInfo({
    required this.id,
    required this.vendorId,
    required this.productId,
    required this.deviceClass,
    this.deviceName,
    this.manufacturer,
    this.serial,
    required this.hasPermission,
  });

  factory UsbDeviceInfo.fromMap(Map<Object?, Object?> m) => UsbDeviceInfo(
        id: (m['id'] as num?)?.toInt() ?? -1,
        vendorId: (m['vendorId'] as num?)?.toInt() ?? 0,
        productId: (m['productId'] as num?)?.toInt() ?? 0,
        deviceClass: (m['deviceClass'] as num?)?.toInt() ?? 0,
        deviceName: m['deviceName'] as String?,
        manufacturer: m['manufacturer'] as String?,
        serial: m['serial'] as String?,
        hasPermission: m['hasPermission'] == true,
      );

  /// `vendorId:productId` in the USB `hhhh:hhhh` convention, for logging.
  String get vidPid =>
      '${vendorId.toRadixString(16).padLeft(4, '0')}:${productId.toRadixString(16).padLeft(4, '0')}';
}

/// A bulk read: [data] plus how many bytes the device actually returned.
class UsbRead {
  final Uint8List data;
  final int read;
  const UsbRead(this.data, this.read);
}

/// Result codes both [UsbHost.open] and transfers can surface, kept as strings so the
/// Dart side never parses a native exception.
abstract final class UsbErrors {
  static const notFound = 'notFound';
  static const noPermission = 'noPermission';
  static const openFailed = 'openFailed';
  static const notOpen = 'notOpen';
  static const transferFailed = 'transferFailed';
  static const detached = 'detached';
}

abstract final class UsbHost {
  static const _channel = MethodChannel('dsh/usb');

  /// Every USB device currently attached, without asking for permission.
  static Future<List<UsbDeviceInfo>> list() async {
    final raw = await _channel.invokeListMethod<dynamic>('list') ?? const [];
    return raw
        .whereType<Map<Object?, Object?>>()
        .map(UsbDeviceInfo.fromMap)
        .toList();
  }

  /// Asks the person to grant access to one device; true once granted.
  static Future<bool> requestPermission(int deviceId) async {
    final r = await _channel
        .invokeMapMethod<String, dynamic>('requestPermission', {'deviceId': deviceId});
    return r?['granted'] == true;
  }

  /// Opens a device and claims its bulk endpoints. The [error] field names a
  /// [UsbErrors] code when it fails.
  static Future<({bool opened, String? error})> open(int deviceId) async {
    final r = await _channel
        .invokeMapMethod<String, dynamic>('open', {'deviceId': deviceId});
    return (opened: r?['opened'] == true, error: r?['error'] as String?);
  }

  static Future<void> close(int deviceId) =>
      _channel.invokeMethod<void>('close', {'deviceId': deviceId});

  static Future<UsbRead> read(int deviceId, {int maxSize = 4096}) async {
    final r = await _channel.invokeMapMethod<String, dynamic>(
        'read', {'deviceId': deviceId, 'maxSize': maxSize});
    final data = r?['data'];
    final bytes = data is List ? Uint8List.fromList(data.cast<int>()) : Uint8List(0);
    return UsbRead(bytes, (r?['read'] as num?)?.toInt() ?? 0);
  }

  /// Writes bytes to the device's bulk OUT endpoint; returns how many went out
  /// (-1 with a [UsbErrors] code when the transfer failed).
  static Future<int> write(int deviceId, Uint8List data) async {
    final r = await _channel
        .invokeMapMethod<String, dynamic>('write', {'deviceId': deviceId, 'data': data});
    return (r?['written'] as num?)?.toInt() ?? -1;
  }
}
