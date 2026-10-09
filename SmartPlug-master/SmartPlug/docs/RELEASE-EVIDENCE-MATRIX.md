# SmartPlug release evidence matrix

Baseline perangkat yang **sudah dibuktikan upload/install dan postboot
terbatas**: SmartPlug `R3.9.8` di COM7 (artifact SHA-256
`81335E457241049813126FD8126C87AAFCA536D32CC8130F0EEBF0A3B9931686`),
ServerSmartPlug `R3.8.17` di COM6 (artifact SHA-256
`01EEAE922A5226E9254FFE4A102892DC878E11743F4248B5F71794F5E74A7C12`), dan
Android `v0.1.52` / versionCode 53 pada Android 16 (artifact SHA-256
`B4EBE3596D1B46E64CFDBB14A10B5D581180398C3A2A355EDA224872318DF669`).
`R3.9.8` build memakai 444.175 / 466.928 B flash (95,1%) dan 40.276 / 81.920 B
RAM (49,2%); `R3.8.17` memakai 998.305 / 1.310.720 B flash (76,2%) dan 53.520 /
327.680 B RAM (16,3%). Postboot R3.8.17 menunjukkan `GET /health` HTTP 200,
web/MQTT/SD/waktu siap, satu perangkat aktif, 174 received/174 accepted/0
denied MQTT, sync request/snapshot 1/1, dan snapshot write 72 sukses/0 gagal
dengan cadence terakhir 1104 ms. SmartPlug menolak `GET /api/v1/health` tanpa
credential dengan HTTP 401. Bukti ini bukan bukti timer expiry, relay berbeban,
SD fault recovery, offline timer/reset recovery, atau multi-telepon. Status
berlaku untuk bukti yang tercatat pada 2026-10-06.

