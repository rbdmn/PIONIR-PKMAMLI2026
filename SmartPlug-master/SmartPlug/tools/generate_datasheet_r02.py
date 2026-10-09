from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    BaseDocTemplate, Frame, KeepTogether, PageBreak, Paragraph, Spacer,
    Table, TableStyle, PageTemplate,
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output' / 'pdf' / 'SmartPlug-Engineering-Datasheet-Production-Control-R0.2.pdf'
OUT.parent.mkdir(parents=True, exist_ok=True)

PAGE_W, PAGE_H = A4
NAVY = colors.HexColor('#12233D')
BLUE = colors.HexColor('#0B5CAB')
TEAL = colors.HexColor('#007D78')
GREEN = colors.HexColor('#16734E')
AMBER = colors.HexColor('#9A6300')
RED = colors.HexColor('#B4232B')
INK = colors.HexColor('#1E293B')
MUTED = colors.HexColor('#536273')
LINE = colors.HexColor('#D7DEE8')
PALE_BLUE = colors.HexColor('#EDF5FF')
PALE_AMBER = colors.HexColor('#FFF5DF')
PALE_RED = colors.HexColor('#FFF0F1')
PALE_GREEN = colors.HexColor('#EAF7F0')

FONT_PATHS = [
    Path('C:/Windows/Fonts/arial.ttf'),
    Path('C:/Windows/Fonts/Arial.ttf'),
]
for p in FONT_PATHS:
    if p.exists():
        pdfmetrics.registerFont(TTFont('DocSans', str(p)))
        break
else:
    raise RuntimeError('Arial font tidak ditemukan.')

styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name='DocTitle', fontName='DocSans', fontSize=24, leading=29, textColor=NAVY, spaceAfter=7))
styles.add(ParagraphStyle(name='SubTitle', fontName='DocSans', fontSize=10.5, leading=15, textColor=MUTED, spaceAfter=10))
styles.add(ParagraphStyle(name='H1x', fontName='DocSans', fontSize=16, leading=20, textColor=NAVY, spaceBefore=5, spaceAfter=8))
styles.add(ParagraphStyle(name='H2x', fontName='DocSans', fontSize=11.5, leading=15, textColor=BLUE, spaceBefore=8, spaceAfter=5))
styles.add(ParagraphStyle(name='Bodyx', fontName='DocSans', fontSize=8.7, leading=12.2, textColor=INK, spaceAfter=5))
styles.add(ParagraphStyle(name='Smallx', fontName='DocSans', fontSize=7.4, leading=10, textColor=MUTED))
styles.add(ParagraphStyle(name='Tinyx', fontName='DocSans', fontSize=6.7, leading=8.4, textColor=MUTED))
styles.add(ParagraphStyle(name='TableHead', fontName='DocSans', fontSize=7.1, leading=8.8, textColor=colors.white))
styles.add(ParagraphStyle(name='TableCell', fontName='DocSans', fontSize=7.1, leading=9.1, textColor=INK))
styles.add(ParagraphStyle(name='TableCellSmall', fontName='DocSans', fontSize=6.5, leading=8.1, textColor=INK))
styles.add(ParagraphStyle(name='CenterSmall', fontName='DocSans', fontSize=7.4, leading=9.5, textColor=MUTED, alignment=TA_CENTER))

def P(text, style='Bodyx'):
    return Paragraph(text, styles[style])

def table(rows, widths, header=True, small=False):
    body_style = 'TableCellSmall' if small else 'TableCell'
    data = []
    for r_i, row in enumerate(rows):
        style = 'TableHead' if header and r_i == 0 else body_style
        data.append([P(str(cell), style) for cell in row])
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0, hAlign='LEFT')
    commands = [
        ('GRID', (0, 0), (-1, -1), 0.35, LINE),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 5),
        ('RIGHTPADDING', (0, 0), (-1, -1), 5),
        ('TOPPADDING', (0, 0), (-1, -1), 4),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
    ]
    if header:
        commands += [('BACKGROUND', (0, 0), (-1, 0), NAVY), ('VALIGN', (0, 0), (-1, 0), 'MIDDLE')]
    t.setStyle(TableStyle(commands))
    return t

