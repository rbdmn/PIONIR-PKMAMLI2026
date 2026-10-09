# Migrasi LittleFS 64 KiB ke 32 KiB

Dokumen ini hanya berlaku untuk SmartPlug ESP-07 ber-flash 512 KiB yang
sebelumnya memakai firmware layout `eagle.flash.512k64.ld` dan memiliki data
energi lokal yang harus dipertahankan.

## Tujuan

Layout 64 KiB memberi ruang aplikasi 434.160 byte. Firmware produk saat ini
berada sangat dekat dengan batas tersebut. Layout 32 KiB memberi ruang aplikasi
466.928 byte, sementara dua slot record energi dan record timer tetap lebih
dari cukup untuk fungsi penyimpanan lokal saat ini.

Migrasi memakai record sementara 24 byte di tail EEPROM yang tidak dipakai:

- API settings: byte `0..511`
- MQTT settings: mulai byte `512`
- migration handoff: mulai byte `768`

Record handoff memuat magic, versi, fase, sequence, energi `double`, dan CRC32.
Firmware menolak record yang tidak valid.

## Larangan keselamatan

- Gunakan pemrogram USB terisolasi ketika SmartPlug tidak terhubung ke listrik
  AC. Ground rangkaian SmartPlug merujuk ke neutral listrik.
- Jangan menjalankan chip erase, `erase_flash`, atau upload filesystem di antara
  tahap 1 dan tahap 2.
- Jangan langsung mengunggah profile 32 KiB ke unit lama yang memiliki kWh
  tersimpan; tahap 1 wajib selesai terlebih dahulu.

## Tahap 1: siapkan handoff pada layout lama

Build/upload profile `smartplug_migrate_64_to_32_stage`.

Profile ini tetap memakai `eagle.flash.512k64.ld`. Pada boot, ia membaca slot
energi LittleFS yang valid, menyalin nilai terbaru ke EEPROM, melakukan
`EEPROM.commit()`, membaca ulang, dan memverifikasi CRC. Ia **tidak memformat
atau menulis LittleFS**.

Tanda berhasil di serial:

```text
INFO migration_64k_handoff_prepared_wh=<nilai>
```

Jika muncul `ERR migration_64k_no_valid_energy_record` atau
`ERR migration_64k_handoff_prepare_failed`, jangan lanjut ke tahap 2.

## Tahap 2: gunakan layout 32 KiB

Tanpa erase flash di antaranya, upload profile `smartplug_product_32k`.

Profile ini hanya memformat filesystem 32 KiB bila menemukan handoff EEPROM
ber-CRC dengan fase `prepared`. Setelah format, ia menulis nilai energi ke slot
LittleFS baru dan membaca ulangnya. Hanya setelah write berhasil ia mengubah
fase EEPROM menjadi `imported`; handoff dibersihkan pada boot aman berikutnya.

Tanda berhasil di serial:

```text
INFO migration_32k_energy_imported_wh=<nilai>
```

Jika listrik padam sebelum fase `imported`, record `prepared` tetap ada dan
tahap 2 mengulang impor dari nilai yang sama. Jika listrik padam sesudah fase
`imported`, boot berikutnya memasang filesystem baru normal; ia tidak memformat
ulang dan hanya mencoba membersihkan handoff yang tersisa.

## Verifikasi setelah tahap 2

1. Periksa `GET /api/v1/status`: `energy_persistence.ready` harus `true` dan
   `saved_available` harus `true` setelah checkpoint energi pertama.
2. Bandingkan nilai `saved_wh`/energi aplikasi dengan nilai serial dari tahap 1.
3. Reboot perangkat sekali tanpa erase dan pastikan energi tidak turun.
4. Uji Direct dan MQTT/server secara terpisah. Pada mode MQTT, snapshot server
   tetap diprioritaskan untuk recovery boot; LittleFS adalah fallback lokal.

## Batas bukti saat ini

Ketiga profile berhasil dikompilasi. Record handoff bersifat byte-stabil dan
punya CRC, namun uji migrasi end-to-end pada perangkat fisik dengan nilai kWh
non-nol belum dilakukan. Jangan menyatakan migrasi siap produksi sebelum uji
fisik tersebut lulus.
