# SmartPlug physical release validation

Status: **belum lengkap**. Dokumen ini adalah gate fisik untuk firmware
`R3.9.3-32k`, ServerSmartPlug `R3.8.4`, dan Android `0.1.31`.

## Bukti yang sudah ada

- Reset global aplikasi, SmartPlug, dan ServerSmartPlug.
- Onboarding server kemudian SmartPlug, serta SmartPlug kemudian server.
- MQTT 500 ms, penyimpanan snapshot SD, dan reconnect setelah reboot kedua
  perangkat pada unit COM6/COM7.
- APK Android 0.1.31 dipasang dan dipakai pada Android 16.

Hasil di atas tidak membuktikan keselamatan mains, akurasi meter, atau
ketahanan flash/SD jangka panjang.

## Perlengkapan wajib

- Fixture terisolasi dengan proteksi arus, MCB/RCD, enclosure, dan emergency
  disconnect. Jangan menyentuh papan saat terhubung ke mains.
- Beban resistif yang diketahui (contoh lampu pijar/heater), alat ukur daya
  referensi, dan termometer/thermal camera bila tersedia.
- SmartPlug, ServerSmartPlug dengan SD card, Android dengan APK v0.1.31, dan
  Wi-Fi rumah uji.

## Gate 1 — Pengukuran dan energi non-nol

1. Hubungkan beban resistif yang diketahui melalui fixture aman.
2. Catat pembacaan referensi dan SmartPlug selama 15 menit.
3. Pastikan tegangan, arus, daya aktif, daya semu, PF, dan energi bernilai
   masuk akal serta energi kumulatif tidak menurun.
4. Periksa aplikasi Direct dan mode Server menampilkan nilai terbaru.
5. Matikan/nyalakan SmartPlug; pastikan kWh terakhir dipulihkan tanpa loncatan
   yang tidak sah.

Lulus bila pembacaan berada dalam toleransi produk yang disetujui dan tidak
ada nilai negatif, NaN, atau reset energi tak diminta.

## Gate 2 — Relay dan kestabilan suplai

1. Dengan beban aman, jalankan setidaknya 100 siklus ON/OFF relay dengan jeda
   yang cukup untuk beban.
2. Catat restart ESP, putus Wi-Fi, MQTT reconnect, dan perubahan relay yang
   tidak diminta.
3. Ulangi pada beban representatif yang telah disetujui secara keselamatan.
4. Periksa suhu relay, terminal, regulator, dan konektor.

Lulus bila tidak ada restart/relay palsu dan temperatur tetap di dalam batas
komponen serta desain enclosure.

## Gate 3 — Ketahanan penyimpanan

1. Jalankan pengukuran berbeban sampai setidaknya dua checkpoint LittleFS
   15-menit dan beberapa snapshot SD terjadi.
2. Lakukan reboot normal SmartPlug serta ServerSmartPlug di antara checkpoint.
3. Lakukan satu uji putus daya terkontrol saat window penulisan SD dan satu
   saat window checkpoint SmartPlug; jangan menguji dengan kabel/papan terbuka.
4. Setelah boot, pastikan server SD masih `sd_ready`, snapshot tersedia, dan
   energi tidak mundur kecuali melalui reset energi tiga konfirmasi.

Lulus bila file snapshot/riwayat tetap dapat dibaca dan tidak ada status
`record_corrupt` atau `write_failed` yang menetap.

## Gate 4 — Soak test dan jaringan

1. Jalankan 24 jam dalam mode Server dengan telemetry 500 ms dan penyimpanan
   SD per 1 detik.
2. Catat reconnect MQTT, reset ESP, error SD, dan gap telemetry lebih dari
   lima detik.
3. Ulangi satu kali kehilangan Wi-Fi rumah lalu pulihkan Wi-Fi.
4. Konfirmasi aplikasi kembali Online tanpa mendaftarkan ulang perangkat.

Lulus bila semua perangkat pulih otomatis dan tidak ada kehilangan data di
luar window yang sudah didokumentasikan.

## Catatan rilis

Sebelum distribusi massal, rekam tanggal, nomor unit, jenis beban, hasil alat
referensi, firmware/APK hash, dan hasil setiap gate. Kegagalan satu gate
memblokir rilis sampai akar masalah diperbaiki dan seluruh gate terkait diulang.