def band(text, bg, fg=INK):
    t = Table([[P(text, 'Bodyx')]], colWidths=[174 * mm])
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), bg),
        ('BOX', (0, 0), (-1, -1), 0.5, fg),
        ('LEFTPADDING', (0, 0), (-1, -1), 8), ('RIGHTPADDING', (0, 0), (-1, -1), 8),
        ('TOPPADDING', (0, 0), (-1, -1), 7), ('BOTTOMPADDING', (0, 0), (-1, -1), 7),
    ]))
    return t

def bullet(text):
    return P('&bull; ' + text)

def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(LINE); canvas.setLineWidth(0.5)
    canvas.line(18 * mm, 13 * mm, 192 * mm, 13 * mm)
    canvas.setFont('DocSans', 7)
    canvas.setFillColor(MUTED)
    canvas.drawString(18 * mm, 8.5 * mm, 'SmartPlug - Engineering Datasheet & Production Release Control - R0.2 - 2026-08-28')
    canvas.drawRightString(192 * mm, 8.5 * mm, f'Halaman {doc.page}')
    canvas.restoreState()

doc = BaseDocTemplate(
    str(OUT), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
    topMargin=17 * mm, bottomMargin=18 * mm,
    title='SmartPlug Engineering Datasheet and Production Release Control R0.2',
    author='SmartPlug Engineering', subject='Evidence-bounded production control datasheet',
)
frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id='main')
doc.addPageTemplates([PageTemplate(id='main', frames=[frame], onPage=footer)])

story = []
story += [P('SMARTPLUG', 'DocTitle'), P('Engineering Datasheet & Production Release Control', 'H1x')]
story += [P('Revisi R0.2 | 28 Agustus 2026 | Status: engineering-controlled, belum diotorisasi untuk produksi, penjualan, energisasi mains, atau sertifikasi.', 'SubTitle')]
story += [band('<b>STATUS KRITIS - BUKAN DATASHEET PRODUK JADI.</b><br/>Dokumen ini matang sebagai kontrol engineering dan gerbang rilis produksi. Ia tidak menetapkan rating produk yang tidak didukung bukti. Semua parameter berstatus TBD harus ditutup oleh evidence yang sesuai sebelum digunakan pada label, penawaran, manual, atau sertifikasi.', PALE_RED, RED), Spacer(1, 8)]
story += [P('1. Identitas dan kendali konfigurasi', 'H1x')]
story += [table([
    ['Bidang', 'Nilai terkendali'],
    ['Nama kerja', 'SmartPlug'],
    ['Target penggunaan yang diusulkan', 'Pemantauan energi AC single-phase dan integrasi local-first SmartPlug. Intended use, pasar, sistem plug/socket, dan kategori beban belum disetujui.'],
    ['Hardware sumber', 'EasyEDA smartPlug2Ver2 / smartPlug2; PCB nominal sekitar 45.0 x 76.0 mm, 2 layer.'],
    ['MCU/radio', 'Ai-Thinker ESP-07 / ESP8266, teridentifikasi statis dari source.'],
    ['Metering', 'BL0940 TSSOP-14, dibaca firmware sebagai paket raw UART 35-byte pada 4800 baud.'],
    ['Baseline firmware', 'smartplug-bringup 0.1.0-safe; target PlatformIO esp07; aktuasi relay, Wi-Fi, OTA, LED, tombol, dan konversi unit dinonaktifkan default.'],
    ['Sumber bukti primer', 'Arsip SmartPlugV2, skematik dan PCB EasyEDA yang dipreservasi. Tidak ada BOM rilis, Gerber, enclosure, hasil uji, sertifikat, atau firmware asal dalam arsip.'],
], [43 * mm, 131 * mm])]
story += [Spacer(1, 7), P('Interpretasi bukti', 'H2x'), bullet('Source desain membuktikan intent statis, bukan unit fisik, perilaku runtime, keselamatan, performa RF, metering, atau kepatuhan.'), bullet('Tidak ada nilai TBD yang boleh diisi dengan asumsi, rating komponen tunggal, atau hasil simulasi tanpa metode dan unit bukti yang disetujui.'), bullet('Jika source, BOM, PCB, firmware, atau pasar berubah, lakukan impact assessment dan revisi dokumen ini sebelum release berikutnya.'), PageBreak()]

