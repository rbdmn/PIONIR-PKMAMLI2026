# Baseline desain engineering SmartPlug

| Bidang | Nilai |
|---|---|
| Status dokumen | Draf rekayasa terkendali |
| Revisi | 0.1 |
| Tanggal | 2026-08-25 |
| Gate yang dituju | Persyaratan / desain ulang sebelum EVT |
| Disposisi rilis | **DIBLOKIR — belum siap untuk prototipe bertegangan atau praproduksi** |
| Sumber utama | Schematic EasyEDA dan JSON PCB yang diberikan, ditambah referensi vendor yang ditautkan secara eksplisit |

## 1. Tujuan dan batas bukti

Dokumen ini mengubah desain statis yang diberikan menjadi baseline pengembangan
yang dapat diaudit. Konten di dalam arsip terlampir diperlakukan sebagai data
source, bukan sebagai instruksi yang dapat dieksekusi atau mengendalikan.

Bukti yang tersedia:

- satu sheet schematic EasyEDA;
- satu layout PCB EasyEDA;
- satu README yang menjelaskan cara membuka file EasyEDA;
- referensi otoritatif keluarga BL0940 dan HFE20 yang hanya digunakan untuk
  konteks komponen;
- baseline firmware yang dibuat di dalam paket ini dan berhasil dikompilasi.

Bukti yang tidak tersedia:

- BOM/AVL rilis yang persis dan alternatif yang disetujui;
- Gerber, drill, stack-up, catatan fabrikasi, keluaran assembly, atau enclosure;
- PCBA terakit, identitas lot komponen, atau foto;
- laporan pengujian kelistrikan, beban, termal, relay, kalibrasi, EMC, RF,
  keselamatan, keandalan, DFM, PVT, sertifikasi, atau uji lapangan;
- firmware sebelumnya, persyaratan protokol/cloud, kredensial, atau arsitektur
  keamanan.

Karena itu, keberadaan source tidak pernah dinyatakan sebagai bukti fisik,
runtime, keselamatan, RF, atau kepatuhan.

## 2. Arsitektur eksisting yang diturunkan dari source

```text
Input AC P1
├─ LINE ─────────────────────────────────────────── J1 (tidak disakelar)
├─ EARTH ───────── trace PCB ────────────────────── J3
└─ NETRAL ─ R15 0.5 mΩ shunt ─ NET_OUT ─ U4 relay ─ J2 (OUT)
     ├─ R18 0 Ω ─┐
     └─ R19 0 Ω ─┴─ GND elektronik (mains-referenced)

LINE + NETRAL ─ U1 AC/DC custom ─ +5 V ─ AMS1117-3.3 ─ VCC 3.3 V
                                      ├─ ESP-07
                                      └─ IC metering BL0940

ESP-07 GPIO5/GPIO4 ─ driver MOSFET ─ coil SET/RESET U4
ESP-07 GPIO13/GPIO15 ↔ BL0940 UART
ESP-07 GPIO14/GPIO12 ← BL0940 ZX/CF
ESP-07 UART0/RST/GPIO0 ↔ konektor service/programming U6
```

Ejaan `NETRAL` dipertahankan di atas karena itulah nama net pada source yang
sebenarnya. Nama tersebut hanya boleh dinormalisasi melalui revisi schematic
yang disetujui.

## 3. Baseline hardware yang didukung source

