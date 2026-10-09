# Panduan Kerja Tim PIONIR App

Aplikasi Flutter ini menggantikan aplikasi Kotlin lama. **Firmware SmartPlug & ServerSmartPlug
tidak diubah**: aplikasi hanya memanggil REST API yang sudah ada di perangkat.

## Konsep utama: kontrak di tengah

```
Frontend (lib/features/)  ──pakai──►  SmartPlugRepository  ◄──implementasi──  Backend (lib/data/)
                                  (lib/data/smartplug_repository.dart)
                                  + model di lib/models/smartplug_models.dart
```

- Frontend **hanya** memanggil fungsi di `SmartPlugRepository` lewat `smartPlugRepositoryProvider`.
- Backend **hanya** mengisi fungsi-fungsi itu dengan kode asli.
- Saat ini provider memakai `FakeSmartPlugRepository` (data palsu). Setelah backend siap, cukup ganti
  satu baris provider, layar tidak perlu diubah.
- Butuh data/fungsi baru? **Bilang ke arsitek dulu**, jangan ubah `lib/models/` atau interface sendiri.

Alur kerja Git: kerja di branch `frontend/...` atau `backend/...`, pastikan `flutter analyze` dan
`flutter test` lolos, lalu minta arsitek merge ke `main`.

---

## Frontend

**Tugas:** menerjemahkan desain Figma ke layar Flutter di `lib/features/<nama-fitur>/`.

1. Buka `lib/features/home/home_screen.dart`. Itu contoh pola yang bisa ditiru:
   - `StreamProvider` untuk data yang terus berubah (`watchDevices`, `watchReading`).
   - `ref.read(smartPlugRepositoryProvider).setRelay(...)` untuk aksi.
   - `.when(loading:, error:, data:)` untuk menampilkan tiga kondisi.
2. Buat layar sesuai Figma, misalnya Beranda, Daftar Perangkat, Detail/Monitoring, Tambah SmartPlug,
   dan Pengaturan. Satu subfolder per fitur.
3. Wajib mendesain kondisi berikut (semuanya sudah ada di data palsu):
   - **Loading** saat data belum datang.
   - **Offline**: `reading.online == false` (contoh: "Charger Laptop").
   - **Menunggu data**: `reading.measurement == null`.
   - **Error**: tangkap `SmartPlugException`, tampilkan pesan dari `e.code`
     (daftar kode ada di `smartplug_models.dart`). Contoh: pairing dengan password `salah`.
   - **Proses lama**: `setRelay` (~1 detik) dan `finishPairing` (bisa 30–60 detik di alat asli).
4. Energi sudah dalam **kWh**. Tidak perlu konversi dari Wh.
5. Mau menambah kasus data palsu untuk mencoba tampilan? Silakan ubah
   `lib/data/fake_smartplug_repository.dart`.
6. Mau menambah paket (misalnya `fl_chart` untuk grafik atau `go_router`)? Kabari arsitek supaya
   `pubspec.yaml` tidak bentrok.

Jangan import file backend secara langsung. Cukup `smartplug_repository.dart` dan `smartplug_models.dart`.

---

## Backend

**Tugas:** membuat `lib/data/smartplug_api_repository.dart` yang `implements SmartPlugRepository`,
lalu mengganti isi `smartPlugRepositoryProvider` ke kelas itu.

**Acuan:** kontrak API di `SmartPlug-master/SmartPlug/docs/design.md`. Kode Kotlin lama di
`SmartPlug-master/SmartPlug/android/app/src/main/java/com/smartplug/app/`:

| Butuh apa | Lihat file Kotlin |
|---|---|
| Daftar endpoint & body | `data/remote/DeviceApi.kt`, `PairingApi.kt`, `ServerApi.kt` |
| Bentuk JSON | `data/remote/dto/DeviceDto.kt`, `PairingDto.kt` |
| Alur pairing lengkap + retry | `ui/screens/addplug/AddSmartPlugViewModel.kt`, `data/repository/PairingRepositoryImpl.kt` |
| Pindah Wi-Fi ke AP SmartPlug | `data/repository/WifiOnboardingRepositoryImpl.kt` |
| Cari IP lewat mDNS | `data/repository/DiscoveryRepositoryImpl.kt` |
| Polling & retry | `util/Polling.kt`, `util/RetryPolicy.kt` |

**Urutan kerja yang disarankan** (SmartPlug sudah di-factory reset, jadi harus pairing dulu):

1. **Pairing via HTTP (uji cepat).** Sambungkan HP manual ke Wi-Fi `SP-<unit_id>`
   (password `setup-<unit_id>`), **matikan data seluler**, lalu panggil ke `http://192.168.4.1`:
   `GET /api/v1/pair/info` → `POST /pair/scan-wifi` → `POST /pair/configure` → `GET /pair/status`.
   Request setelah `pair/info` wajib membawa header `X-Pairing-Token: <pairing_token>`
   (berlaku 5 menit). Dari status `connected` kamu mendapat `lan_ip` dan `owner_token`
   (hanya dikirim sekali, jadi langsung simpan).
2. **Simpan perangkat + token.** Token disimpan di `flutter_secure_storage` dan **tidak boleh** di-log.
   Daftar perangkat cukup disimpan di penyimpanan lokal sederhana dulu.
3. **`watchReading`.** Ambil data dari `GET /api/v1/status` + `GET /api/v1/measurements/allparameters`
   dengan header `Authorization: Bearer <owner_token>`. Konversi Wh → kWh.
4. **`setRelay`.** Kirim `POST /api/v1/relay` dengan body `{"state":"on"|"off"}`. Respons 202 artinya
   baru **antre**, jadi polling `/status` sampai `relay_state` berubah, baru return.
5. **Pairing otomatis dari aplikasi.** Pindah ke AP tanpa sambung manual memakai MethodChannel ke
   Kotlin di `android/app/src/main/kotlin/...`. Salin logika dari `WifiOnboardingRepositoryImpl.kt`,
   lalu tambahkan izin lokasi/Wi-Fi di `AndroidManifest.xml`.
6. **Mode Server (ServerSmartPlug)** dikerjakan setelah mode Direct stabil.

**Aturan dari firmware yang wajib diikuti:**
- Hanya **satu request aktif** per perangkat. Request berikutnya dikirim setelah yang sebelumnya selesai.
- Timeout HTTP 8 detik. Kalau gagal, beri jeda 2 → 4 → 8 → 15 → 30 detik.
- Tampilkan pengukuran hanya jika `fresh == true`. Kalau tidak, kirim `measurement: null`.
- Perangkat tidak menjawab: tetap emit `PlugReading(online: false, ...)`, jangan lempar error.
- Semua error lain dilempar sebagai `SmartPlugException` dengan kode yang tersedia di kontrak.
- Polling berhenti saat stream di-cancel. Jangan polling di background.
- Password Wi-Fi dan token tidak boleh masuk URL, log, atau layar.