story += [P('2. Ringkasan kemampuan, batasan, dan klaim yang dilarang', 'H1x')]
story += [table([
    ['Area', 'Dapat ditelusuri dari source/baseline', 'Tidak boleh diklaim saat ini'],
    ['Pemantauan listrik', 'BL0940, shunt nominal 0.5 mOhm, divider 5 x 390 kOhm, dan field raw firmware teridentifikasi.', 'Volt, Ampere, Watt, PF, Wh/kWh, akurasi, resolusi, drift, rentang, atau interval kalibrasi produk.'],
    ['Kontrol beban', 'Relay latching single-pole dual-coil dan driver GPIO4/GPIO5 terpetakan.', 'Kapasitas beban, life, kategori beban, posisi kontak aktual, pemutusan aman, atau fungsi proteksi.'],
    ['Konektivitas', 'ESP-07/ESP8266 hadir. Wi-Fi belum diaktifkan pada baseline.', 'Jangkauan Wi-Fi, channel/region, keamanan, REST API rilis, OTA, cloud, atau sertifikasi RF.'],
    ['Keselamatan', 'MOV 10D471K dan diode flyback terlihat dalam source.', 'SELV, isolation class, protection class, fuse coordination, surge immunity, PE continuity, IP, atau sertifikasi.'],
    ['Produksi', 'Sumber PCB dan firmware baseline tersedia.', 'DFM/DFA, assembly release, yield, traceability, process capability, atau readiness produksi.'],
], [30 * mm, 71 * mm, 73 * mm], small=True), Spacer(1, 8)]
story += [band('<b>Aturan komunikasi produk:</b> status perintah relay, pulse yang dikeluarkan, dan perubahan kontak fisik adalah tiga state berbeda. Board ini tidak memiliki feedback kontak; firmware tidak boleh mengubah salah satu state menjadi klaim state lainnya.', PALE_AMBER, AMBER), Spacer(1, 8)]
story += [P('Konfigurasi SmartPlug yang realistis', 'H2x')]
story += [table([
    ['Fitur SmartPlug', 'Status pada device sekarang', 'Lokasi implementasi yang benar'],
    ['Monitoring V/A/W/PF/Wh', 'Bisa setelah kalibrasi dan validasi metering.', 'Device mengirim raw/hasil terkalibrasi; aplikasi menampilkan.'],
    ['REST lokal dan Wi-Fi tanpa internet', 'Bisa dikembangkan pada ESP8266; belum ada di baseline.', 'Firmware + aplikasi Android; security/provisioning harus disetujui.'],
    ['Grafik, histori, biaya, rekomendasi', 'Bisa.', 'Aplikasi Android; jangan bergantung pada storage board 1 MB untuk histori produk.'],
    ['Deteksi standby/vampire', 'Logika dapat dibuat.', 'Firmware/aplikasi; auto-cut fisik tetap diblokir hardware safety.'],
    ['MicroSD, harmonik, phase imbalance', 'Tidak didukung board sekarang.', 'Perlu hardware berbeda.'],
], [42 * mm, 56 * mm, 76 * mm], small=True), PageBreak()]

