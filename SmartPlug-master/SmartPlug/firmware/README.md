# SmartPlug firmware — R3.9.2

R3.9.2 menghapus dashboard web perangkat dan menjadikan pemulihan energi
LittleFS selalu aktif tanpa toggle EEPROM. Factory reset menghapus checkpoint
energi LittleFS sebelum perangkat kembali ke provisioning awal.

R3.9.1 membedakan interval checkpoint energi berdasarkan mode integrasi:
15 menit untuk REST langsung dan lima menit untuk MQTT. Perhitungan energi
tetap berada di SmartPlug.

R3.9.0 menambahkan setup bernomor yang ramah HP, kewajiban mengganti password
admin awal, factory build dengan kredensial unik + QR Wi-Fi, penyimpanan energi
dua record CRC dengan verifikasi baca balik, penanda data stale, serta ACK relay
setelah pulsa selesai. Catatan rilis dan batas verifikasi:
[RELEASE-R3.9.0.md](RELEASE-R3.9.0.md).

Panduan pengguna terbaru: `../output/pdf/SmartPlug-Panduan-Pengguna-R3.10.pdf`
(28 halaman; integrasi Android di halaman 17-28). Revisi dokumen ini tidak
mengubah versi firmware R3.9.0. Panduan R3.9 sebelumnya tetap disimpan.
Build biasa mempertahankan kompatibilitas unit lama. **Unit baru untuk penyerahan
produk wajib memakai `esp07_factory` dan profil/label yang cocok dengan STA MAC**;
ikuti [FACTORY-PROVISIONING-R3.9.0.md](FACTORY-PROVISIONING-R3.9.0.md).
Tidak ada upload atau uji hardware yang dilakukan pada pengerjaan rilis ini.

## Dasar perhitungan energi

R3.8.1 memperbaiki perhitungan energi menjadi counter-only, menambahkan guard
reset/rollover, dan menjalankan uji regresi native. Lihat
[catatan energi](ENERGY-R3.8.1.md) dan
[hasil verifikasi R3.8.1](ENERGY-TEST-STATUS-R3.8.1.md).
ServerSmartPlug tetap memakai revisi R3.8.0; kontrak topic dan satuan Wh tidak berubah.

Firmware SmartPlug ESP8266 memakai satu image dengan pilihan mode runtime:
`esp07_factory` untuk provisioning unit baru, atau `smartplug_product` untuk
kompatibilitas unit lama. Setelah perangkat
terhubung ke Wi-Fi lokasi, administrator memilih satu jalur integrasi aktif:
**REST API** untuk aplikasi LAN atau **MQTT** untuk broker. Access Point lokal
tetap tersedia khusus untuk konfigurasi dan pemeriksaan QC sederhana.

Rilis integrasi R3.8 terdiri dari dua firmware produk:

| Firmware | Target | Peran |
|---|---|---|
| `SmartPlug/firmware`, environment `smartplug_product` | ESP8266 | Metering, relay latching, commissioning AP, serta mode REST atau MQTT yang dapat dipilih saat runtime. |
| `ServerSmartPlug`, environment `server_esp32` | ESP32 | Broker MQTT, REST API aplikasi, penyimpanan SD, histori satu menit, dan sinkronisasi energi. |

Environment `esp07_rest`, `esp07_mqtt`, dan `esp07_safe` dipertahankan hanya
sebagai profile engineering/kompatibilitas. Image `esp07_factory` harus dibangun
dengan profil kredensial unik yang sesuai dengan STA MAC unit tujuan.

Access Point hanya menyediakan REST API JSON untuk aplikasi. Perangkat tidak
menyajikan halaman dashboard, form konfigurasi, maupun grafik. Monitoring dan
kontrol aplikasi berjalan melalui REST API atau MQTT sesuai mode yang dipilih.

## Struktur firmware

Firmware aktif berada pada [`src/main.cpp`](src/main.cpp) — driver, API lokal,
MQTT, `setup()`, dan `loop()` — ditambah header protokol/konfigurasi
bersama di [`include/`](include/) (`BuildConfig.h`, `BoardPins.h`,
`Bl0940Protocol.h`, `SmartPlugMetering.h`, `SmartPlugConfig.h`,
`SmartPlugAnomaly.h`, `SmartPlugReliability.h`, `FactoryProfile.h`). Helper
metering/reliability dipakai langsung oleh unit test native agar tidak ada
dua salinan logika yang sama. Sumber multi-file sebelumnya disimpan
pada [`archive/multifile-source-r0.7.3/`](archive/multifile-source-r0.7.3/)
sebagai referensi dan tidak ikut dibangun.