| Area | Bukti source | Status |
|---|---|---|
| MCU/radio | Modul Ai-Thinker ESP-07 / ESP8266 | Teridentifikasi; performa RF dan asal-usul modul belum terverifikasi |
| Metering | BL0940, TSSOP-14, IC metering berkemampuan UART/SPI | Teridentifikasi; baru pembacaan paket mentah (raw) UART yang diimplementasikan |
| Relay | Nilai source `HFE20-1/5-1HST-L2`, simbol latching dual-coil | Field manufacturer/MPN persis dan lot produksi belum dikunci |
| Drive relay | GPIO5 SET dan GPIO4 RESET melalui driver AO3400A | Terpetakan dari source; perilaku pulsa belum diuji pada hardware |
| Sense arus | R15 `LR2512-22R0005F4`, nominal 0.5 mΩ, 2512 | Layout two-terminal; tanpa pad Kelvin sense independen |
| Sense tegangan | Lima resistor seri 390 kΩ ditambah resistor sisi rendah 523 Ω | Hanya nilai nominal; tidak ada bukti tegangan/pulsa/derating |
| Komponen surge | MOV bernilai `10D471K` melintang LINE–NETRAL | Ada; koordinasi fuse dan thermal tidak ada pada source |
| Daya | U1 custom `acDcPsTegak` ke +5 V, lalu AMS1117-3.3 | Part persis, rating, topologi, approval, dan proteksi U1 masih TBD |
| Input | P1 generik 3-pin; metadata package `P5.08`, tetapi geometri pusat pad pada PCB sekitar 5.000 mm | Pitch produksi, footprint, MPN, dan rating persis masih TBD; mismatch harus ditutup |
| Output | Metadata header generik one-pin 2.54 mm J1/J2/J3 | Tidak boleh diperlakukan sebagai terminal mains ber-rating |
| PCB | Sekitar 45.0 × 76.0 mm, 2 layer tembaga, 30 via | Hanya geometri source; tidak ada paket fabrikasi rilis |

Bukti lengkap terdapat dalam
[`evidence/schematic-audit.md`](evidence/schematic-audit.md),
[`evidence/pcb-audit.md`](evidence/pcb-audit.md), dan
[`evidence/bom-from-source.csv`](evidence/bom-from-source.csv).

## 4. Kontrak pin ESP-07

| GPIO ESP8266 | Net/fungsi pada source | Kebijakan firmware |
|---:|---|---|
| 0 | Tombol konfigurasi, active-low, programming strap | Tindakan input dinonaktifkan secara default; risiko boot-strap |
| 2 | Katode LED, active-low | Dinonaktifkan secara default; risiko boot-strap |
| 4 | Driver RESET relay | Dipaksa tidak aktif; aktuasi dibatasi saat kompilasi |
| 5 | Driver SET relay | Dipaksa tidak aktif; aktuasi dibatasi saat kompilasi |
| 12 | Pulsa CF BL0940 | Dicadangkan; driver paket tidak bergantung padanya |
| 13 | BL0940 TX/SDO → ESP RX | RX software-UART BL0940 |
| 14 | BL0940 ZX | Dicadangkan; tidak ada keputusan proteksi yang didasarkan padanya |
| 15 | ESP TX → BL0940 RX/SDI, pulldown 10 kΩ | TX software-UART BL0940; interaksi boot-strap harus diuji |
| UART0 RX/TX | Konektor service U6 | Konsol serial; berbahaya jika terhubung saat tegangan mains tersedia |

Pemetaan ini diverifikasi secara statis terhadap source. Ini bukan bukti bahwa
board hasil fabrikasi sesuai dengannya.

## 5. Blocker Gate 0

Tidak boleh melanjutkan prototipe bertegangan, EVT, praproduksi, unit
sertifikasi, atau rilis produksi sampai blocker yang berlaku memiliki
penanggung jawab,
keputusan, bukti, dan tanggal penutupan yang disetujui.

