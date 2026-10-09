import 'dart:convert';

import 'package:dio/dio.dart';

import '../models/smartplug_models.dart';

/// Hasil `GET /api/v1/pair/info`.
class PairInfo {
  const PairInfo({
    required this.deviceId,
    required this.staMac,
    required this.pairingToken,
    required this.tokenExpiresInS,
  });

  final String deviceId;
  final String staMac;

  /// Wajib dikirim sebagai `X-Pairing-Token`; berlaku 5 menit.
  final String pairingToken;
  final int tokenExpiresInS;
}

enum PairState { connecting, connected, failed }

/// Hasil `GET /api/v1/pair/status`.
class PairStatus {
  const PairStatus({
    required this.state,
    this.deviceId,
    this.lanIp,
    this.ownerToken,
    this.failureReason,
  });

  final PairState state;
  final String? deviceId;
  final String? lanIp;

  /// Hanya ada saat [PairState.connected]. Rahasia: simpan, jangan di-log.
  final String? ownerToken;

  /// Kode firmware saat gagal, mis. `wifi_authentication_failed`, `wifi_not_found`.
  final String? failureReason;
}

/// Klien HTTP untuk pairing API SmartPlug (design.md "Pairing API").
/// Semua kegagalan dilempar sebagai [SmartPlugException] dengan kode dari firmware
/// (mis. `pairing_closed`, `invalid_pairing_token`) atau `timeout`/`offline`.
class PairingApi {
  /// [baseUrl] diganti ke `http://<lan_ip>` untuk cek status setelah SmartPlug
  /// pindah ke Wi-Fi rumah.
  PairingApi({Dio? dio, this.baseUrl = apBaseUrl})
      : _dio = dio ??
            Dio(BaseOptions(
              connectTimeout: _timeout,
              sendTimeout: _timeout,
              receiveTimeout: _timeout,
            ));

  /// Alamat SmartPlug saat HP tersambung ke AP `SP-<unit_id>`.
  static const apBaseUrl = 'http://192.168.4.1';

  static const _timeout = Duration(seconds: 8);

  /// Scan Wi-Fi ESP8266 mengunjungi semua channel, jadi diberi waktu lebih lama.
  static const _scanTimeout = Duration(seconds: 20);

  final Dio _dio;
  final String baseUrl;

  Future<PairInfo> getPairInfo() async {
    final json = await _send('GET', '/api/v1/pair/info');
    if (json['product'] != 'smartplug' || json['protocol'] != 'pairing-v1') {
      throw SmartPlugException('unsupported_device', '${json['product']}/${json['protocol']}');
    }
    return PairInfo(
      deviceId: json['device_id'] as String,
      staMac: json['sta_mac'] as String,
      pairingToken: json['pairing_token'] as String,
      tokenExpiresInS: (json['token_expires_in_s'] as num).toInt(),
    );
  }

  /// Wi-Fi rumah yang terlihat oleh SmartPlug (bukan oleh HP).
  Future<List<HomeWifi>> scanWifi(String pairingToken) async {
    final json = await _send(
      'POST',
      '/api/v1/pair/scan-wifi',
      pairingToken: pairingToken,
      timeout: _scanTimeout,
    );
    return [
      for (final n in (json['networks'] as List).cast<Map<String, dynamic>>())
        HomeWifi(
          ssid: n['ssid'] as String,
          signalPercent: rssiToPercent((n['rssi'] as num).toInt()),
          secured: n['security'] != 'open',
        ),
    ];
  }

  /// Mengirim Wi-Fi rumah ke SmartPlug (mode Direct). Mengembalikan `configuration_id`
  /// untuk [getStatus]. Password harus kosong (Wi-Fi open) atau 8–63 karakter.
  Future<String> configureDirect({
    required String pairingToken,
    required String ssid,
    required String password,
  }) async {
    final json = await _send(
      'POST',
      '/api/v1/pair/configure',
      pairingToken: pairingToken,
      body: {
        'ssid': ssid,
        'password': password,
        'connection_profile': {'type': 'direct'},
      },
    );
    return json['configuration_id'] as String;
  }

  /// Dipanggil tiap 1 detik setelah [configureDirect] sampai connected/failed.
  /// Setelah SmartPlug pindah ke Wi-Fi rumah, request lewat AP bisa gagal sesaat;
  /// itu belum berarti pairing gagal (lanjutkan cek via IP LAN dengan token yang sama).
  Future<PairStatus> getStatus({
    required String pairingToken,
    required String configurationId,
  }) async {
    final json = await _send(
      'GET',
      '/api/v1/pair/status',
      pairingToken: pairingToken,
      query: {'configuration_id': configurationId},
    );
    final state = switch (json['state']) {
      'connected' => PairState.connected,
      'connecting' => PairState.connecting,
      _ => PairState.failed,
    };
    return PairStatus(
      state: state,
      deviceId: json['device_id'] as String?,
      lanIp: json['lan_ip'] as String?,
      ownerToken: json['owner_token'] as String?,
      failureReason: json['reason'] as String?,
    );
  }

  Future<Map<String, dynamic>> _send(
    String method,
    String path, {
    String? pairingToken,
    Map<String, dynamic>? query,
    Object? body,
    Duration? timeout,
  }) async {
    final Response<String> response;
    try {
      response = await _dio.request<String>(
        '$baseUrl$path',
        data: body == null ? null : jsonEncode(body),
        queryParameters: query,
        options: Options(
          method: method,
          headers: {
            'X-Pairing-Token': ?pairingToken,
            if (body != null) Headers.contentTypeHeader: Headers.jsonContentType,
          },
          responseType: ResponseType.plain,
          receiveTimeout: timeout,
          validateStatus: (_) => true, // status non-2xx dibaca sendiri di bawah
        ),
      );
    } on DioException catch (e) {
      throw SmartPlugException(_isTimeout(e) ? 'timeout' : 'offline', e.message);
    }

    final status = response.statusCode ?? 0;
    final json = _decode(response.data);
    if (status < 200 || status >= 300) {
      throw SmartPlugException(_errorCode(json) ?? 'http_$status', 'HTTP $status');
    }
    if (json == null) throw const SmartPlugException('unknown', 'respons bukan JSON');
    final version = json['api_version'];
    if (version is String && !version.startsWith('1.')) {
      throw SmartPlugException('unsupported_device', 'api_version $version');
    }
    return json;
  }

  static bool _isTimeout(DioException e) =>
      e.type == DioExceptionType.connectionTimeout ||
      e.type == DioExceptionType.sendTimeout ||
      e.type == DioExceptionType.receiveTimeout;

  static Map<String, dynamic>? _decode(String? text) {
    if (text == null || text.isEmpty) return null;
    try {
      final value = jsonDecode(text);
      return value is Map<String, dynamic> ? value : null;
    } on FormatException {
      return null;
    }
  }

  /// Firmware saat ini mengirim `{"error":"kode"}`; design.md menulis
  /// `{"error":{"code":"kode"}}`. Keduanya diterima.
  static String? _errorCode(Map<String, dynamic>? json) {
    final error = json?['error'];
    if (error is String) return error;
    if (error is Map) return error['code'] as String?;
    return null;
  }
}

/// RSSI (dBm) ke 0–100%, skala yang sama dengan aplikasi Kotlin lama.
int rssiToPercent(int rssi) => rssi >= -50 ? 100 : (rssi <= -100 ? 0 : 2 * (rssi + 100));
