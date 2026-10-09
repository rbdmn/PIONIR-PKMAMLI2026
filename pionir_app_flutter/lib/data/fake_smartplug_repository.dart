import 'dart:async';
import 'dart:math';

import '../models/smartplug_models.dart';
import 'smartplug_repository.dart';

/// Data palsu agar frontend bisa bekerja tanpa alat dan tanpa backend.
/// Sengaja menyediakan kondisi yang perlu didesain:
/// - "Kulkas Dapur": online, nilai naik-turun, relay bisa ON/OFF.
/// - "Charger Laptop": selalu offline.
/// - Pairing dengan password "salah" -> error `wifi_auth_failed`.
class FakeSmartPlugRepository implements SmartPlugRepository {
  FakeSmartPlugRepository({this.delay = const Duration(milliseconds: 700)});

  /// Jeda simulasi jaringan; test bisa memperkecilnya.
  final Duration delay;

  static const _offlineId = 'SP-AABBCCDDEE02';

  final _random = Random();
  final _changes = StreamController<List<SmartPlug>>.broadcast();
  final List<SmartPlug> _devices = [
    const SmartPlug(
      id: 'SP-AABBCCDDEE01',
      name: 'Kulkas Dapur',
      mode: ConnectionMode.direct,
      ipAddress: '192.168.1.25',
    ),
    const SmartPlug(
      id: _offlineId,
      name: 'Charger Laptop',
      mode: ConnectionMode.direct,
      ipAddress: '192.168.1.26',
    ),
  ];
  final Map<String, RelayState> _relay = {};
  final Map<String, double> _energyKwh = {};

  void _emitDevices() => _changes.add(List.unmodifiable(_devices));

  @override
  Stream<List<SmartPlug>> watchDevices() async* {
    yield List.unmodifiable(_devices);
    yield* _changes.stream;
  }

  @override
  Stream<PlugReading> watchReading(String deviceId) async* {
    while (true) {
      yield _reading(deviceId);
      await Future<void>.delayed(const Duration(seconds: 1));
    }
  }

  PlugReading _reading(String deviceId) {
    final now = DateTime.now();
    if (deviceId == _offlineId) {
      return PlugReading(online: false, relay: RelayState.unknown, measurement: null, time: now);
    }
    final relay = _relay[deviceId] ?? RelayState.on;
    final on = relay == RelayState.on;
    final voltage = 218 + _random.nextDouble() * 6;
    final power = on ? 95 + _random.nextDouble() * 20 : 0.0;
    const pf = 0.92;
    final energy = (_energyKwh[deviceId] ?? 12.345) + power / 3600 / 1000;
    _energyKwh[deviceId] = energy;
    return PlugReading(
      online: true,
      relay: relay,
      measurement: Measurement(
        voltage: voltage,
        current: power / (voltage * pf),
        activePower: power,
        apparentPower: power / pf,
        powerFactor: on ? pf : 0,
        energyKwh: energy,
      ),
      time: now,
    );
  }

  @override
  Future<RelayState> setRelay(String deviceId, {required bool on}) async {
    await Future<void>.delayed(delay);
    if (deviceId == _offlineId) throw const SmartPlugException('offline');
    final state = on ? RelayState.on : RelayState.off;
    _relay[deviceId] = state;
    return state;
  }

  @override
  Future<void> renameDevice(String deviceId, String name) async {
    final i = _devices.indexWhere((d) => d.id == deviceId);
    if (i < 0) return;
    final d = _devices[i];
    _devices[i] = SmartPlug(id: d.id, name: name, mode: d.mode, ipAddress: d.ipAddress);
    _emitDevices();
  }

  @override
  Future<void> removeDevice(String deviceId) async {
    _devices.removeWhere((d) => d.id == deviceId);
    _emitDevices();
  }

  @override
  Future<List<PlugAccessPoint>> scanPlugs() async {
    await Future<void>.delayed(delay * 2);
    return const [
      PlugAccessPoint(ssid: 'SP-7K2M9QX4', unitId: '7K2M9QX4', signalPercent: 82),
      PlugAccessPoint(ssid: 'SP-H3TR8WZ6', unitId: 'H3TR8WZ6', signalPercent: 41),
    ];
  }

  @override
  Future<List<HomeWifi>> startPairing(PlugAccessPoint plug) async {
    await Future<void>.delayed(delay * 2);
    return const [
      HomeWifi(ssid: 'WiFi-Rumah', signalPercent: 90, secured: true),
      HomeWifi(ssid: 'WiFi-Tamu', signalPercent: 55, secured: false),
    ];
  }

  @override
  Future<SmartPlug> finishPairing({
    required String ssid,
    required String password,
    required String name,
  }) async {
    await Future<void>.delayed(delay * 4);
    if (password == 'salah') throw const SmartPlugException('wifi_auth_failed');
    final device = SmartPlug(
      id: 'SP-AABBCCDDEE${(_devices.length + 1).toString().padLeft(2, '0')}',
      name: name,
      mode: ConnectionMode.direct,
      ipAddress: '192.168.1.${30 + _devices.length}',
    );
    _devices.add(device);
    _emitDevices();
    return device;
  }

  @override
  Future<void> cancelPairing() async {}
}
