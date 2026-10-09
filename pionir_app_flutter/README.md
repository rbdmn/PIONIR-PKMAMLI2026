# PIONIR App (Flutter)

Aplikasi Android untuk SmartPlug PIONIR. Firmware SmartPlug/ServerSmartPlug **tidak diubah**;
aplikasi mengikuti kontrak API di `../SmartPlug-master/SmartPlug/docs/design.md`
(ringkasan endpoint: `../SmartPlug-master/SmartPlug/android/API-SUMMARY.md`).
Aplikasi Kotlin lama di `../SmartPlug-master/SmartPlug/android` dipakai sebagai acuan perilaku.

## Menjalankan

```
flutter pub get
flutter test
flutter run
```

Build pertama butuh Android NDK **28.2.13676358** (Android Studio → SDK Manager → SDK Tools →
centang *Show Package Details* → NDK (Side by side)).

## Pembagian folder

| Folder | Pemilik | Isi |
|---|---|---|
| `lib/models/` | Arsitek | Kontrak data bersama. Ubah hanya lewat kesepakatan tim. |
| `lib/data/smartplug_repository.dart` | Arsitek | Kontrak fungsi (interface) + titik tukar palsu/asli. |
| `lib/data/` (file lain) | Backend | Implementasi asli: HTTP, pairing Wi-Fi, token, penyimpanan. |
| `lib/data/fake_smartplug_repository.dart` | Frontend | Data palsu; boleh ditambah kasus untuk mencoba tampilan. |
| `lib/features/` | Frontend | Layar dari Figma, satu subfolder per fitur. |
| `android/` | Backend | Izin & kode Kotlin native (pairing Wi-Fi). |

Frontend cukup memakai `smartPlugRepositoryProvider`. Saat backend siap, ganti isi provider itu
di `lib/data/smartplug_repository.dart` ke implementasi asli; layar tidak perlu diubah.
Butuh fungsi/data baru? Usulkan dulu perubahannya di `lib/models/` atau interface repository.

## Git

- `main` selalu bisa di-build. Kerja di branch sendiri bila perlu: `frontend/...`, `backend/...`.
- Sebelum merge ke `main`: `flutter analyze` dan `flutter test` harus lolos.
- Jangan commit `build/`, `.dart_tool/`, `local.properties` (sudah di `.gitignore`).
