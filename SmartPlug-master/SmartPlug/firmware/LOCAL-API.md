# Kontrak REST API SmartPlug — v1

Image produk menjalankan REST API HTTP lokal untuk aplikasi. Access Point juga
menyajikan portal browser lokal; aplikasi tetap memakai endpoint JSON untuk
provisioning, QC, monitoring, dan kontrol.

## Provisioning awal

Pada boot pertama (atau factory reset), perangkat kembali ke access point setup
untuk dipilih oleh aplikasi Android.

| Parameter | Perilaku |
|---|---|
| SSID | `SP-<unit_id>` pada profil kompatibilitas; profil factory mengikuti identitas factory yang diprovisi. |
| Password AP | `setup-<unit_id>` pada profil kompatibilitas; profil factory mengikuti kredensial factory. |
| Akun admin | Username `admin`; password awal `SmartPlug123`. |
| IP AP | `192.168.4.1`, HTTP port 80. |

Password admin tidak disimpan plaintext: EEPROM menyimpan digest HMAC-SHA-256
dengan salt per perangkat.

Factory reset menghapus Wi-Fi existing, session, mode integrasi, pengaturan
broker, dan kredensial yang diubah pengguna, lalu memulihkan konfigurasi awal.

## Identitas perangkat

Firmware membentuk `device_id` otomatis dari **STA MAC Wi-Fi**, bukan AP MAC
dan bukan daftar nomor yang harus diinput operator. Formatnya:

```text
device_id = SP- + STA_MAC tanpa tanda titik dua
contoh    = SP-84F3EB123456
```

`device_id` tersedia pada `/api/v1/status` dan dipakai konsisten oleh REST,
MQTT, server, serta histori aplikasi.

## Kebijakan akses

Telemetry operasional tidak publik. Endpoint berikut memerlukan `Authorization:
Bearer <credential>` dari aplikasi yang sudah didaftarkan, atau session browser
admin yang masih sah. Discovery minimal tetap terpisah agar HP baru dapat
menemukan identitas SmartPlug sebelum menjalankan alur akses tambahan.

| Endpoint | Isi |
|---|---|
| `GET /api/v1` | Discovery API. |
| `GET /api/v1/capabilities` | Kemampuan firmware dan batasnya. |
| `GET /api/v1/status` | Status Wi-Fi, relay commanded state, dan firmware. |
| `GET /api/v1/measurements/allparameters` | Seluruh parameter sampel terbaru. |
| `GET /api/v1/measurements/voltage` | Tegangan terbaru dalam V. |
| `GET /api/v1/measurements/current` | Arus terbaru dalam A. |
| `GET /api/v1/measurements/active-power` | Daya aktif terbaru dalam W. |
| `GET /api/v1/measurements/apparent-power` | Daya semu terbaru dalam VA. |
| `GET /api/v1/measurements/power-factor` | Power factor terbaru dalam PF. |
| `GET /api/v1/measurements/energy` | Energi kumulatif terbaru dalam Wh. |
| `GET /api/v1/health` | Kesehatan komunikasi meter. |
| `GET /api/v1/diagnostics` | Reset reason, heap, Wi-Fi/MQTT, relay, zero crossing, BL0940, LittleFS/energi, dan 24 event terakhir. Khusus session admin atau bearer **owner**. |
| `GET /diagnostics` | Halaman diagnostik lokal read-only, memakai session admin browser (atau bearer owner dari klien HTTP); target awal-ke-awal 1 detik, tanpa request overlap. |

`GET /api/v1` dan `GET /api/v1/capabilities` adalah discovery minimal; keduanya
tidak memuat telemetry, kredensial, status pairing, atau password. Mutasi
administrasi browser memerlukan session dan CSRF token. Credential `owner`
hasil pairing dapat mengelola akses/konfigurasi; credential `member` hasil
undangan dapat membaca dan mengontrol operasi sehari-hari tanpa mengetahui
password Wi-Fi atau credential HP lain.

## Wi-Fi Diagnostics (R3.10.11, source/build saja)

Sambungkan HP/komputer ke AP SmartPlug dan buka `http://192.168.4.1/`, login
admin, lalu buka `http://192.168.4.1/diagnostics`. Endpoint JSON yang sama
tersedia di `GET /api/v1/diagnostics` memakai cookie session admin atau
`Authorization: Bearer <owner_key>`; credential member tidak diizinkan.
Respons berisi `firmware_version`, `device_id`, `uptime_ms`, `reset_reason`,
`free_heap`, `max_free_block`, objek `wifi`, `integration`, `relay`,
`zero_cross`, `bl0940`, `storage`, serta `events` terbaru lebih dahulu.
`zero_cross.status` dapat berupa `valid`, `waiting`, `timeout` (fallback
zero-cross terjadi dalam 5 detik terakhir), atau `unavailable`; mode pulse
terakhir tetap terlihat terpisah sebagai `last_trigger_mode`. `detail` event relay
bernilai 1 untuk ON dan 0 untuk OFF; `zero_cross_timeout.detail=1` berarti
edge terlihat tetapi pulse tidak berhasil dimulai pada edge tersebut.