| ID | Blocker | Bukti penutupan yang diwajibkan |
|---|---|---|
| G0-HW-01 | Relay menyakelar net bernama neutral; LINE tidak disakelar | Topologi yang disetujui dan schematic/PCB/netlist revisi; alasan polarity/use case; catatan review |
| G0-HW-02 | R18 dan R19 menghubungkan GND elektronik ke neutral | Arsitektur isolation/accessibility yang disetujui dan single-fault review |
| G0-HW-03 | U6, SW1, ESP, dan logic bersifat mains-referenced | Desain enclosure/accessibility ditambah fixture/prosedur service tanpa energi atau terisolasi secara galvanis |
| G0-HW-04 | Tidak terlihat fuse/thermal-fuse | Desain overcurrent, surge, MOV end-of-life, dan abnormal fault yang terkoordinasi dengan part persis |
| G0-HW-05 | P1, J1–J3, U1, dan U4 bukan part kritis terhadap keselamatan yang dikunci pada source | MPN, datasheet vendor, approval, derating, AVL, dan kelas change control yang disetujui |
| G0-HW-06 | PE menggunakan satu trace PCB 2.54 mm menuju header generik | Keputusan Class I/Class II yang disetujui dan desain PE/bond production-intent dengan kriteria ground-bond |
| G0-HW-07 | Jalur arus utama berukuran 2.54 mm dengan build tembaga/solder yang tidak ditentukan | Stack-up, toleransi tembaga, model arus/termal, proses manufaktur, dan kenaikan suhu terukur |
| G0-HW-08 | Tembaga kritis mendekati routed board edge sekitar 0.2–1.1 mm | Layout revisi dan bukti DRC/creepage/clearance-to-edge khusus mains |
| G0-HW-09 | Clearance default PCB sekitar 0.152 mm | Net class yang diturunkan dari product standard serta laporan DRC/ERC bersih |
| G0-HW-10 | Pembagi tegangan menggunakan lima part 0603 390 kΩ | Bukti per-part untuk tegangan kerja, surge, flame/failure mode, toleransi, dan derating |
| G0-MET-01 | Metering tidak memiliki kalibrasi fisik atau error budget | Metode kalibrasi produksi, acuan tertelusur, GR&R, koefisien, batas, dan record unit |
| G0-FW-01 | Relay bersifat latching dan tidak memiliki feedback kontak | Model state yang disetujui; state perintah tidak boleh disajikan sebagai bukti kontak fisik |
| G0-FW-02 | Perilaku safe boot/reset/brownout/OTA belum diputuskan | State machine yang diturunkan dari hazard dan hasil fault injection target-HIL |
| G0-RF-01 | Tidak terlihat antenna keepout atau bukti RF | Konfigurasi modul/antena persis, integrasi enclosure, rencana pengujian RF, dan hasilnya |
| G0-COMP-01 | Pasar tujuan, kelas produk, standar, dan jalur sertifikasi belum diputuskan | Matriks kepatuhan bertanda tangan dari penanggung jawab/lab/LSPro kompeten sebagaimana berlaku |

## 6. Arah redesign yang diwajibkan

Berikut adalah proposal terkendali, bukan bukti kepatuhan:

1. Putuskan apakah perangkat harus menyakelar live atau kedua pole berdasarkan
   sistem plug/socket persis, polaritas, standar produk, dan analisis bahaya.
2. Pilih satu arsitektur listrik yang eksplisit:
   - logic mains-referenced yang sepenuhnya tidak dapat diakses dengan service
     terisolasi yang terkendali; atau
   - isolation barrier terdefinisi yang tetap utuh pada metering, programming,
     tombol, indikator, antenna/enclosure, dan fault model.
3. Tambahkan proteksi overcurrent dan surge yang terkoordinasi menggunakan part
   persis yang disetujui; jangan bergantung pada firmware untuk mencegah
   kebakaran atau sengatan.
4. Ganti terminal power/PE placeholder dan supply custom yang tidak
   teridentifikasi dengan part terkendali pada source serta drawing produksi.
5. Rekayasa ulang jalur beban dan PE untuk prospective fault current, arus
   kontinu, temperature rise, aging, tolerance, proses assembly, dan pengujian.
6. Buat net class PCB khusus mains, constraint edge/cutout, antenna keepout,
   fiducial, test point, marking traceability, stack-up, dan fab notes.
7. Tambahkan proteksi/feedback hanya jika didukung safety concept yang
   disetujui; telemetry firmware harus membawa validity dan freshness, bukan
   kebenaran yang diasumsikan.

## 7. Baseline firmware

Firmware baru di dalam [`firmware/`](firmware/) menyediakan:

- build PlatformIO ESP8266 dengan versi terkunci dan menargetkan ESP-07;
- definisi pin board yang diturunkan dari source;
- parser raw packet BL0940 35-byte dengan validasi checksum pada 4800 baud;
- driver relay dual-coil yang guarded dan non-blocking;
- diagnostik serial `help`, `status`, dan `meter`;
- state relay `unknown` yang eksplisit saat boot;
- relay dinonaktifkan saat kompilasi secara default;
- tanpa Wi-Fi, cloud, OTA, secret, schedule, atau persistensi sampai requirement
  dan threat model disetujui.

