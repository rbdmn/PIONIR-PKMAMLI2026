import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/smartplug_models.dart';
import 'fake_smartplug_repository.dart';

/// Kontrak yang dipakai frontend. Backend membuat implementasi aslinya
/// (HTTP ke SmartPlug/ServerSmartPlug, pairing Wi-Fi, token, penyimpanan).
/// Semua kegagalan dilempar sebagai [SmartPlugException].
abstract class SmartPlugRepository {
  /// Daftar SmartPlug tersimpan; memancarkan ulang setiap ada perubahan.
  Stream<List<SmartPlug>> watchDevices();

  /// Pembacaan live satu perangkat. Polling berjalan selama stream didengar
  /// dan berhenti saat listener dibatalkan (layar ditutup).
  /// Perangkat tidak menjawab -> tetap emit dengan `online: false`, bukan error.
  Stream<PlugReading> watchReading(String deviceId);

  /// Selesai setelah perangkat mengonfirmasi state baru, bukan saat
  /// perintah sekadar diantrikan. Gagal -> `relay_rejected`/`offline`/`timeout`.
  Future<RelayState> setRelay(String deviceId, {required bool on});

  Future<void> renameDevice(String deviceId, String name);

  /// Hanya menghapus dari HP; tidak me-reset perangkat.
  Future<void> removeDevice(String deviceId);

  // --- Tambah SmartPlug baru (pairing lewat AP `SP-<unit_id>`) ---
  // Urutan: scanPlugs -> startPairing -> finishPairing.
  // cancelPairing boleh dipanggil kapan saja untuk membatalkan.

  /// Mencari SmartPlug yang belum di-setup di sekitar HP.
  Future<List<PlugAccessPoint>> scanPlugs();

  /// Menyambung HP ke AP SmartPlug, lalu mengembalikan daftar Wi-Fi rumah
  /// yang terlihat oleh SmartPlug.
  Future<List<HomeWifi>> startPairing(PlugAccessPoint plug);

  /// Mengirim Wi-Fi rumah ke SmartPlug, menunggu tersambung, mengembalikan
  /// HP ke Wi-Fi rumah, lalu menyimpan perangkat. Bisa memakan ~30-60 detik.
  Future<SmartPlug> finishPairing({
    required String ssid,
    required String password,
    required String name,
  });

  Future<void> cancelPairing();
}

/// Titik tukar data palsu <-> asli. Saat implementasi backend siap,
/// cukup ganti baris di bawah; layar frontend tidak perlu diubah.
final smartPlugRepositoryProvider = Provider<SmartPlugRepository>(
  (ref) => FakeSmartPlugRepository(),
);