Ring berkapasitas 24 event disalin ke RTC user memory setiap event (bukan
flash), dan di-checkpoint ke dua slot LittleFS ber-CRC paling sering setiap
30 detik. Setelah restart biasa, RTC memberi event terbaru; setelah daya
terputus total, event sejak checkpoint terakhir dapat hilang. Kegagalan
LittleFS tercermin pada `storage.diagnostics_checkpoint_ok`, dan factory reset
menghapus ring. Endpoint ini tidak menyediakan kontrol relay dan tidak
menampilkan password/token.

Jangan menghubungkan USB-to-TTL biasa ketika P1 diberi 220 VAC. Reset reason
dan urutan event perlu dibaca setelah uji fisik yang aman; build/source saja
tidak membuktikan penyebab restart ataupun keberhasilan pulse pada AC.

## Login, session, dan API mutasi

1. `POST /api/v1/auth/login` dengan form URL-encoded `username` dan `password`.
2. Jika berhasil, respons memberi `csrf_token` dan browser menerima cookie
   `sp_session` dengan atribut `HttpOnly`, `SameSite=Strict`, dan masa hidup
   15 menit.
3. Setiap `POST` mutasi dari browser mengirim header `X-CSRF-Token: <token>`
   serta cookie. Aplikasi dapat menggantikan keduanya dengan `Authorization:
   Bearer <credential>` bila endpoint menerima credential operasional.
4. `POST /api/v1/auth/logout` menghapus session.

| Endpoint | Input | Hasil |
|---|---|---|
| `POST /api/v1/relay` | `state=on` atau `state=off` | Antrekan pulse relay; minimum satu detik antar-perintah. |
| `POST /api/v1/settings/wifi` | `ssid`, `password` | Simpan Wi-Fi existing dan mulai koneksi STA. |
| `GET /api/v1/settings/mqtt` | session | Baca mode integrasi dan metadata broker tanpa password broker. |
| `POST /api/v1/settings/mqtt` | owner credential atau session admin; `mode=rest` atau `mode=mqtt`, serta parameter broker pada mode MQTT | Pilih jalur integrasi dan simpan pengaturan broker. Credential `member` tidak dapat mengubah atau memutus koneksi ServerSmartPlug. Transisi Direct↔MQTT menghapus timer/Schedule lokal secara deterministik; perubahan tidak menggerakkan relay. |
| `POST /api/v1/settings/access` | `ap_ssid`, `ap_password`, opsional `admin_password` | Ganti akses AP/admin dan reboot. Password admin baru minimal 12 karakter. |
| `POST /api/v1/settings/wifi/reset` | — | Factory reset lalu reboot. |
| `POST /api/v1/energy/reset` | `confirm_1`, `confirm_2`, `confirm_3` semuanya `RESET_ENERGY` | Reset counter energi perangkat setelah konfirmasi tiga tahap. |
| `GET /api/v1/timer` | credential operasional (`owner` atau `member`) atau session | Mengembalikan status lengkap yang memuat objek `timer`, termasuk `active`, `running`, dan `remaining_ms`. |
| `POST /api/v1/timer` | credential operasional (`owner` atau `member`), atau session browser + CSRF; `days`, `hours`, `minutes`, `seconds`, atau `action=reset` | Terapkan timer Direct; bila relay OFF, timer baru mulai saat relay ON. Deadline timer disimpan di LittleFS dan dipulihkan setelah reboot; pada expiry, checkpoint baru dihapus setelah status relay telah `off`. Pada mode MQTT endpoint menolak dengan `409 automation_requires_direct_mode`: timer hanya di ServerSmartPlug. |
| `GET /api/v1/schedule` | credential operasional (`owner` atau `member`) atau session | Baca jadwal lokal, sinkronisasi jam, jadwal berikutnya, dan daftar entri. |
| `POST /api/v1/schedule` | credential operasional (`owner` atau `member`), atau session browser + CSRF; `action=set_enabled`, `add`, `delete`, atau `move` | Ubah jadwal Direct; `add` memakai `hour`, `minute`, `state=on|off`, dan `event`; `set_enabled` memakai `enabled=true|false`. Opsional `timezone_offset_minutes` harus −720 s.d. 840. |
| `POST /api/v1/factory-reset` | owner token + tiga `FACTORY_RESET` | Hapus konfigurasi dan reboot; khusus aplikasi Android. |
| `GET /api/v1/audit` | session | Audit ring-buffer: login, perubahan setting, relay, dan reset. |
| `GET /api/v1/access/profile` | credential atau session | Profil aman untuk HP tambahan: identitas, mode integrasi, relay, timer, dan jadwal; tidak ada password Wi-Fi/MQTT. |
| `POST /api/v1/access/invitations` | owner credential | `role=member`; membuat kode 6 digit sekali pakai selama 5 menit. |
| `POST /api/v1/access/enroll` | LAN, kode undangan | Body JSON `device_id`, `invite_code`; memberi credential member sekali saja. Tidak tersedia dari subnet AP setup. |
| `GET /api/v1/access/credentials` | owner credential | ID dan role credential aktif, tanpa token/verifier. |
| `DELETE /api/v1/access/credentials?credential_id=...` | owner credential | Cabut satu credential member. |

