import 'package:flutter_test/flutter_test.dart';
import 'package:pionir_app_flutter/data/fake_smartplug_repository.dart';
import 'package:pionir_app_flutter/models/smartplug_models.dart';

void main() {
  final repo = FakeSmartPlugRepository(delay: Duration.zero);

  test('daftar perangkat palsu tersedia', () async {
    final devices = await repo.watchDevices().first;
    expect(devices, hasLength(2));
  });

  test('relay bisa dimatikan dan pembacaan ikut berubah', () async {
    const id = 'SP-AABBCCDDEE01';
    expect(await repo.setRelay(id, on: false), RelayState.off);
    final reading = await repo.watchReading(id).first;
    expect(reading.relay, RelayState.off);
    expect(reading.measurement!.activePower, 0);
  });

  test('perangkat offline melempar SmartPlugException', () async {
    expect(
      () => repo.setRelay('SP-AABBCCDDEE02', on: true),
      throwsA(isA<SmartPlugException>().having((e) => e.code, 'code', 'offline')),
    );
  });
}
