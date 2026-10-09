# SmartPlug R3.9.1 - checkpoint energi per mode

## Perubahan

- Counter energi tetap dihitung oleh SmartPlug.
- Persistensi LittleFS tetap opsional dan default nonaktif; pengaturan aktif
  atau nonaktif tetap disimpan di EEPROM.
- Saat persistensi aktif, mode REST langsung membuat checkpoint setiap 15
  menit untuk mengurangi frekuensi tulis flash.
- Saat persistensi aktif, mode MQTT membuat checkpoint setiap lima menit.
  Server tetap menjadi sumber pemulihan energi jangka panjang saat nilainya
  lebih besar dan valid.
- Record LittleFS tetap memakai dua slot bergantian dengan CRC dan verifikasi
  baca setelah tulis.

## Batas verifikasi

Build source membuktikan perubahan dapat dikompilasi. Ketahanan LittleFS dan
perilaku saat listrik putus tetap memerlukan uji perangkat fisik sebelum
dibuat sebagai klaim umur produk.