story += [P('3. Arsitektur hardware yang teramati', 'H1x')]
story += [P('Diagram berikut hanya menggambarkan konektivitas yang terbaca dari source, bukan arsitektur safety yang disetujui.', 'Smallx')]
diagram = Table([
    [P('<b>INPUT AC P1</b><br/>P1.3 LINE<br/>P1.2 NETRAL<br/>P1.1 EARTH', 'TableCell'), P('<b>JALUR BEBAN</b><br/>LINE -> J1 langsung<br/>NETRAL -> R15 -> NET_OUT -> relay U4 -> OUT -> J2<br/>EARTH -> trace -> J3', 'TableCell')],
    [P('<b>POWER</b><br/>U1 AC/DC custom -> +5 V<br/>AMS1117-3.3 -> VCC', 'TableCell'), P('<b>LOGIC / METERING</b><br/>ESP-07 + BL0940<br/>R18/R19 0 Ohm mengikat GND ke NETRAL', 'TableCell')],
    [P('<b>RELAY DRIVER</b><br/>GPIO5 SET, GPIO4 RESET<br/>AO3400A + diode flyback', 'TableCell'), P('<b>SERVICE</b><br/>U6: VCC, GND, RXD, TXD, RST, GPIO0<br/>Mains-referenced sampai desain revisi membuktikan sebaliknya', 'TableCell')],
], colWidths=[87 * mm, 87 * mm])
diagram.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,-1), PALE_BLUE), ('GRID', (0,0), (-1,-1), 0.6, BLUE),
    ('VALIGN', (0,0), (-1,-1), 'TOP'), ('LEFTPADDING', (0,0), (-1,-1), 8), ('RIGHTPADDING', (0,0), (-1,-1), 8),
    ('TOPPADDING', (0,0), (-1,-1), 8), ('BOTTOMPADDING', (0,0), (-1,-1), 8),
]))
story += [diagram, Spacer(1, 8)]
story += [P('Pin contract ESP-07', 'H2x')]
story += [table([
    ['GPIO', 'Fungsi source', 'Kebijakan R0.2'],
    ['GPIO0', 'Tombol active-low; boot strap; U6', 'Tidak memicu switching. Risiko strap boot wajib ditutup dalam HIL.'],
    ['GPIO2', 'LED active-low; boot strap', 'Dinonaktifkan default; pull-up efektif wajib diuji.'],
    ['GPIO4 / GPIO5', 'RESET / SET coil relay', 'Dipaksa nonaktif saat setup; aktuasi compile-time disabled.'],
    ['GPIO12 / GPIO14', 'CF / ZX BL0940', 'Dicadangkan; bukan dasar keputusan proteksi.'],
    ['GPIO13 / GPIO15', 'BL0940 TX/SDO dan RX/SDI', 'Software UART 4800 baud; GPIO15 adalah boot strap.'],
    ['UART0', 'Service U6', 'Hanya diagnostik; tidak aman terhubung saat mains tersedia.'],
], [22*mm, 58*mm, 94*mm], small=True), Spacer(1, 8)]
story += [P('Fakta safety source yang mengubah seluruh disposisi', 'H2x'), bullet('Relay pada source menyakelar net bernama NETRAL; LINE tetap menuju J1. Koreksi harus pada topologi dan netlist fisik, bukan hanya label.'), bullet('R18 dan R19 masing-masing 0 Ohm menghubungkan GND elektronik ke NETRAL. Logic, service connector, switch, dan ESP harus diperlakukan hazardous live/mains-referenced.'), bullet('Tidak terlihat fuse, fusible resistor, thermal fuse, thermal disconnect MOV, contact feedback, atau konektor mains ber-rating yang terkunci.'), PageBreak()]

story += [P('4. Parameter teknis dan status rilis', 'H1x')]
story += [P('Tabel ini sengaja menggunakan status rilis, bukan angka spekulatif. Parameter dengan status TBD adalah requirement release dan tidak boleh disalin sebagai spesifikasi produk.', 'Bodyx')]
story += [table([
    ['Parameter', 'Nilai yang boleh diterbitkan', 'Status / bukti yang diperlukan sebelum rilis'],
    ['Tegangan dan frekuensi input', 'TBD', 'Pilih pasar, plug/socket, OVC, pollution degree, altitude, safety concept, test unit production-intent.'],
    ['Arus kontinu / daya output', 'TBD', 'Exact terminal/relay/shunt/PCB stack-up, thermal rise, abnormal load, endurance, manufacturing tolerance.'],
    ['Jenis beban dan inrush', 'TBD', 'Load matrix resistif/induktif/kapasitif/motor/heater, suppression, relay test report.'],
    ['Konsumsi standby', 'TBD', 'Pengukuran pada beberapa unit, metode, kondisi Wi-Fi dan toleransi mains yang dikunci.'],
    ['Akurasi V/I/P/PF/E', 'TBD', 'Calibration procedure, traceable reference, uncertainty budget, GR&R, temperature/load sweep.'],
    ['Suhu, RH, altitude, IP', 'TBD', 'Enclosure final, material, thermal/environmental qualification.'],
    ['Imunitas / emisi EMC', 'TBD', 'Target standard dan laporan pre-compliance/formal pada konfigurasi rilis.'],
    ['Radio Wi-Fi', 'ESP-07 exists; no product radio claim', 'Exact module/antenna/enclosure/region, RF test and certification path.'],
    ['Reliability / relay endurance', 'TBD', 'Exact relay MPN, load profile, cycling, contact behavior, test samples.'],
    ['Ingress / electrical protection', 'No claim', 'Fuse coordination, MOV end-of-life strategy, hipot, leakage/touch current, abnormal-fault evidence.'],
], [38*mm, 48*mm, 88*mm], small=True), Spacer(1, 9)]
story += [P('Nominal calculations - engineering only', 'H2x')]
story += [table([
    ['Item source', 'Nominal derivation', 'Use restriction'],
    ['R15 0.5 mOhm shunt', '10 A -> 5 mV / 50 mW; 16 A -> 8 mV / 128 mW; 20 A -> 10 mV / 200 mW.', 'Not a current rating. Excludes PCB, pad, TCR, pulse, thermal, solder, and calibration.'],
    ['Voltage divider', '5 x 390 kOhm series + 523 Ohm lower leg. At 230 V RMS: approx. 117.9 uA divider current and 61.7 mV VP RMS.', 'Not a voltage rating. Working voltage, surge/pulse, creepage, tolerance, and failure mode open.'],
], [40*mm, 64*mm, 70*mm], small=True), Spacer(1, 8)]
story += [band('<b>Release rule:</b> component datasheet ratings may be inputs to design verification, but are never evidence that the finished SmartPlug has the same rating.', PALE_AMBER, AMBER), PageBreak()]

