# Kontrak MQTT SmartPlug - v1

Firmware menggunakan Wi-Fi Station untuk terhubung ke broker saat administrator
memilih mode **MQTT** dari halaman commissioning. Pada mode **REST API**,
perangkat berkomunikasi langsung dengan aplikasi melalui HTTP lokal.

## Mulai dari nol

1. Hubungkan telepon/laptop ke Wi-Fi `SmartPlug-Setup` dengan password
   `SmartPlug123`.
2. Buka
   `http://192.168.4.1`.
3. Pada panel **Sambungkan ke Wi-Fi yang ada**, masukkan SSID/password Wi-Fi
   lokasi. Pastikan status Wi-Fi Station menjadi `connected`.
4. Siapkan alamat ServerSmartPlug ESP32, port MQTT, username/password broker,
   dan base topic. ServerSmartPlug R3.8 selalu memerlukan akun broker.
   Firmware membentuk device id otomatis dari STA MAC dengan format
   `SP-<STA_MAC>` tanpa tanda titik dua. Contoh base topic untuk device id
   `SP-84F3EB123456` adalah `smartplug/SP-84F3EB123456`.
5. Buka panel **Mode integrasi**, pilih **MQTT**, lalu isi alamat IP/host
   broker, port, username/password bila diperlukan, dan base topic. Simpan
   pengaturan mode integrasi.
6. Server MQTT dapat subscribe `smartplug/SP-84F3EB123456/measurement/#`. Ketika
   koneksi berhasil, topic `availability` memuat `online` dan setiap parameter
   pengukuran diterbitkan setiap 500 ms.

## Konfigurasi dari Access Point

Pilih `MQTT`, masukkan IP/host ServerSmartPlug, port `1883`, username/password
broker yang sama dengan konfigurasi ServerSmartPlug, serta base topic seperti
`smartplug/SP-84F3EB123456`. Konfigurasi
tersimpan di EEPROM dengan record terversi dan CRC32; password broker tidak
ditampilkan kembali pada halaman commissioning.

## Topik

| Topik | Arah | Isi |
|---|---|---|
| `<base>/measurement/voltage` | Device -> broker | Tegangan dalam V. |
| `<base>/measurement/current` | Device -> broker | Arus dalam A. |
| `<base>/measurement/active-power` | Device -> broker | Daya aktif dalam W. |
| `<base>/measurement/apparent-power` | Device -> broker | Daya semu dalam VA. |
| `<base>/measurement/power-factor` | Device -> broker | Power factor dalam PF. |
| `<base>/measurement/energy` | Device -> broker | Energi kumulatif dalam Wh. |
| `<base>/measurement/allparameters` | Device -> broker | Seluruh parameter sebagai satu JSON. |
| `<base>/state` | Device -> broker | Status retained perangkat dan relay. |
| `<base>/availability` | Device -> broker | `online` retained; LWT `offline`. |
| `<base>/cmd/relay` | Broker -> device | Payload `on` atau `off`. |
| `<base>/ack/relay` | Device -> broker | Hasil antrean perintah relay. |
| `<base>/sync/energy` | Server -> device | Snapshot counter energi server setelah perangkat terhubung. |

## Payload

Mulai R3.8.1, `energy_wh` berasal dari akumulator counter energi yang sama dengan
REST. Tidak ada penambahan estimasi daya di antara increment counter; energi
dapat tetap sementara pada beban kecil. Aturan reset, rollover, dan jeda data
tercantum pada `ENERGY-R3.8.1.md`.

Server MQTT menyimpan setiap topic di bawah `<base>/measurement/#` sebagai
riwayat pengukuran pada basis data server. Contoh payload satu parameter:

```json
{"device_id":"SP-84F3EB123456","captured_at_ms":125000,"parameter":"voltage","value":220.1,"unit":"V"}
```

Contoh payload seluruh parameter:

```json
{"device_id":"SP-84F3EB123456","captured_at_ms":125000,"calibrated":true,"voltage_v":220.1,"current_a":0.42,"active_power_w":86.4,"apparent_power_va":92.5,"power_factor":0.934,"energy_wh":1234.5}
```

State JSON memuat `device_id`, `relay`, `relay_actuation`, dan
`wifi_connected`. Payload command relay hanya menerima `on` atau `off`.
Respons `ack/relay` memuat `accepted` dan `state`.

