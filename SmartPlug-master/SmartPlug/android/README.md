# SmartPlug Android App

Aplikasi Android native (Kotlin + Jetpack Compose) untuk onboarding, monitoring,
kontrol relay, energi, dan koneksi SmartPlug ke ServerSmartPlug. Kontrak sistem:
[`docs/design.md`](../docs/design.md).

## Status build

Rilis APK dan catatan validasi historis didistribusikan terpisah dari source
repository. Bangun APK sendiri melalui perintah di bawah; jangan menimpa
artifact yang sudah terbukti berjalan ketika membagikan build baru.

## Alur pengguna

### SmartPlug Direct

1. Buka **Perangkat** → **Tambah Perangkat** → **SmartPlug**.
2. Pilih access point `SP-<unit_id>`.
3. Pilih Wi-Fi rumah dan masukkan password.
4. Tunggu perangkat tampil sebagai **Direct**.

Alur ini hanya menghubungkan SmartPlug ke Wi-Fi rumah. Tidak ada pemilihan
ServerSmartPlug pada onboarding Direct; alur Direct dipertahankan terpisah.

### ServerSmartPlug

1. Buka **Perangkat** → **Tambah Perangkat** → **ServerSmartPlug**.
2. Pilih access point `ServerSmartPlug-Setup`, lalu pilih Wi-Fi rumah.
3. Buka detail SmartPlug → menu titik tiga → **Koneksi Server**.
4. Pilih ServerSmartPlug yang telah disimpan. Mode menjadi **Server** hanya
   setelah SmartPlug mengonfirmasi konfigurasi berhasil.

Dialog koneksi server tidak meminta IP, port MQTT, username, password MQTT,
atau API token secara manual. Putuskan koneksi server untuk kembali ke Direct.

### Beberapa HP

HP pertama adalah **owner**. Owner membuat kode lewat **Tambah HP**; HP lain
memilih **Tambah Perangkat** → **SmartPlug yang sudah ada** dan memakai kode
itu untuk menjadi **member**. Member dapat memantau dan mengontrol operasi
normal, tetapi tidak dapat mengubah koneksi server atau credential perangkat.

## Fitur dan batas penting

- Ringkasan Home, monitoring/grafik/riwayat, kontrol relay, reset energi tiga
  konfirmasi, factory reset, timer, Schedule, dan bahasa Indonesia/English.
- Direct memakai REST lokal SmartPlug. Mode Server memakai REST
  ServerSmartPlug dengan snapshot MQTT terakhir.
- Timer/Schedule hanya punya satu authority: SmartPlug pada Direct atau Server
  pada mode Server. Perpindahan mode tidak boleh menggerakkan relay.
- Token owner/member disimpan terenkripsi dan backup/device transfer Android
  dinonaktifkan.
- API lokal memakai HTTP tanpa TLS. Jangan gunakan Wi-Fi yang tidak dipercaya
  untuk password atau telemetry.

## Build dan test

Prasyarat: JDK 17 dan Android SDK sesuai `local.properties`.

```powershell
cd D:\IoT\SmartPlug\android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Output debug Gradle: `app/build/outputs/apk/debug/app-debug.apk`. Beri nama
versi baru untuk setiap artifact yang dibagikan; jangan menimpa APK yang sudah
terbukti berjalan. Test JVM/build tidak menggantikan bukti onboarding AP→LAN,
relay berbeban, SD-card, atau uji listrik fisik.

## Struktur proyek

```text
app/src/main/java/com/smartplug/app/
├── data/       API REST, Room, secure token storage
├── domain/     model dan repository contract
├── di/         dependency injection Hilt
├── ui/         Compose screens, navigation, grafik, localization
└── util/       retry, polling cadence, sound, haptic
```

Ringkasan endpoint: [`API-SUMMARY.md`](API-SUMMARY.md).