story += [P('5. Firmware baseline and production firmware contract', 'H1x')]
story += [table([
    ['Function', 'Baseline R0.2 fact', 'Production requirement'],
    ['Boot and relay', 'SET/RESET driven inactive in setup; state starts unknown; relay actuation disabled by SMARTPLUG_ALLOW_RELAY_ACTUATION=0.', 'Safety state machine, reset/brownout/watchdog/fault injection evidence, actuator authorization, no unsafe assumed state.'],
    ['BL0940 protocol', 'Checksum-validated 35-byte full packet, UART 4800 baud, raw fields exposed; nonblocking receive avoids stretching pulse.', 'Calibration coefficients/versioning, freshness, validity, uncertainty, negative/overflow behavior, HIL and target test.'],
    ['Serial diagnostics', 'UART0 at 115200: help, status, meter; relay command needs compile enable + confirmation token.', 'Disable or authenticate production service; physical isolation, role control, audit record, recovery procedure.'],
    ['Network / REST / OTA', 'Not implemented and Wi-Fi unused in default build.', 'Threat model, authenticated provisioning/control, secure update chain, rollback, key lifecycle, resource/load test.'],
    ['Persistence / time', 'No persistence; no RTC.', 'Explicit ownership of energy counter, wear budget, loss/recovery behavior, time source, clock validity, retention test.'],
], [36*mm, 65*mm, 73*mm], small=True), Spacer(1, 7)]
story += [P('Required production telemetry semantics', 'H2x')]
story += [table([
    ['Field family', 'Must carry', 'Must not imply'],
    ['Measurement', 'value, unit, calibration profile/version, validity, age/freshness, error/fault state', 'Raw value is calibrated; last valid sample is current; detected means accurate.'],
    ['Relay', 'command requested, authorization result, pulse issued, commanded RAM state, contact feedback availability=false', 'Mechanical contact state or safe isolation when no feedback exists.'],
    ['Device health', 'reset reason, firmware build ID, config schema, storage/time/network validity, critical fault latch', 'Normal operation from absence of a single error.'],
], [38*mm, 76*mm, 60*mm], small=True), Spacer(1, 7)]
story += [P('Security minimum for local-first SmartPlug', 'H2x'), bullet('Local-only does not mean unauthenticated. Device identity, pairing/provisioning, access control, replay resistance, factory recovery, and update authorization must be defined before control endpoints exist.'), bullet('Do not expose an open relay endpoint. Present hardware safety blockers additionally prohibit physical relay actuation.'), bullet('Energy history and recommendation engine belong primarily to Android/local controller unless a constrained, wear-budgeted device store is validated.'), PageBreak()]