Server menyimpan counter energi terakhir per `device_id`, lalu menerbitkan
`<base>/sync/energy` setelah reconnect. Payloadnya:

```json
{"device_id":"SP-84F3EB123456","energy_wh":1234.5,"recorded_at_ms":1710000000}
```

Snapshot server yang valid dipakai perangkat sebagai counter saat reconnect.
Dengan demikian, server ESP32 dan basis data SD card menjadi sumber utama pada
mode MQTT; LittleFS hanya cadangan lokal jika diaktifkan.

Firmware mencoba koneksi ulang broker setiap lima detik selama Wi-Fi Station
berstatus connected. Setelah kegagalan berturut-turut, interval bertambah
menjadi 10, 20, 40, lalu maksimum 60 detik dan kembali ke 5 detik setelah
koneksi berhasil. Keepalive broker adalah 30 detik.

## Kebijakan MQTT aman

| Topic | QoS dan retain | Kebijakan |
|---|---|---|
| `<base>/measurement/#` | QoS 0, tidak retained | Server menerima live data dan memperbarui latest data. |
| `<base>/state` | QoS 0, retained | Status terakhir perangkat. |
| `<base>/availability` | online QoS 0 retained; offline LWT QoS 1 retained | Server menandai koneksi perangkat. |
| `<base>/cmd/relay` | QoS 0, tidak retained | Hanya server boleh publish; command tidak boleh retained. |
| `<base>/ack/relay` | QoS 0, tidak retained | Server memasangkan hasil dengan satu command aktif per device. |
| `<base>/sync/energy` | QoS 0, tidak retained | Server mengirim snapshot energy terakhir setelah perangkat online. |

Broker memakai akun khusus perangkat dan menegakkan ACL per base topic dari
identitas MQTT `SmartPlug-SP-<STA_MAC>`. Aplikasi tidak mengirim command
langsung ke broker; aplikasi memanggil REST API server.
Server membatasi satu command relay aktif per `device_id`, menunggu `ack/relay`
maksimum lima detik, dan mencatat hasilnya. Untuk jaringan di luar LAN
terpercaya, broker dan server harus memakai TLS; firmware ini menggunakan MQTT
LAN biasa.

## Histori yang disediakan server

Server memperbarui latest data pada setiap message dan menyimpan histori agregat
setiap satu menit: rata-rata V, A, W, VA, PF dan nilai `energy_wh` terakhir.
Simpan histori 1 menit selama 30 hari; agregasikan menjadi 1 jam untuk retensi
365 hari dan 1 hari untuk retensi jangka panjang sesuai kapasitas SD card.
Endpoint REST server untuk aplikasi adalah:

```text
GET  /api/v1/devices/{device_id}/latest
GET  /api/v1/devices/{device_id}/history?from=<UTC>&to=<UTC>&resolution=1m
GET  /api/v1/devices/{device_id}/energy
POST /api/v1/devices/{device_id}/relay
GET  /api/v1/commands/{command_id}
```

## Kontrol dari aplikasi

Pada mode MQTT, aplikasi tidak perlu dan tidak boleh mengirim command ke
broker secara langsung. Gunakan REST API ServerSmartPlug:

```text
POST /api/v1/devices/SP-84F3EB123456/relay
Authorization: Bearer <application-api-token>
Content-Type: application/json

{"state":"on"}
```

Server menerbitkan payload `on` atau `off` ke topic perangkat, menunggu
`ack/relay` paling lama lima detik, lalu aplikasi membaca
`GET /api/v1/commands/{command_id}` untuk memperoleh `completed`, `rejected`,
atau `timeout`.

## Pemeriksaan cepat

| Kondisi | Tindakan |
|---|---|
| Status broker menunjukkan belum dikonfigurasi | Pilih mode MQTT, lalu isi host, port, dan topic pada panel **Mode integrasi**. |
| `configured=true` tetapi `connected=false` | Periksa Wi-Fi Station, alamat broker, port, serta akun broker. |
| Tidak ada measurement | Pastikan server subscribe ke `<base>/measurement/#` dan status pembaca energi pada panel QC aktif. |
| Tidak ada `ack/relay` | Publish payload huruf kecil `on` atau `off` ke `<base>/cmd/relay`. |
