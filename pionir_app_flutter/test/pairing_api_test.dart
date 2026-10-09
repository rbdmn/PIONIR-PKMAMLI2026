import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pionir_app_flutter/data/pairing_api.dart';
import 'package:pionir_app_flutter/models/smartplug_models.dart';

/// Pengganti SmartPlug: mengembalikan respons tetap per path dan mencatat request terakhir.
class _FakePlug implements HttpClientAdapter {
  final Map<String, (int, String)> responses = {};
  RequestOptions? last;
  String? lastBody;
  bool timeout = false;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    last = options;
    lastBody = requestStream == null
        ? null
        : utf8.decode(await requestStream.expand((c) => c).toList());
    if (timeout) {
      throw DioException(requestOptions: options, type: DioExceptionType.connectionTimeout);
    }
    final (status, body) = responses[options.uri.path]!;
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late _FakePlug plug;
  late PairingApi api;

  setUp(() {
    plug = _FakePlug();
    api = PairingApi(dio: Dio()..httpClientAdapter = plug);
  });

  test('pair/info: parse respons asli dari alat', () async {
    plug.responses['/api/v1/pair/info'] = (
      200,
      '{"api_version":"1.0","product":"smartplug","protocol":"pairing-v1",'
          '"device_id":"SP-A1B2C3D4E5F6","sta_mac":"A1:B2:C3:D4:E5:F6",'
          '"state":"unprovisioned","pairing_token":"tok123","token_expires_in_s":299}',
    );
    final info = await api.getPairInfo();
    expect(info.deviceId, 'SP-A1B2C3D4E5F6');
    expect(info.pairingToken, 'tok123');
    expect(info.tokenExpiresInS, 299);
  });

  test('pair/info: plug yang masih dimiliki -> pairing_closed', () async {
    plug.responses['/api/v1/pair/info'] = (409, '{"error":"pairing_closed"}');
    expect(
      api.getPairInfo(),
      throwsA(isA<SmartPlugException>().having((e) => e.code, 'code', 'pairing_closed')),
    );
  });

  test('error format design.md (bersarang) juga dikenali', () async {
    plug.responses['/api/v1/pair/info'] =
        (401, '{"api_version":"1.0","error":{"code":"invalid_pairing_token","message":"x"}}');
    expect(
      api.getPairInfo(),
      throwsA(isA<SmartPlugException>().having((e) => e.code, 'code', 'invalid_pairing_token')),
    );
  });

  test('scan-wifi: kirim token, ubah rssi & security', () async {
    plug.responses['/api/v1/pair/scan-wifi'] = (
      200,
      '{"api_version":"1.0","state":"pairing","networks":['
          '{"ssid":"WiFi-Rumah","rssi":-48,"security":"wpa2"},'
          '{"ssid":"WiFi-Tamu","rssi":-75,"security":"open"}]}',
    );
    final list = await api.scanWifi('tok123');
    expect(plug.last!.headers['X-Pairing-Token'], 'tok123');
    expect(list.first.ssid, 'WiFi-Rumah');
    expect(list.first.signalPercent, 100);
    expect(list.first.secured, isTrue);
    expect(list.last.signalPercent, 50);
    expect(list.last.secured, isFalse);
  });

  test('configure: body sesuai kontrak, kembalikan configuration_id', () async {
    plug.responses['/api/v1/pair/configure'] =
        (202, '{"api_version":"1.0","configuration_id":"cfg-abcd","state":"connecting"}');
    final id = await api.configureDirect(
      pairingToken: 'tok123',
      ssid: 'WiFi-Rumah',
      password: 'rahasia123',
    );
    expect(id, 'cfg-abcd');
    expect(jsonDecode(plug.lastBody!), {
      'ssid': 'WiFi-Rumah',
      'password': 'rahasia123',
      'connection_profile': {'type': 'direct'},
    });
  });

  test('pair/status: connected membawa lan_ip & owner_token', () async {
    plug.responses['/api/v1/pair/status'] = (
      200,
      '{"api_version":"1.0","state":"connected","device_id":"SP-A1B2C3D4E5F6",'
          '"sta_mac":"A1:B2:C3:D4:E5:F6","ssid":"WiFi-Rumah","lan_ip":"192.168.1.25",'
          '"owner_token":"owner-xyz"}',
    );
    final s = await api.getStatus(pairingToken: 'tok123', configurationId: 'cfg-abcd');
    expect(plug.last!.uri.queryParameters['configuration_id'], 'cfg-abcd');
    expect(s.state, PairState.connected);
    expect(s.lanIp, '192.168.1.25');
    expect(s.ownerToken, 'owner-xyz');
  });

  test('pair/status: failed membawa alasan', () async {
    plug.responses['/api/v1/pair/status'] =
        (200, '{"api_version":"1.0","state":"failed","reason":"wifi_authentication_failed"}');
    final s = await api.getStatus(pairingToken: 'tok123', configurationId: 'cfg-abcd');
    expect(s.state, PairState.failed);
    expect(s.failureReason, 'wifi_authentication_failed');
  });

  test('tidak ada jawaban -> timeout', () async {
    plug.timeout = true;
    expect(
      api.getPairInfo(),
      throwsA(isA<SmartPlugException>().having((e) => e.code, 'code', 'timeout')),
    );
  });
}