story += [P('6. Production release configuration index', 'H1x')]
story += [P('No production unit may be released without one signed configuration index binding every item below to a revision, owner, approval, and immutable evidence location.', 'Bodyx')]
story += [table([
    ['Configuration item', 'Minimum controlled content', 'Release evidence'],
    ['Product definition', 'Part number, hardware revision, intended/prohibited use, target market, plug/socket system, load matrix.', 'Approved PRD and hazard analysis.'],
    ['Electrical design', 'Schematic, PCB, netlist, stack-up, net classes, creepage/clearance, isolation concept, derating.', 'Independent design review, clean ERC/DRC, approved drawings.'],
    ['BOM / AVL', 'Exact MPN, manufacturer, lifecycle, approval marks, alternates, change classification.', 'Released BOM, vendor datasheets, supply-chain approval.'],
    ['Mechanical', 'Enclosure, material/flame class, touch protection, labels, PE method, antenna location, assembly torque.', 'Released drawings, tolerance stack-up, material and qualification evidence.'],
    ['Firmware', 'Source commit, reproducible toolchain, binary hash, config schema, SBOM/dependency list, secure update policy.', 'Build log, static/unit/HIL results, security review.'],
    ['Manufacturing', 'Gerber/drill, fabrication notes, centroid, stencil, panel, work instruction, fixture, limits.', 'FAI, DFM/DFA, gauge R&R, PFMEA, control plan.'],
    ['Quality / compliance', 'DVP&R, sampling, acceptance criteria, deviations, certificates, label claims.', 'Signed test reports on exact released configuration.'],
    ['Traceability', 'Unique ID, lot/date/PCB rev, calibration record, firmware build, test record, rework history.', 'Readback and retention procedure validated.'],
], [34*mm, 76*mm, 64*mm], small=True), Spacer(1, 8)]
story += [P('Mandatory label content once ratings are verified', 'H2x')]
story += [bullet('Product PN and hardware revision; serial/lot/date or traceable 2D code.'), bullet('Verified input/output ratings, applicable warnings, protection class/PE markings, and only verified approval marks.'), bullet('Safety and service statements consistent with final isolation architecture; no user-accessible programming interface warning ambiguity.'), bullet('Firmware/configuration identification must be retrievable without connecting a grounded tool to a live board.'), PageBreak()]

story += [P('7. Gate 0 blockers and closure evidence', 'H1x')]
story += [table([
    ['ID', 'Blocker observed', 'Minimum closure evidence'],
    ['G0-HW-01', 'Relay source switches NETRAL while LINE is directly connected to J1.', 'Approved topology and terminal mapping; revised schematic/PCB/netlist; physical wiring verification; safety review.'],
    ['G0-HW-02', 'R18/R19 tie GND to NETRAL; logic and service are mains-referenced.', 'Approved insulation/accessibility architecture, single-fault analysis, enclosure/service fixture evidence.'],
    ['G0-HW-03', 'No visible coordinated fuse, thermal fuse, or MOV thermal protection.', 'Fault-energy/fuse/MOV coordination, exact parts, abnormal-fault test, released design.'],
    ['G0-HW-04', 'P1, J1-J3, U1, U4 have incomplete identity/rating control.', 'Exact MPN/AVL/datasheet/approval/lifecycle and production drawing verification.'],
    ['G0-HW-05', 'PE is a 2.54 mm PCB trace to generic header.', 'Class I/II decision; PE/bond design; ground-bond and fault-current qualification.'],
    ['G0-HW-06', 'PCB default clearance approx. 0.152 mm and NET_OUT edge margin approx. 0.265 mm.', 'Standard-derived constraints, revised layout, DRC report, manufacturing tolerance evidence.'],
    ['G0-MET-01', 'No calibrated measurement or error budget.', 'Traceable calibration, uncertainty/GR&R, temperature/load validation, per-unit record.'],
    ['G0-FW-01', 'Latching relay has no contact feedback.', 'Approved state model, fault behavior, UI/API wording; optional hardware feedback if requirement needs it.'],
    ['G0-RF-01', 'No antenna keepout/integration evidence.', 'Exact module antenna rule, enclosure integration review, RF verification.'],
    ['G0-COMP-01', 'Market, standard, certification route unselected.', 'Compliance matrix approved by accountable product owner and competent lab/certification route.'],
], [21*mm, 65*mm, 88*mm], small=True), Spacer(1, 7)]
story += [band('<b>Gate discipline:</b> a blocker is closed only by an owner-approved decision plus linked evidence on the exact configuration. A verbal decision, an assumed component rating, or a successful compile does not close a production gate.', PALE_RED, RED), PageBreak()]