Login salah dibatasi: lima kegagalan berurutan menghasilkan lockout 60 detik.
Mutasi API dibatasi, dan input SSID/password/state divalidasi sebelum disimpan
atau diteruskan ke relay. Enroll member dibatasi lima kode salah lalu lockout
60 detik; error sengaja tidak membedakan kode salah, kedaluwarsa, atau undangan
tidak tersedia.

Timer dan Schedule lokal hanya berwenang dalam mode Direct. Pada mode MQTT,
`POST /api/v1/timer` maupun `POST /api/v1/schedule` menolak dengan
`409 automation_requires_direct_mode`; pencegahan ini juga berlaku di loop
relay sehingga record lokal lama tidak dapat berjalan setelah hand-off.

## Storage dan pengukuran

Konfigurasi Wi-Fi, AP, hash admin, mode integrasi, dan pengaturan MQTT disimpan
dalam record EEPROM terversi dengan CRC32. Jika record korup atau format tidak
cocok, perangkat kembali ke provisioning awal aman.

BL0940 dipoll setiap 500 ms. Tegangan, arus, dan daya aktif memakai smoothing
hingga 10 sampel valid. Nilai electrical yang belum memiliki
koefisien valid dilaporkan sebagai `0`; field `calibration` membedakannya dari
pembacaan nol fisik. Persistensi `energy_wh` selalu aktif: counter dipulihkan
dari LittleFS saat boot dan ditulis ulang setiap 15 menit pada mode REST
langsung atau lima menit pada mode MQTT, sehingga nilainya kumulatif
antar-restart. Factory reset menghapus checkpoint energi ini.

## Format measurement REST

Mulai R3.8.1, energi bertambah hanya dari selisih counter energi BL0940; nilainya
dapat tetap di antara increment, terutama pada beban kecil. Tidak ada estimasi
`daya * waktu` yang ditambahkan. Reset/lonjakan counter dan jeda data lebih dari
lima detik memulai baseline baru tanpa menaikkan total dari interval yang tidak
pasti. Ketentuan lengkap terdapat pada `ENERGY-R3.8.1.md`.

`GET /api/v1/measurements/allparameters` mengembalikan JSON seperti berikut:

```json
{
  "captured_at_ms": 125000,
  "has_sample": true,
  "calibration": "calibrated",
  "electrical": {
    "voltage_v": 220.1,
    "current_a": 0.42,
    "active_power_w": 86.4,
    "apparent_power_va": 92.5,
    "power_factor": 0.934,
    "energy_wh": 1234.5
  }
}
```

Endpoint parameter tunggal memakai format yang konsisten, misalnya
`GET /api/v1/measurements/voltage`:

```json
{"captured_at_ms":125000,"has_sample":true,"parameter":"voltage","value":220.1,"unit":"V","calibration":"calibrated"}
```

## Batas keamanan yang tetap berlaku

Transport masih HTTP lokal tanpa TLS. Session/CSRF mengurangi penyalahgunaan dari
halaman atau klien lain di jaringan, tetapi tidak mengenkripsi password atau
telemetry terhadap pihak yang telah memiliki akses ke Wi-Fi. Relay API hanya
menyatakan command queued. Klien wajib membaca `relay_state` dan field status
flat `relay_command_result` untuk hasil terminal; `zero_cross_timeout` berarti
pulsa dibatalkan karena tidak ada zero crossing yang diterima dalam batas aman,
sehingga state relay sebelumnya tetap berlaku. Setelah boot, tiga sampel arus valid digunakan untuk
menentukan state awal: arus terdeteksi menetapkan `on`; tiga pembacaan tanpa
arus memicu pulse `off`, kemudian status menjadi `off` setelah pulse selesai.

## Status HTTP dan error

Semua error berbentuk JSON `{"error":"<error_code>"}`.

| HTTP | Contoh error | Tindakan aplikasi |
|---|---|---|
| 400 | `invalid_relay_state`, `invalid_mqtt_settings` | Perbaiki input; jangan retry otomatis. |
| 401 | `authentication_required`, `authentication_failed` | Login kembali. |
| 403 | `csrf_invalid`, `relay_actuation_disabled` | Perbarui session/CSRF atau nonaktifkan kontrol relay. |
| 404 | `not_found`, `measurement_not_found` | Gunakan endpoint yang diumumkan discovery. |
| 429 | `rate_limited`, `login_temporarily_locked` | Tunggu sebelum mencoba lagi. |
| 500 | `settings_write_failed`, `calibration_write_failed` | Simpan error dan minta pengguna mengulang. |
