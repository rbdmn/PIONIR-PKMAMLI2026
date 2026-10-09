# SmartPlug R3.9.2 - API-only commissioning and persistent local energy

## Perubahan

- Dashboard web, form konfigurasi, grafik, dan asset dashboard tidak ikut
  dikompilasi atau dilayani oleh perangkat.
- Access Point tetap menyediakan REST API JSON yang dipakai aplikasi Android.
- Counter energi lokal LittleFS selalu aktif dan tidak memiliki toggle atau
  field konfigurasi EEPROM.
- Unit baru menyiapkan filesystem energi otomatis bila belum tersedia.
- Factory reset menghapus checkpoint energi LittleFS. Reset dihentikan dan
  melaporkan error bila storage energi tidak dapat dihapus dengan benar.
- Checkpoint tetap 15 menit pada REST langsung dan lima menit pada MQTT.

## Batas verifikasi

Build source membuktikan perubahan dapat dikompilasi. Penghapusan energi saat
factory reset dan ketahanan LittleFS tetap memerlukan uji perangkat fisik.