story += [P('8. Verification and validation matrix', 'H1x')]
story += [table([
    ['Phase', 'Scope', 'Representative exit evidence'],
    ['Requirements / architecture', 'Intended use, hazards, safety concept, load matrix, market/standards.', 'Approved PRD, hazard/risk analysis, compliance matrix, Gate 0 ownership.'],
    ['Design review', 'Schematic, PCB, enclosure, BOM/AVL, DFM, testability.', 'ERC/DRC, peer review records, stack-up, drawings, fabrication/assembly package.'],
    ['Firmware host', 'Protocol parsing, state machine, persistence, API schema, security behavior.', 'Unit/property/fuzz/static analysis, reproducible build, review report.'],
    ['Safe target / HIL', 'GPIO boot/reset/brownout, relay dummy load, meter emulator, service isolation.', 'Target logs, fixture records, fault injection results.'],
    ['EVT mains', 'Thermal, current path, relay, metering, abnormal behavior, pre-compliance.', 'Controlled lab procedures and raw test data on identified units.'],
    ['DVT', 'Safety, EMC, RF, reliability, environmental, calibration, user interface.', 'DVP&R passed on production-intent lots; formal reports where applicable.'],
    ['PVT', 'Manufacturing fixture, process capability, programming/calibration, label/packaging.', 'FAI, yield/capability, GR&R, control plan, traceability audit.'],
    ['Mass production', 'Certified configuration, change control, field update/support.', 'Release sign-off, certificate mapping, golden samples, ongoing quality plan.'],
], [31*mm, 68*mm, 75*mm], small=True), Spacer(1, 8)]
story += [P('Minimum tests that cannot be replaced by firmware', 'H2x')]
story += [bullet('Ground-bond/PE continuity where Class I applies; dielectric/hipot and leakage/touch-current appropriate to final architecture.'), bullet('Temperature rise, overload, short/abnormal operation, relay endurance across defined load categories, and terminal retention/torque.'), bullet('Surge, EFT, ESD, dip/interruption, conducted/radiated emissions and immunity, plus RF integration where claims/market require them.'), bullet('Metering accuracy and calibration repeatability across voltage, current, PF, temperature, component lots, and aging as required by the product claim.'), PageBreak()]

story += [P('9. Evidence register and document disposition', 'H1x')]
story += [table([
    ['Artifact', 'Purpose', 'Current status'],
    ['evidence/source-manifest.md', 'Archive identity, hashes, and evidence boundary.', 'Available; source archive has no production collateral.'],
    ['evidence/schematic-audit.md', 'Static connectivity, component identity, and critical safety findings.', 'Available; not a physical verification.'],
    ['evidence/pcb-audit.md', 'PCB geometry, DRC defaults, edge margin, PE/current-path findings.', 'Available; not a released fabrication review.'],
    ['design.md', 'Engineering baseline, Gate 0 blockers, redesign direction, verification path.', 'Available; source-level assessment.'],
    ['firmware/', 'Safe ESP-07 bring-up code and protocol parser.', 'Available; build artifact only; not flashed or HIL-validated.'],
    ['production-readiness-questions.md', 'Decision register for development through production.', 'Available; answers/evidence still required.'],
], [48*mm, 75*mm, 51*mm], small=True), Spacer(1, 8)]
story += [P('Change control', 'H2x')]
story += [table([
    ['Revision', 'Date', 'Change', 'Disposition'],
    ['R0.1', '2026-08-25', 'Initial evidence-bounded engineering datasheet.', 'Superseded by R0.2 for controlled engineering use.'],
    ['R0.2', '2026-08-28', 'Expanded production release control: claim discipline, firmware contract, configuration index, Gate 0, V&V matrix, SmartPlug scope.', 'Current engineering-controlled release; not product-production release.'],
], [20*mm, 26*mm, 92*mm, 36*mm], small=True), Spacer(1, 8)]
story += [band('<b>Final disposition:</b> This datasheet is suitable as a mature engineering-control document for redesign and production preparation. The current SmartPlug board is <b>not ready for energised prototype, pre-production, production, sale, or product certification</b> until the listed blockers are closed on a controlled configuration and evidence is reviewed.', PALE_RED, RED), Spacer(1, 9)]
story += [P('Primary sources used', 'H2x'), P('1. SmartPlugV2 supplied archive SHA-256 A1F555E324DDDF872162B4349204409EC8EEAD74FCFE2C729C733363C6DC8030. 2. Preserved EasyEDA schematic SHA-256 0F0CA22FEBA5CD64F4E238B6E865ED163DB512381C7D2923443AB6FE4EB5677C. 3. Preserved EasyEDA PCB SHA-256 8B9F93854766716F0F44CB742D058A8899DBF3B2A1E216A976E19EE328A1C92C. 4. Current workspace audit and safe firmware baseline listed above.', 'Tinyx')]

doc.build(story)
print(OUT)
