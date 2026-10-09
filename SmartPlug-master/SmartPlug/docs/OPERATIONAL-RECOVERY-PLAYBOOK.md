# SmartPlug Operational Recovery Playbook

Dokumen ini adalah prosedur operator/support untuk SmartPlug, ServerSmartPlug,
dan aplikasi Android. Tujuannya memulihkan layanan tanpa menebak-nebak, tanpa
membocorkan owner/member credential, dan tanpa menjalankan reset sebelum bukti
yang cukup terkumpul.

> Batas keselamatan: jangan membuka enclosure, menyentuh rangkaian, atau
> mencabut/memasang koneksi saat unit terhubung ke mains. Uji relay berbeban,
> energi, dan power-loss mengikuti `PHYSICAL-RELEASE-VALIDATION.md`.

## Data yang dicatat sebelum tindakan

Catat waktu/zona waktu, `device_id`, nama perangkat, mode Direct/Server, SSID
(tanpa password), versi APK/firmware, IP/host terakhir, serta error persis dari
aplikasi. Jangan memasukkan owner/member credential, token ServerSmartPlug,
password Wi-Fi, atau MQTT secret ke tiket, chat, screenshot, maupun log.

## Urutan diagnosis aman

1. Pastikan ponsel memakai Wi-Fi rumah yang sama; hilangkan VPN atau
   isolasi-klien yang diketahui memblokir LAN.
2. Buka detail perangkat dan tunggu dua siklus polling. Direct harus offline
   setelah polling gagal; mode Server harus offline bila server tidak menerima
   telemetry sekitar lima detik.
3. Untuk ServerSmartPlug, baca `GET /health` dari LAN. Minimum yang sehat:
   `web_server_started=true`, `mqtt_broker_started=true`, dan `sd_ready=true`.
   Ini bukan bukti SmartPlug sudah mengirim telemetry.
4. Bila IP SmartPlug berubah, gunakan discovery mDNS aplikasi. Kegagalan mDNS
   bukan alasan untuk factory reset karena multicast dapat diblokir jaringan.
5. Bila HTTP 200 tetapi angka lama/nol, catat status/usia snapshot dan periksa
   SmartPlug ke broker sebelum mengubah konfigurasi atau relay.

## Jalur pemulihan non-destruktif

| Gejala | Tindakan | Eskalasi |
|---|---|---|
| Direct offline setelah IP berubah | Discovery mDNS, lalu validasi `device_id` sebelum memperbarui IP. | Jika mDNS/REST LAN gagal, kumpulkan log; jangan daftarkan sebagai unit baru. |
| Server sehat, SmartPlug offline | Verifikasi Wi-Fi server, lalu tunggu reconnect backoff dan periksa telemetry/broker. | Sesi bench dengan versi firmware dan log serial; jangan reset energi. |
| HP kedua tidak dapat memantau | Jalankan alur **SmartPlug yang sudah ada** dengan invitation baru. | Jangan salin owner token. Owner membuat ulang invitation bila code gagal/kedaluwarsa. |
| Riwayat tidak tersedia | Pastikan `sd_ready=true` dan waktu server tersinkron. `/latest` dapat tetap bekerja. | Jangan format SD sebelum status/file/log dicatat. |
| Relay timeout | Baca ulang status perintah dan perangkat; acceptance bukan bukti kontak sudah settle. | Uji berbeban hanya dengan fixture aman. |

## Batas reset dan otorisasi

| Tindakan | Dampak | Otorisasi dan bukti minimum |
|---|---|---|
| Refresh aplikasi/discovery ulang | Tidak mengubah perangkat atau energi. | Owner/member; catat error dan mode. |
| Disconnect Server satu SmartPlug | Unit tersebut kembali Direct; server/unit lain tidak berubah. | Credential perangkat; pastikan IP dapat dijangkau dan catat server aktif. |
| Reset energi | Counter perangkat berubah; audit server sebelumnya dipertahankan. | Owner; tiga `RESET_ENERGY`, alasan, kWh sebelum reset, waktu. |
| Factory reset SmartPlug | Wi-Fi, credential, profil MQTT, timer/Schedule, checkpoint lokal hilang; kembali setup mode. | Owner/teknisi berotorisasi; tiga `FACTORY_RESET`, identitas unit, keputusan registrasi ulang. |
| Reset aplikasi | Hanya profil lokal; aksi remote harus berhasil terkonfirmasi satu per satu. | Pemilik ponsel; daftar perangkat terdampak dan hasil per perangkat. |
| Reset Server/format SD | Dapat menghapus riwayat, snapshot, konfigurasi server. | Administrator; backup/identifikasi media dan otorisasi eksplisit. |

## Owner-key multi-phone recovery

1. Owner membuat invitation baru pada perangkat yang masih dapat diakses.
2. HP baru memakai alur *SmartPlug yang sudah ada*; hanya profil aman dikirim.
3. HP baru menyimpan member credential di Android Keystore. API token server
   milik HP lain tidak pernah ditransfer.
4. Untuk HP hilang, owner mencabut credential perangkat tersebut dan membuat
   invitation baru jika diperlukan.
5. Bila tidak ada credential tersisa, eskalasi factory reset dengan persetujuan
   eksplisit; recovery tidak boleh membypass autentikasi.

## Paket bukti untuk eskalasi firmware

Sertakan versi/hash artifact, versi APK, Direct/Server, `device_id`, langkah,
waktu, `/health` tanpa token, status aplikasi, dan log serial non-secret. Untuk
MQTT sertakan accepted/denied publish serta alasan terakhir tanpa signing secret
atau payload credential.

## Kriteria penutupan insiden

Insiden ditutup bila unit yang benar kembali normal, status online/offline
konsisten selama observasi yang disepakati, dan tindakan destruktif tercatat.
Satu refresh aplikasi bukan bukti energi, relay, SD persistence, atau recovery
reboot sudah lulus.