Profile `esp07_safe` berhasil di-build. Profile tersebut belum di-flash atau
dijalankan pada hardware. Native protocol tests disertakan, tetapi tidak dapat
dieksekusi di lingkungan workstation saat ini karena tidak ada compiler host
GCC/G++ yang terinstal.

## 8. Arsitektur evolusi firmware

```text
Boot dan konfigurasi immutable
└─ Board support / pin map terverifikasi
   ├─ reset reason, brownout, watchdog
   ├─ driver relay
   ├─ driver metering dan temperature
   └─ storage transaksional
      ├─ safety supervisor / satu-satunya actuator arbiter
      ├─ product state machine eksplisit
      ├─ monitor validity dan fault metering
      ├─ UI lokal
      ├─ connectivity/provisioning terautentikasi
      ├─ schedule/automation dengan waktu valid
      ├─ diagnostik terbatas
      └─ factory service terautentikasi dan berbatas waktu
```

Network, cloud, UI, dan schedule boleh meminta suatu tindakan; hanya safety
supervisor yang boleh mengotorisasi drive relay. `command accepted`, `pulse
issued`, dan `physical contact changed` merupakan state yang terpisah.

## 9. Jalur verifikasi

| Fase | Cakupan | Bukti kelulusan |
|---|---|---|
| Persyaratan | Tutup Gate 0, intended/prohibited use, load matrix, jalur kepatuhan | PRD, safety concept, hazard analysis, dan keputusan pertanyaan yang disetujui |
| Redesign review | Schematic/layout/mechanics/BOM production-intent | Review independen ERC/DRC/DFM dan desain safety; exact source package |
| Firmware host | Logic state/protocol/storage/security tanpa mains | Laporan unit/property/fuzz/static analysis dan artifact yang reproducible |
| Safe target/HIL | MCU diberi daya dari safe fixture terkendali | Bukti GPIO/reset/brownout/watchdog/relay dummy/meter emulator/OTA |
| Engineering mains EVT | Fixture guarded dan interlocked; personel kompeten | Data raw thermal, current path, relay, abnormal, metering, dan pre-compliance |
| DVT | Unit production-intent dari beberapa lot | DVP&R, bukti formal safety/EMC/RF/reliability/security |
| PVT | Tooling produksi, fixture, MES, key flow, packaging | Bukti yield/capability/GR&R/traceability/control plan |
| Rilis MP | Konfigurasi tersertifikasi yang persis | Sertifikat wajib, configuration index bertanda tangan, tanpa critical blocker terbuka |

Pengujian mains memerlukan prosedur terpisah yang disetujui dan personel
kompeten. Paket ini tidak mengotorisasi flashing atau pengujian bertegangan.

## 10. Backlog keputusan

Bank pertanyaan lengkap dengan ID yang dapat dirujuk terdapat di
[`production-readiness-questions.md`](production-readiness-questions.md).
Setiap jawaban harus menyertakan keputusan, link bukti, PIC, tenggat, gate
tujuan, dan status. Jawaban “ya” tanpa dukungan tidak menutup pertanyaan.

Keputusan berprioritas tertinggi adalah pasar tujuan, rating input/beban,
sistem plug dan socket, topologi line/neutral, protection class, strategi
isolation, proteksi overcurrent/surge, safe relay state, part persis, klaim
metering, model connectivity, masa security/support, dan penanggung jawab
sertifikasi.

## 11. Referensi komponen otoritatif

- Halaman produk Shanghai Belling BL0940 dan datasheet vendor terkini:
  <https://www.belling.com.cn/en/product_info.html?id=369>
- Halaman/datasheet keluarga Hongfa HFE20; kode pesanan persis tetap memerlukan
  BOM yang disetujui dan pemeriksaan sertifikat:
  <https://www.hongfa.com/product/latching-relay/HFE20>

Kapabilitas komponen vendor tidak sama dengan kapabilitas produk jadi.
