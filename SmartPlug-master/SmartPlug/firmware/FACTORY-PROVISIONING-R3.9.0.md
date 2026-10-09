# Factory provisioning R3.9.0

Dokumen untuk pembuat/pemasang, bukan pengguna akhir. Tidak ada koneksi COM,
upload, atau reset yang dijalankan oleh perintah pembuatan label/build di sini.

## Urutan wajib per unit

1. Baca ID dari halaman setup unit. ID menggunakan **STA MAC lengkap**, bukan
   AP MAC. Contoh format `SP-84F3EB123456`; contoh bukan identitas unit nyata.
2. Jalankan tool label memakai MAC unit tersebut. Gunakan Python dengan
   `reportlab`; runtime dokumen Codex sudah menyediakannya.
3. Tool menghasilkan folder `SP-<MAC>` dengan `factory_profile.h` dan
   `label.html`. Folder yang sudah ada ditolak, sehingga label terbit tidak
   diam-diam diganti dengan password lain.
4. Simpan header/label pada arsip manufaktur yang dibatasi aksesnya. Jangan
   commit ke repository publik. Header dan BIN mengandung kredensial bootstrap
   per unit; ini bukan secure-element storage.
5. Build `esp07_factory` dengan header unit tersebut. Build tanpa header gagal;
   tidak fallback ke password bersama. Firmware menolak mengaktifkan jaringan
   dan fungsi aplikasi bila STA MAC pada perangkat berbeda.
6. Upload hanya lewat prosedur hardware berizin dan fixture yang sesuai.
   Jangan hubungkan programmer non-isolated ke board yang mendapat mains.
7. Pada fixture, pastikan konfigurasi lama tidak menggantikan isi label baru.
   Reset konfigurasi melalui prosedur yang telah diizinkan bila diperlukan.
   Reset konfigurasi mempertahankan koefisien pengukuran yang valid; factory
   defaults awal digunakan bila EEPROM sebelumnya tidak valid.
8. Cetak `label.html` dari browser. Label Wi-Fi boleh ditempel di unit, tetapi
   kartu admin diserahkan terpisah kepada pemilik. Jangan mengirim screenshot
   yang memperlihatkan password/QR ke saluran publik.
9. Uji pindai QR dengan HP aktual, login kartu admin, ganti password, restart,
   dan reset kembali ke label awal pada fixture. Catat hasil per unit.
10. Setiap pembaruan unit berlabel harus dibangun memakai header yang sama.
    Jangan mengganti firmware factory dengan compatibility build: default
    resetnya berbeda dan akan memutus kesesuaian label.

## Contoh perintah PowerShell (ganti MAC contoh)

```powershell
python tools/provision_unit.py --sta-mac 84:F3:EB:12:34:56 --output .factory
$env:SMARTPLUG_FACTORY_PROFILE = (Resolve-Path '.factory/SP-84F3EB123456/factory_profile.h').Path
pio run -e esp07_factory
```

Jalankan dari folder firmware. Simpan output build per unit; build unit lain
akan mengganti `.pio/build/esp07_factory/firmware.bin`.

Password AP dan admin dibuat terpisah memakai generator acak kriptografis,
masing-masing 20 karakter alfanumerik. QR hanya memuat SSID/password Wi-Fi.
Penggantian password admin awal wajib sebelum konfigurasi/QC yang mengubah
state. AP asli tetap cocok dengan QR selama nama/password AP tidak diubah.

## Batas proses yang sudah diverifikasi

Pembuatan profil/label dan compile diuji memakai MAC sintetis
`02:00:00:00:00:01`. BIN fixture itu **bukan untuk alat pengguna**. Pengujian
pindai HP, kecocokan radio MAC, reset aktual, dan sambungan fisik belum dilakukan.
Kredensial MQTT harus diberikan oleh pengelola broker; tool ini belum membuat
akun broker atau ACL per unit secara otomatis.
