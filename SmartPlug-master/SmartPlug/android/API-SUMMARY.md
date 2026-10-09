# Ringkasan endpoint yang dipakai aplikasi

Semua endpoint di bawah adalah kontrak **target** dari
[`docs/design.md`](../docs/design.md). Sebagian belum diimplementasikan firmware saat ini —
lihat catatan status di [`README.md`](README.md). Sumber kebenaran untuk detail payload/HTTP
status tetap `docs/design.md`, bukan dokumen ini.

## Pairing (hanya via SmartPlug AP, `http://192.168.4.1`)

| Endpoint | Dipakai untuk | File Kotlin |
|---|---|---|
| `GET /api/v1/pair/info` | Membaca `device_id`, `sta_mac`, `pairing_token` sebelum konfigurasi | `PairingApi.getPairInfo` |
| `POST /api/v1/pair/scan-wifi` | Meminta SmartPlug memindai Wi-Fi rumah | `PairingApi.scanWifi` |
| `POST /api/v1/pair/configure` | Mengirim SSID/password rumah + profil koneksi (`direct` atau `server`) | `PairingApi.configure` |
| `GET /api/v1/pair/status` | Polling 1 detik sampai `connected`/`failed` | `PairingApi.getStatus` |

## Operasional mode REST langsung (`http://<lan_ip>`)

| Endpoint | Dipakai untuk | File Kotlin |
|---|---|---|
| `GET /api/v1/status` | Status relay, `device_id`, `fresh`/`sample_age_ms` | `DeviceApi.getStatus` |
| `GET /api/v1/measurements/allparameters` | Semua parameter listrik dalam satu request (polling 1-2 detik) | `DeviceApi.getAllParameters` |
| `POST /api/v1/relay` | Perintah relay `on`/`off`, direspons `202 queued` | `DeviceApi.setRelay` |

## Operasional mode ServerSmartPlug (`http://<server_host>`)

| Endpoint | Dipakai untuk | File Kotlin |
|---|---|---|
| `GET /api/v1/status` | Cek kesiapan server | `ServerApi.getServerStatus` |
| `GET /api/v1/devices` | Daftar SmartPlug terdaftar di server | `ServerApi.listDevices` |
| `GET /api/v1/devices/{device_id}/latest` | Pengukuran + status relay terbaru | `ServerApi.getLatest` |
| `GET /api/v1/devices/{device_id}/energy` | Total energi tersimpan server (`source: server_sd`) | `ServerApi.getEnergy` |
| `GET /api/v1/devices/{device_id}/history` | Riwayat beresolusi `1m/5m/30m/1h/1d` untuk grafik | `ServerApi.getHistory` |
| `POST /api/v1/devices/{device_id}/relay` | Perintah relay lewat server, direspons `202 queued` + `command_id` | `ServerApi.setRelay` |
| `GET /api/v1/commands/{command_id}` | Polling hasil akhir command relay (`completed`/`rejected`/`timeout`) | `ServerApi.getCommandStatus` |

## Discovery (non-HTTP)

| Mekanisme | Dipakai untuk | File Kotlin |
|---|---|---|
| Android Wi-Fi scan API | Menyaring AP `SP-<unit_id>` di sekitar | `WifiOnboardingRepositoryImpl.scanForSmartPlugAps` |
| `WifiNetworkSpecifier` | Menyambung ke AP SmartPlug tanpa mengubah Wi-Fi default sistem | `WifiOnboardingRepositoryImpl.connectToAp` |
| NSD (`_smartplug._tcp`) | Menemukan ulang `lan_ip` SmartPlug bila alamat berubah | `DiscoveryRepositoryImpl.resolveDeviceLanIp` |
| NSD (`_srvrplug._tcp`) | Menemukan ServerSmartPlug di jaringan rumah saat pairing | `DiscoveryRepositoryImpl.discoverServer` |

Endpoint yang **tidak** dipakai aplikasi karena bukan bagian dari kontrak aplikasi pengguna akhir:
konsol serial firmware, halaman commissioning HTML (`GET /`), dan endpoint QC lain yang hanya
disebut di `firmware/LOCAL-API.md` untuk kebutuhan instalasi/QC teknisi.
