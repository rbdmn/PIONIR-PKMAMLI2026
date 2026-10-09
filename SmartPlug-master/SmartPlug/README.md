# SmartPlug

## Download aplikasi Android

Unduh APK Android terbaru: [**SmartPlugApp-latest.apk**](https://github.com/fadlurrahmanf/SmartPlug/releases/latest/download/SmartPlugApp-latest.apk).

Versi, checksum, dan catatan perubahan tersedia di halaman [GitHub Releases](https://github.com/fadlurrahmanf/SmartPlug/releases/latest).

## Datasheet board

Dokumen publik untuk memahami board dan wiring terdapat pada
[`datasheet.md`](datasheet.md) dan [`docs/design.md`](docs/design.md).
Catatan audit historis, bukti uji, dan salinan source lama dipisahkan dari
dokumentasi developer agar repository tetap mudah dinavigasi.

Paket rekayasa yang dirakit dari `SmartPlugV2.zip` pada 2026-08-25.

## Mulai dari sini

- [`docs/design.md`](docs/design.md) — baseline desain, arsitektur,
  bahaya, gate rilis, dan rencana pengembangan.
- [`datasheet.md`](datasheet.md) — datasheet rekayasa terkendali. Dokumen
  ini sengaja belum menjadi datasheet penjualan/produksi selama rating kritis
  belum terverifikasi.
- [`firmware/`](firmware/) — firmware bring-up aman ESP-07 yang dapat
  dikompilasi. Aktuasi relay dinonaktifkan secara default.
- [`hardware/easyeda/`](hardware/easyeda/) — source EasyEDA yang tidak
  dimodifikasi dari arsip yang diberikan.

Arsip internal yang tidak dibutuhkan saat build disimpan di
[`../internal-records/SmartPlug-internal-records.rar`](../internal-records/SmartPlug-internal-records.rar).

## Disposisi saat ini

**BELUM SIAP UNTUK PROTOTIPE BERTEGANGAN, PRAPRODUKSI, ATAU RILIS PRODUKSI.**

Schematic/layout yang diberikan menunjukkan jalur beban dengan neutral yang
disakelar, GND elektronik terhubung ke neutral mains, tidak terlihat adanya
fuse/thermal-fuse, antarmuka daya yang generik/tanpa rating terbukti, jalur PCB
protective earth yang belum terkualifikasi, serta beberapa blocker
clearance/jalur arus. Ini adalah fakta desain yang harus ditutup, bukan bukti
bahwa unit hasil fabrikasi aman atau tidak aman dalam setiap kondisi.

Dokumentasi publik ini tidak menggantikan pengujian board, inspeksi wiring,
atau validasi keselamatan yang sesuai sebelum perangkat dihubungkan ke mains.