The default `esp07_safe` profile deliberately disables relay actuation, the
GPIO0 button action, the GPIO2 LED, Wi-Fi radio, cloud connectivity, OTA, and
unit conversion. The source archive contains no earlier firmware and the PCB
has not been energized or probed in this work.

## Konfigurasi yang dikelola dari Access Point

| Parameter | Kapan diatur | Rekomendasi |
|---|---|---|
| Wi-Fi Station SSID/password | Instalasi awal atau pindah lokasi | Gunakan SSID jaringan lokasi yang stabil. |
| Mode integrasi | Setelah Wi-Fi Station terhubung | Pilih **REST API** bila aplikasi LAN membaca endpoint HTTP; pilih **MQTT** bila ada broker. |
| MQTT host, port, akun, base topic | Hanya mode MQTT | Gunakan IP/hostname broker, port `1883` bila broker memakai default, dan topic unik per perangkat. |
| Persistensi energi LittleFS | Selalu aktif | Checkpoint energi lokal untuk pemulihan setelah restart. |
| Nama/password Access Point | Instalasi awal | Beri nama yang menunjukkan identitas unit dan password kuat minimal 8 karakter. |
| Password admin | Instalasi awal | Ganti ke password unik minimal 12 karakter. |

Kalibrasi tidak ditampilkan sebagai pengaturan operator pada halaman ini. Nilai
QC menampilkan tegangan, arus, daya, power factor, dan energi untuk verifikasi instalasi; perubahan
koefisien metering tetap merupakan pekerjaan engineering terkontrol.

Counter energi kumulatif disimpan terpisah di LittleFS sebagai dua record
bergantian, masing-masing 24 byte berisi versi,
urutan, energi double, dan CRC32. Record lama 8 byte tetap dapat dimigrasikan.
Firmware memulihkan record valid terbaru pada boot. Checkpoint dibuat bila
nilai energi berubah: setiap 15 menit pada mode REST langsung atau setiap lima
menit pada mode MQTT. Unit baru menyiapkan filesystem otomatis; factory reset
menghapus checkpoint energi lalu menyiapkan filesystem kosong.

## Build

```powershell
pio run -d D:\IoT\SmartPlug\firmware -e smartplug_product
```

Protocol unit tests require a host C++ compiler:

```powershell
pio test -d D:\IoT\SmartPlug\firmware -e native_protocol_tests
```

Image `smartplug_product` memiliki dukungan MQTT, tetapi mode awalnya adalah REST
sampai administrator menyimpan konfigurasi MQTT yang valid dari Access Point.

## Verifikasi build

Hasil di bawah adalah catatan historis R3.8.0. Hasil build source terkini ada di
`ENERGY-TEST-STATUS-R3.8.1.md`.

`smartplug_product` R3.8.0 dikompilasi untuk target ESP8266/ESP-07 dengan
`espressif8266@4.2.1`. Build menghasilkan binary 393,539 byte dari batas
434,160 byte image 512 KB. Hasil build membuktikan source dapat dikompilasi;
hasil tersebut bukan bukti flashing, boot, pembacaan meter, relay, Wi-Fi, atau
operasi board fisik.

## Pemeriksaan QC lokal

Hubungkan perangkat ke Access Point, buka `http://192.168.4.1`, lalu gunakan
panel QC untuk memeriksa paket meter valid, tegangan/arus/daya/energi, kode raw,
dan respons uji relay. Halaman tidak menyediakan grafik riwayat atau kontrol
operasional; fungsi tersebut merupakan tanggung jawab integrasi REST API atau
MQTT.

## Safety gate

The schematic ties electronics GND to mains neutral through R18 and R19. The
programming connector is therefore potentially mains-referenced. Never connect
a normal PC/programmer while mains is present. Building this code is not
permission to flash, energize, or test the board.

Relay support needs `SMARTPLUG_ALLOW_RELAY_ACTUATION=1`, but that flag must not
be enabled until the neutral-switching topology, exact relay, coil pulse,
cooldown, boot behavior, and physical fixture are independently approved and
verified. A latching relay retains mechanical state through resets; state API
adalah state command yang diketahui firmware, bukan contact feedback.
