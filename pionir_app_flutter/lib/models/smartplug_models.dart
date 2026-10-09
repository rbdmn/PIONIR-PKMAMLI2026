// Kontrak data bersama backend & frontend.
// Ubah file ini hanya lewat kesepakatan tim (pemilik: arsitek/integrator).

/// Jalur komunikasi aplikasi ke SmartPlug.
/// direct = HP langsung ke SmartPlug; server = lewat ServerSmartPlug (ESP32).
enum ConnectionMode { direct, server }

/// Status relay logis yang dilaporkan perangkat. unknown = belum terbaca.
enum RelayState { on, off, unknown }

/// SmartPlug yang sudah selesai ditambahkan dan tersimpan di HP.
class SmartPlug {
  const SmartPlug({
    required this.id,
    required this.name,
    required this.mode,
    this.ipAddress,
  });

  /// `SP-<STA_MAC>`, identitas permanen dari firmware.
  final String id;

  /// Nama yang diberikan pengguna, misalnya "Kulkas Dapur".
  final String name;

  final ConnectionMode mode;

  /// IP LAN terakhir; bisa berubah, jangan dipakai sebagai identitas.
  final String? ipAddress;
}

/// Satu pembacaan listrik. Energi sudah dalam kWh (firmware mengirim Wh).
class Measurement {
  const Measurement({
    required this.voltage,
    required this.current,
    required this.activePower,
    required this.apparentPower,
    required this.powerFactor,
    required this.energyKwh,
  });

  final double voltage; // V
  final double current; // A
  final double activePower; // W
  final double apparentPower; // VA
  final double powerFactor; // 0..1
  final double energyKwh; // kWh kumulatif
}

/// Kondisi live satu SmartPlug, dikirim berkala selama layar memantau.
class PlugReading {
  const PlugReading({
    required this.online,
    required this.relay,
    required this.measurement,
    required this.time,
  });

  /// false bila perangkat tidak menjawab pada polling terakhir.
  final bool online;

  final RelayState relay;

  /// null bila belum ada sampel yang segar (firmware `fresh=false`).
  final Measurement? measurement;

  final DateTime time;
}

/// Access point SmartPlug yang belum di-setup (SSID `SP-<unit_id>`).
class PlugAccessPoint {
  const PlugAccessPoint({
    required this.ssid,
    required this.unitId,
    required this.signalPercent,
  });

  final String ssid;
  final String unitId;
  final int signalPercent; // 0..100
}

/// Wi-Fi rumah hasil pindaian SmartPlug saat pairing.
class HomeWifi {
  const HomeWifi({
    required this.ssid,
    required this.signalPercent,
    required this.secured,
  });

  final String ssid;
  final int signalPercent; // 0..100
  final bool secured;
}

/// Satu-satunya jenis error yang dilempar repository ke UI.
/// Frontend menerjemahkan [code] menjadi pesan untuk pengguna.
class SmartPlugException implements Exception {
  const SmartPlugException(this.code, [this.message]);

  /// Kode yang dipakai:
  /// - `offline`              perangkat/server tidak menjawab
  /// - `timeout`              request melebihi batas waktu
  /// - `unauthorized`         token ditolak (perlu pairing ulang)
  /// - `permission_denied`    izin lokasi/Wi-Fi belum diberikan
  /// - `location_off`         layanan lokasi HP mati (scan Wi-Fi kosong)
  /// - `plug_not_found`       AP SmartPlug tidak terlihat / gagal disambung
  /// - `wifi_not_found`       Wi-Fi rumah tidak ditemukan oleh SmartPlug
  /// - `wifi_auth_failed`     password Wi-Fi rumah salah
  /// - `relay_rejected`       perintah relay ditolak/gagal dikonfirmasi
  /// - `unknown`              lainnya; lihat [message]
  final String code;

  /// Detail teknis untuk log/debug, bukan untuk ditampilkan apa adanya.
  final String? message;

  @override
  String toString() => 'SmartPlugException($code${message == null ? '' : ': $message'})';
}
