# SmartPlug Wi-Fi Diagnostics

Last updated: 2026-10-07 18:37 WIB  
Last evidence update: 2026-10-07 18:37 WIB  
Last tracker heartbeat: 2026-10-07 18:37 WIB

## Progress dashboard

| Scope | Progress | Status | Note |
|---|---:|---|---|
| **Overall goal** | **100%** | Physical diagnostic evidence captured | Firmware R3.10.11 uploaded and Wi-Fi relay diagnostic reproduced once. |
| Phase 1. Event dan recovery | 100% | Done (source/build) | RTC ring + checkpoint; timing ZX saat pulse dicapture dari ISR. |
| Phase 2. API dan halaman | 100% | Done (source/build) | Owner/session auth, read-only page, cadence 1 detik tanpa overlap. |
| Phase 3. Dokumentasi dan verifikasi | 100% | Done | Dua build lulus, dokumen sinkron, batas fisik dilaporkan. |

## Progress calculation

`40%×100% + 35%×100% + 25%×100% = 100%` untuk scope edit/build; tidak mencakup uji fisik.

| Phase | Weight | Progress | Status | Closure evidence |
|---|---:|---:|---|---|
| 1. Event dan recovery | 40% | 100% | Done (source/build) | 24 event, RTC restore dan checkpoint LittleFS terverifikasi source/build. |
| 2. API dan halaman | 35% | 100% | Done (source/build) | Endpoint terlindungi, halaman memuat data/event dengan target satu refresh per detik. |
| 3. Dokumentasi dan verifikasi | 25% | 100% | Done | Dua build final sukses, tanpa overflow, dokumen sinkron. |

### Phase 1 work items

| ID | Work item | Progress | Status | Next gate | Last update |
|---|---|---:|---|---|---|
| 1.1 | Audit API, relay, MQTT, filesystem dan RTC | 100% | Done | Hasil masuk desain implementasi | Source lokal diperiksa 18:12 WIB. |
| 1.2 | Implementasi ring event dan recovery hemat flash | 100% | Done (source) | Uji fisik retensi di luar scope | RTC/LittleFS A/B, CRC, checkpoint 30 s. |
| 1.3 | Instrumentasi event penting | 100% | Done (source) | Uji fisik event di luar scope | Relay, timer, schedule, Wi-Fi/MQTT, failure dicatat. |

### Phase 2 work items

| ID | Work item | Progress | Status | Next gate | Last update |
|---|---|---:|---|---|---|
| 2.1 | GET /api/v1/diagnostics dengan auth | 100% | Done (source/build) | Uji fisik di luar scope | Owner bearer/session; member ditolak. |
| 2.2 | GET /diagnostics satu refresh per detik | 100% | Done (source/build) | Uji browser fisik di luar scope | `fetch` berikut baru setelah respons sebelumnya. |
| 2.3 | Cek regresi alur yang dilindungi | 100% | Done (source/build) | Uji fisik di luar scope | Jalur API lama tidak diubah; dua profil build lulus. |

### Phase 3 work items

| ID | Work item | Progress | Status | Next gate | Last update |
|---|---|---:|---|---|---|
| 3.1 | Perbarui LOCAL-API.md dan design.md | 100% | Done | Uji fisik di luar scope | Kontrak dan batas retensi terdokumentasi. |
| 3.2 | Build kedua environment dan ukur RAM/flash | 100% | Done | — | Keduanya 458,207/466,928 B; RAM 41,620/81,920 B. |
| 3.3 | Tinjau bukti dan batas uji fisik | 100% | Done | — | Belum upload; AC/relay/browser/retensi fisik belum dibuktikan. |

## Current work / agent state

| Sub-agent / worker | Active work item | Started at | Elapsed | Progress | Activity / state |
|---|---|---|---|---:|---|
| Codex utama | Selesai scope source/build | 2026-10-07 18:12 WIB | 00:10 | 100% | Dua build lulus; tidak ada upload. |

## Issue register

