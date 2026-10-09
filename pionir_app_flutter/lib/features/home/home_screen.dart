import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/smartplug_repository.dart';
import '../../models/smartplug_models.dart';

// Layar contoh cara memakai kontrak. Frontend bebas mengganti tampilannya
// sesuai Figma; pola provider di bawah bisa ditiru untuk layar lain.

final devicesProvider = StreamProvider<List<SmartPlug>>(
  (ref) => ref.watch(smartPlugRepositoryProvider).watchDevices(),
);

final readingProvider = StreamProvider.autoDispose.family<PlugReading, String>(
  (ref, deviceId) => ref.watch(smartPlugRepositoryProvider).watchReading(deviceId),
);

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final devices = ref.watch(devicesProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('PIONIR')),
      body: devices.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Gagal memuat: $e')),
        data: (list) => list.isEmpty
            ? const Center(child: Text('Belum ada SmartPlug'))
            : ListView(children: [for (final d in list) _DeviceTile(device: d)]),
      ),
    );
  }
}

class _DeviceTile extends ConsumerWidget {
  const _DeviceTile({required this.device});

  final SmartPlug device;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reading = ref.watch(readingProvider(device.id)).value;
    final m = reading?.measurement;
    final subtitle = switch (reading) {
      null => 'Memuat...',
      PlugReading(online: false) => 'Offline',
      _ when m == null => 'Menunggu data',
      _ => '${m.activePower.toStringAsFixed(1)} W · '
          '${m.voltage.toStringAsFixed(1)} V · '
          '${m.energyKwh.toStringAsFixed(3)} kWh',
    };
    return ListTile(
      title: Text(device.name),
      subtitle: Text(subtitle),
      trailing: Switch(
        value: reading?.relay == RelayState.on,
        onChanged: reading?.online == true
            ? (on) async {
                try {
                  await ref.read(smartPlugRepositoryProvider).setRelay(device.id, on: on);
                } on SmartPlugException catch (e) {
                  if (context.mounted) {
                    ScaffoldMessenger.of(context)
                        .showSnackBar(SnackBar(content: Text('Gagal: ${e.code}')));
                  }
                }
              }
            : null,
      ),
    );
  }
}
