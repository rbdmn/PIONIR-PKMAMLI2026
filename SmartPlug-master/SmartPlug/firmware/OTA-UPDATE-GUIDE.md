# Status pembaruan firmware SmartPlug

## Tujuan

OTA tidak didukung pada board saat ini. Flash fisik yang terbaca adalah 512 KB,
sedangkan firmware membutuhkan ruang aplikasi yang tidak menyisakan staging
image kedua. Oleh sebab itu, pembaruan dilakukan melalui U6/serial saja dan
hanya ketika AC serta beban telah dilepas.

## Upload melalui U6, tanpa AC

1. Lepaskan AC dan semua beban dari board.
2. Hubungkan USB-to-TTL 3,3 V ke U6: TX ke RX, RX ke TX, dan GND bersama.
3. Masukkan ESP8266 ke bootloader: tahan GPIO0/SW1 ke GND lalu reset/power-cycle.
4. Build dan upload image dashboard:

   ```powershell
   pio run -e esp07_local -t upload --upload-port COM3
   ```

5. Lepaskan GPIO0 dan reset kembali. LED GPIO2 menyala stabil setelah firmware
   selesai boot.

## Batas dan recovery

- Layout yang dipakai adalah `eagle.flash.512k64.ld`.
- Jangan memakai file `SmartPlug-OTA-Bootstrap-R0.3.0-esp07.bin` lama; itu
  dibuat untuk layout 1 MB dan tidak cocok untuk flash fisik 512 KB.
- Tidak ada jalur pembaruan saat AC aktif. Jika board tidak boot atau AP tidak
  muncul setelah flash, lepaskan AC dan lakukan recovery melalui U6.
