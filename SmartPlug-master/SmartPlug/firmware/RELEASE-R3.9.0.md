# SmartPlug R3.9.0 - catatan perubahan dan verifikasi

## Perubahan firmware perangkat

- Satu web setup/QC sederhana dengan enam langkah, login eksplisit, logout,
  konfirmasi password, teks bantuan, status Wi-Fi/MQTT aktual, dan versi.
  Input yang sedang diketik tidak lagi ditimpa polling. Polling berurutan
  memiliki timeout dan tidak menumpuk request setiap interval.
- Nilai QC mencakup V, A, W, power factor, Wh. Sampel lebih lama dari 5 detik
  ditandai `fresh:false`; UI menampilkan tanda kosong, bukan angka nol palsu.
  MQTT berhenti mengirim ulang sampel yang sudah stale.
- Admin bootstrap wajib diganti sebelum mutasi konfigurasi/relay. Profil
  `esp07_factory` memakai label/kartu kredensial unik dan terikat STA MAC.
  Build `smartplug_product`/`esp07_rest` adalah kompatibilitas unit lama, bukan
  provisioning unik otomatis untuk setiap unit kosong.
- API pengaturan MQTT GET diperbaiki: tanda kutip `mode` kini lengkap sehingga
  JSON dapat dibaca. Password kosong saat mengedit broker mempertahankan
  password tersimpan. Base topic dengan wildcard ditolak.
- Validasi Wi-Fi, referensi kalibrasi finite/positif dan interval energi.
  Perubahan konfigurasi gagal-commit mengembalikan state RAM/buffer EEPROM.
  Ini bukan rollback flash yang tahan putus daya.
- Legacy kalibrasi `energy_wh / cf_count` ditolak. Engineering API memakai
  `energy_delta_wh` dan `cf_delta` dari interval uji yang sama; field energi
  lama menghasilkan `energy_interval_required`. UI pengguna tidak membuka
  kalibrasi. Verifikasi terhadap meter referensi tetap diperlukan.
- LittleFS tetap default nonaktif, pilihan disimpan dalam EEPROM. Jika aktif,
  checkpoint energi memakai dua record bergantian, version/sequence/CRC32,
  double, dan readback sebelum record baru dinyatakan berhasil. Record lama
  8-byte dapat dibaca. Rentang codec perangkat 0..1e12 Wh; ini bukan rating
  alat ukur atau kemampuan server lama.
- Mount LittleFS tidak melakukan format otomatis. Admin dapat menyiapkan
  storage yang belum siap melalui konfirmasi eksplisit pada halaman web.
  Dua slot tidak menjamin pemulihan bila seluruh filesystem/flash rusak.
- Checkpoint tetap lima menit; energi tidak berubah tidak ditulis ulang.
  Reconnect/sync MQTT tidak memicu commit tiap pesan. Counter server yang
  lebih besar diterapkan tanpa membuang baseline CF sensor berjalan.
- Boot relay menggunakan tiga observasi arus valid berurutan dengan kelas
  sama; sampel invalid membatalkan kandidat. Setelah keputusan, firmware
  meminta satu pulsa ON/OFF. Status baru ditetapkan setelah pulsa selesai;
  request aplikasi yang diterima membatalkan inferensi boot berikutnya.
- Jeda coil minimum 1 detik. Command ke state yang sudah diketahui adalah
  no-op (idempotent). ON/OFF mapping board dipertahankan dari implementasi
  sebelumnya. Pekerjaan jaringan/storage tidak dimulai ketika coil aktif.
- ACK MQTT positif dikirim setelah pulsa selesai, bukan saat baru mulai.
  Status REST menambah `relay.command_result` (`none`, `queued`, `pulsing`,
  `completed`, `rejected`). HTTP 202 tetap berarti antrean, bukan bukti beban.
- Reset konfigurasi menghapus slot MQTT pada kedua profil dalam commit yang
  sama dengan konfigurasi utama, mempertahankan kalibrasi valid, mengembalikan
  akses awal unit, dan menonaktifkan persistensi energi. Record energi lama
  tidak dihapus; reset ini bukan secure erase untuk pindah pemilik.

## Kontrak tambahan

`GET /api/v1/status` menambah `security.password_change_required`,
`security.factory_provisioned`, `integration.connected`, serta
`energy_persistence.ready/write_failed/record_corrupt`.

Endpoint measurement latest/allparameters/per-parameter menambah `fresh` dan
`sample_age_ms`. Field lama dipertahankan. Aplikasi harus memeriksa freshness.

`POST /api/v1/settings/energy-storage/initialize` memerlukan session, CSRF,
persistensi aktif, dan form `confirm=initialize_local_storage`. Hanya tersedia
bila filesystem belum mounted/siap. Endpoint ini melakukan format LittleFS;
record lama terhapus. Bukan reset total energi runtime atau data server.

## Bukti pengujian software

- Native Unity: 38/38 test lulus, termasuk sumber CF tunggal, rollover/reset,
  angka invalid, precision counter, CRC, simulasi record terpotong pada setiap
  panjang, pemilihan slot valid, sequence wrap, freshness, dan keputusan boot.
- Browser menjalankan HTML/JS aktual dari `main.cpp` dengan API simulasi:
  login/ganti password, form tidak tertimpa polling, readback MQTT, tombol
  relay, data stale, jaringan gagal, serta layar 320/390 px tanpa overflow.
- Tiga unit test provisioning: normalisasi STA MAC, penolakan identitas invalid,
  dan bentuk/keunikan sampel password acak.
- Uji class relay aktual dengan clock/GPIO simulasi: lihat `tools/test_relay_host.py`.
- Guide PDF 16 halaman: daftar isi diperiksa terhadap halaman nyata, font
  teks hitam solid, diagram vektor, render setiap halaman, cek batas teks.

## Batas verifikasi / pekerjaan berikutnya

Tidak ada upload, aktivasi relay, sambungan COM, uji AC, uji power-cut, atau
pengukuran akurasi dilakukan pada pekerjaan ini. Build dan mock bukan bukti
alat siap dijual. Lihat `LOCAL-PENDING.md` untuk gate release fisik.

EEPROM konfigurasi masih satu sektor flash. ServerSmartPlug tidak diubah pada
rilis ini: implementasi server R3.8.0 masih menggunakan float dan batas energi
1.000.000 Wh, perlu penyesuaian untuk counter besar/retensi/keamanan broker.
Command MQTT belum memiliki ID transaksi end-to-end; ACK/state jangan diklaim
sebagai feedback kontak relay fisik. Belum ada TLS, signed OTA, atau secure erase.