| ID | Status | Evidence | Closure / next evidence gate |
|---|---|---|---|
| WD-01 | Closed for build | Slot firmware R3.10.11: 458,207/466,928 B (98.1%), headroom 8,721 B. | Dua build lulus; ruang kecil perlu diawasi pada revisi berikutnya. |
| WD-02 | Documented limitation | RTC event tidak menulis flash per event; checkpoint LittleFS A/B tiap 30 s. Daya mati total dapat menghilangkan event setelah checkpoint. | Uji daya fisik nanti; jangan klaim jaminan pada power loss total. |
| WD-03 | Deferred | Penyebab restart relay saat 220 VAC belum diuji melalui fixture aman. | Ambil reset reason dan event setelah uji fisik terpisah. |
| WD-04 | Open | OFF relay pulse on AC caused AP loss and reboot; returned diagnostic says `External System`. | Hardware/power/reset-line investigation needed; zero-cross firmware path is already proven active. |

## Evidence log

- 2026-10-07 18:12 WIB: Source menunjukkan API health/status terautentikasi, LittleFS aktif, dan `ESP.rtcUserMemoryRead/Write` tersedia pada core lokal. Belum ada perubahan firmware diagnostics atau upload.
- 2026-10-07 18:14 WIB: Heartbeat tracker terlambat sekitar 1 menit sejak pembaruan awal; pekerjaan tetap aktif. Titik integrasi API, relay, dan boot sudah diidentifikasi.
- 2026-10-07 18:15 WIB: Modul ring 24 event ditulis; menyimpan setiap event di RTC dan checkpoint terverifikasi ke LittleFS tiap 30 detik, tanpa write flash per event. Belum ada bukti build.
- 2026-10-07 18:16 WIB: Hook event relay/timer/schedule/Wi-Fi/MQTT, API autentikasi owner/session, dan halaman read-only ditulis. Belum ada bukti build/runtime.
- 2026-10-07 18:18 WIB: Heartbeat tracker terlambat lebih dari 1 menit selama build. ESP-07 build sukses: flash 457,631/466,928 B (98.0%), RAM 41,404/81,920 B. Build profil kedua berjalan; tidak ada upload.
- 2026-10-07 18:19 WIB: Build upload-profile awal juga sukses identik. Review membetulkan interval ZX agar dicapture tepat saat ISR mulai coil dan memindah checkpoint setelah relay tidak sibuk; kedua build perlu diulang setelah patch ini.
- 2026-10-07 18:20 WIB: Kedua build lulus setelah patch timing: flash 458,027/466,928 B (98.1%), RAM 41,564/81,920 B. Refresh halaman diperbaiki dari jeda pasca-respons menjadi target awal-ke-awal 1 detik; rebuild lagi diperlukan.
- 2026-10-07 18:21 WIB: Heartbeat sedikit lewat 1 menit selama build/review. Status ZX tidak lagi tersangkut `timeout` selamanya; outcome relay mencakup jalur MQTT/deferred. Final build berjalan, ESP-07 link 458,207/466,928 B.
- 2026-10-07 18:22 WIB: Final build kedua environment SUCCESS; flash 458,207/466,928 B (98.1%), RAM 41,620/81,920 B (50.8%). `git diff --check` tanpa whitespace error. Binary kedua profil 462,352 B.
- 2026-10-07 18:25 WIB: Dengan otorisasi pengguna, `smartplug_product_32k_upload` R3.10.11 berhasil di-upload ke COM7. ESP8266EX MAC `18:FE:34:A2:91:B7`; 462,352 B ditulis dan hash upload diverifikasi. Lepas programmer sebelum P1 diberi 220 VAC.
- 2026-10-07 18:37 WIB: Physical Wi-Fi diagnostic with P1 AC: REST OFF command was accepted; zero-cross was valid at about 9.986 ms / 50 Hz and event ring recorded `relay_off_queued`, `zero_cross_waiting`, then `coil_pulse_started`. HTTP transport and SmartPlug AP disappeared immediately. After reconnect, uptime was under one minute and reset reason reported `External System`; no OFF `coil_pulse_completed` event existed. Boot ON pulse after restart completed on valid zero crossing. This proves reset during/just after the OFF coil pulse, but does not distinguish supply sag from reset-line/EMI coupling without electrical measurement.

Protected flows: onboarding SmartPlug, REST aplikasi, MQTT, timer, schedule, owner key, dan kontrol relay R3.10.10 yang sudah berjalan.