| Requirement | Evidence | Status | Remaining gate |
|---|---|---|---|
| APK dapat dipasang | Android `v0.1.52` / versionCode 53 dibangun, di-artifact-kan tanpa menimpa rilis sebelumnya, dan dipasang pada Android 16. Cold launch sebelumnya membuktikan satu SmartPlug terdaftar online tanpa membuka detail; Timer tetap tiga-baris terpusat | Proved on test phone for bounded install/cold-launch and timer-wheel visual | Ulang smoke pada kondisi storage rendah/upgrade, lalu jalankan timer Apply/Reset/expiry dengan bukti perangkat |
| Reset global | Tidak ada bukti ulang yang dapat direproduksi terhadap artifact saat ini | Not proved | Reset aplikasi, SmartPlug, dan server pada sesi terkontrol |
| Onboarding server lalu SmartPlug | Tidak ada bukti ulang yang dapat direproduksi terhadap artifact saat ini | Not proved | Run terkontrol dengan log/screenshot dan profile server tersimpan |
| Onboarding SmartPlug lalu server | Tidak ada bukti ulang yang dapat direproduksi terhadap artifact saat ini | Not proved | Run terkontrol dengan log/screenshot dan profile server tersimpan |
| Monitoring server | Android membaca satu `/latest` atomik saat detail terlihat pada target cadence 500 ms, dengan batas request 2 detik. R3.8.17 yang terpasang melaporkan satu perangkat, MQTT 174 accepted/0 denied, dan snapshot write 72 sukses/0 gagal dengan cadence terakhir 1104 ms. Batas wall-to-screen absolut tetap bergantung Wi-Fi/Android | Partially proved on test phone | Multi-device cadence, persistence SD tepat satu detik, dan soak test 24 jam |
| Recovery server | R3.8.17 yang terpasang melewati health postboot: web/MQTT/SD/waktu siap, satu perangkat aktif, 174 accepted/0 denied, dan 72 snapshot write sukses/0 gagal. Source menurunkan `sd_ready` saat write gagal, melakukan remount terbatas tanpa replay state SD stale ke RAM, mempertahankan deadline timer ketika offline/stale tanpa mengirim command atau mengonsumsi retry, dan menahan `awaiting reset` sampai telemetry mengonfirmasi energi ≤1 Wh | Partially proved on test phone; source/build plus bounded postboot device evidence | Restart server dengan snapshot non-nol, kemudian power-loss/fault SD, hot-remove/reinsert, korupsi, offline timer expiry, dan reset-energy recovery |
| Recovery SmartPlug | R3.9.8 yang terpasang membatasi konfigurasi server pada owner/session admin, menghapus automation lokal saat Direct↔MQTT hand-off, dan menolak mutation/eksekusi automation lokal ketika MQTT aktif. Source timer Direct menahan deadline sampai relay OFF dikonfirmasi serta retry terbatas, tetapi expiry/retry belum diuji fisik | Partially proved on test phone; source/build extension | Reboot SmartPlug, verifikasi Wi-Fi/MQTT, energi non-nol, owner/member authorization, dan timer expiry/retry |
| Keterbatasan ruang ESP8266 | `smartplug_product_32k` R3.9.8 build terbaru memakai 444,175 / 466,928 B (95.1%); tidak overflow | Proved build | Uji upload sampel produksi dan capacity check untuk setiap revisi |
| Pengukuran listrik | Sensor menghasilkan sampel tetapi fixture terakhir tanpa AC/beban | Not proved | Beban resistif + meter referensi |
| Relay berbeban | Tidak dieksekusi pada fixture tanpa AC | Not proved | 100 siklus relay berbeban aman |
| KWh non-nol/migrasi | Hanya checkpoint 0 Wh diuji saat migrasi | Not proved | KWh non-nol, reboot, dan recovery |
| SD/LittleFS power-loss | Tidak dieksekusi. R3.8.17 yang terpasang membuktikan SD siap dan 72 snapshot write sukses tanpa kegagalan pada health postboot. Source menahan state RAM live saat hot-remount agar relay/timer stale pada SD tidak diputar ulang; SmartPlug memakai checkpoint LittleFS | Not proved | Putus daya terkontrol ketika write; verifikasi file/record dan fallback tanpa korupsi |
| Endurance jaringan | Uji restart singkat lulus | Not proved | Soak test 24 jam |
| Authorization telemetry SmartPlug | R3.9.8 source requires bearer/session for status, health, and measurements; owner-only MQTT configuration and bounded credential vault compile in `smartplug_product_32k`. Live request tanpa credential menerima HTTP 401 | Partially proved by build plus bounded device evidence | Verify owner HP success, member daily-control access, and member denial for server reconfiguration |
| Multi-phone credentials | Invite/enroll/revoke routes and bounded credential vault implemented; JVM regressions cover member-only credential persistence, expired invitation without persisted state, dan failed revoke without member-list loss | Partially proved by source/unit | Real HP1→HP2 invite, enrolment, expiry/rate-limit, revoke, dan reboot/recovery checks |
| Timer expiry and relay acknowledgement | SmartPlug R3.9.8 dan Server R3.8.17 sudah di-upload. Source SmartPlug keeps a Direct timer deadline until relay reports OFF, retries at 1 s up to three times, and reports terminal `timer_expiry_*`. Server clears a Server timer only after matching accepted OFF ACK, holds expiry while offline/stale without consuming retry, then retries after fresh telemetry at 2 s up to three times. Android blocks zero-duration Apply and closes Timer only after success callback | Partially proved by source/build plus limited postboot device smoke | Controlled human/device Apply → matching OFF ACK → relay OFF → reboot/recovery proof, including offline expiry and recovery; inspect pending/attempt/failure state |
| Home cold-start freshness | Android `v0.1.51` reads `observeDevices().first()` before evaluation. JVM regression, full unit/assembly, install, and a bounded one-device cold launch prove Home online without entering detail | Proved on test phone for the registered one-device cold-launch flow | Repeat across upgrade, no-network, multiple-device, and storage-pressure conditions |

Sebuah requirement berstatus **Not proved** atau **Decision required** memblokir
klaim kesiapan distribusi massal, meskipun build atau demo dasar telah lulus.
