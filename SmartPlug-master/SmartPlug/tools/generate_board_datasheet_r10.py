from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import BaseDocTemplate, Frame, PageTemplate, PageBreak, Paragraph, Spacer, Table, TableStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output' / 'pdf' / 'SmartPlug-Board-Datasheet-R1.2.pdf'
OUT.parent.mkdir(parents=True, exist_ok=True)

for path in [Path('C:/Windows/Fonts/arial.ttf'), Path('C:/Windows/Fonts/Arial.ttf')]:
    if path.exists():
        pdfmetrics.registerFont(TTFont('BoardSans', str(path)))
        break
else:
    raise RuntimeError('Arial font tidak ditemukan')

NAVY = colors.HexColor('#11263F'); BLUE = colors.HexColor('#0869B8'); INK = colors.HexColor('#1D2939')
MUTED = colors.HexColor('#52647A'); LINE = colors.HexColor('#D6DFEA'); PALE = colors.HexColor('#F1F6FC')
AMBER = colors.HexColor('#FFF5DF'); AMBER_LINE = colors.HexColor('#B77000')
styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name='TitleB', fontName='BoardSans', fontSize=26, leading=31, textColor=NAVY, spaceAfter=5))
styles.add(ParagraphStyle(name='H1B', fontName='BoardSans', fontSize=16, leading=20, textColor=NAVY, spaceBefore=5, spaceAfter=8))
styles.add(ParagraphStyle(name='H2B', fontName='BoardSans', fontSize=11.5, leading=15, textColor=BLUE, spaceBefore=8, spaceAfter=5))
styles.add(ParagraphStyle(name='BodyB', fontName='BoardSans', fontSize=8.8, leading=12.2, textColor=INK, spaceAfter=5))
styles.add(ParagraphStyle(name='SmallB', fontName='BoardSans', fontSize=7.3, leading=9.5, textColor=MUTED))
styles.add(ParagraphStyle(name='HeadB', fontName='BoardSans', fontSize=7.2, leading=9, textColor=colors.white))
styles.add(ParagraphStyle(name='CellB', fontName='BoardSans', fontSize=7.3, leading=9.4, textColor=INK))
styles.add(ParagraphStyle(name='CellSmallB', fontName='BoardSans', fontSize=6.7, leading=8.4, textColor=INK))
styles.add(ParagraphStyle(name='CodeB', fontName='Courier', fontSize=6.5, leading=8.3, textColor=INK))

def p(text, style='BodyB'):
    return Paragraph(text, styles[style])

def tbl(rows, widths, small=False):
    data = [[p(c, 'HeadB' if i == 0 else ('CellSmallB' if small else 'CellB')) for c in row] for i, row in enumerate(rows)]
    t = Table(data, colWidths=widths, repeatRows=1, hAlign='LEFT')
    t.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), NAVY), ('GRID', (0,0), (-1,-1), 0.35, LINE),
        ('VALIGN', (0,0), (-1,-1), 'TOP'), ('LEFTPADDING', (0,0), (-1,-1), 5), ('RIGHTPADDING', (0,0), (-1,-1), 5),
        ('TOPPADDING', (0,0), (-1,-1), 4), ('BOTTOMPADDING', (0,0), (-1,-1), 4),
    ]))
    return t

def note(text):
    t = Table([[p(text)]], colWidths=[174*mm])
    t.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,-1), AMBER), ('BOX', (0,0), (-1,-1), 0.5, AMBER_LINE),
        ('LEFTPADDING', (0,0), (-1,-1), 8), ('RIGHTPADDING', (0,0), (-1,-1), 8),
        ('TOPPADDING', (0,0), (-1,-1), 7), ('BOTTOMPADDING', (0,0), (-1,-1), 7),
    ]))
    return t

def footer(canvas, doc):
    canvas.saveState(); canvas.setStrokeColor(LINE); canvas.setLineWidth(0.5)
    canvas.line(18*mm, 13*mm, 192*mm, 13*mm); canvas.setFillColor(MUTED); canvas.setFont('BoardSans', 7)
    canvas.drawString(18*mm, 8.5*mm, 'SmartPlug Board Datasheet R1.2 - board, wiring, firmware, and local API')
    canvas.drawRightString(192*mm, 8.5*mm, f'Halaman {doc.page}'); canvas.restoreState()

doc = BaseDocTemplate(str(OUT), pagesize=A4, leftMargin=18*mm, rightMargin=18*mm, topMargin=17*mm, bottomMargin=18*mm,
                      title='SmartPlug Board Datasheet R1.2', author='SmartPlug Engineering')
doc.addPageTemplates([PageTemplate(id='main', frames=[Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id='f')], onPage=footer)])
s=[]
s += [p('SMARTPLUG', 'TitleB'), p('Datasheet Board, Wiring, Firmware, dan Local API - R1.2', 'H1B'), p('Tanggal: 28 Agustus 2026 | Ruang lingkup: fakta dari skematik/PCB EasyEDA yang diberikan, target input 220 VAC 50 Hz, firmware SmartPlug R0.2.0, dan kontrak REST API v1 di workspace.', 'SmallB')]
s += [note('<b>Tujuan dokumen ini:</b> menjelaskan board yang ada secara langsung. Ini bukan laporan sertifikasi, bukan rating produk jadi, dan bukan instruksi untuk menghubungkan mains.'), Spacer(1,8)]
s += [p('1. Ringkasan board', 'H1B'), tbl([
    ['Parameter', 'Spesifikasi board'],
    ['Nama source', 'smartPlug2Ver2 / smartPlug2'],
    ['Ukuran PCB', 'Sekitar 45.0 x 76.0 mm; 2 layer tembaga.'],
    ['Input desain', '220 VAC, 50 Hz.'],
    ['Kontrol / Wi-Fi', 'ESP-07 berbasis ESP8266.'],
    ['IC metering', 'BL0940, TSSOP-14.'],
    ['Relay', 'HFE20-1/5-1HST-L2; relay latching 5 V, satu kontak NO (1 Form A).'],
    ['Pengukuran arus', 'Shunt R15 0.5 mOhm, package 2512.'],
    ['Pengukuran tegangan', 'Divider 5 x 390 kOhm seri dan filter 523 Ohm / 100 nF.'],
    ['Catu logic', 'U1 AC/DC ke rail +5 V, kemudian AMS1117-3.3 ke VCC 3.3 V.'],
    ['Konektor service', 'U6 enam pin: VCC, GND, RXD, TXD, RST, GPIO0.'],
], [48*mm,126*mm]), Spacer(1,8)]
s += [p('Rating yang dapat ditulis dengan benar', 'H2B'), tbl([
    ['Item', 'Nilai'],
    ['Tegangan/frekuensi target board', '220 VAC, 50 Hz.'],
    ['Relay component', 'HFE20 family source relay: 5 V latching, 1 Form A. Rating komponen berasal dari relay, bukan rating board lengkap.'],
    ['Rating output SmartPlug', 'Tidak ditetapkan oleh source board. Tidak ada angka arus/daya produk pada skematik, PCB, terminal, atau hasil uji yang diberikan.'],
    ['Akurasi metering', 'Tidak ditetapkan oleh source board. Firmware SmartPlug menahan V/A/W/PF/Wh sampai koefisien kalibrasi dan validasi fisik tersedia.'],
], [55*mm,119*mm], small=True), PageBreak()]

s += [p('2. Wiring dan jalur daya', 'H1B'), p('Nama net ditulis persis seperti source: <b>NETRAL</b>, bukan NEUTRAL.', 'SmallB')]
wiring = Table([
    [p('<b>INPUT P1</b><br/>P1.1 EARTH<br/>P1.2 NETRAL<br/>P1.3 LINE', 'CellB'), p('<b>OUTPUT</b><br/>J1 LINE<br/>J2 OUT<br/>J3 EARTH', 'CellB')],
    [p('<b>JALUR LINE</b><br/>P1.3 LINE -> J1 langsung<br/>LINE juga menuju U1 dan divider tegangan.', 'CellB'), p('<b>JALUR SWITCH</b><br/>P1.2 NETRAL -> R15 -> NET_OUT -> kontak U4 -> OUT -> J2', 'CellB')],
    [p('<b>JALUR EARTH</b><br/>P1.1 EARTH -> trace PCB -> J3', 'CellB'), p('<b>LOGIC GND</b><br/>NETRAL -> R18 0 Ohm dan R19 0 Ohm -> GND elektronik', 'CellB')],
], colWidths=[87*mm,87*mm])
wiring.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),PALE),('GRID',(0,0),(-1,-1),0.5,BLUE),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),8),('RIGHTPADDING',(0,0),(-1,-1),8),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),8)]))
s += [wiring, Spacer(1,8), p('Komponen utama pada wiring', 'H2B'), tbl([
    ['Designator', 'Part/value source', 'Fungsi pada wiring'],
    ['U3', 'ESP-07', 'MCU, Wi-Fi, komunikasi BL0940, dan drive relay.'],
    ['U2', 'BL0940', 'Membaca channel tegangan/arus dan menyediakan UART, ZX, CF.'],
    ['U4', 'HFE20-1/5-1HST-L2', 'Kontak NO menghubungkan NET_OUT ke OUT; coil SET/RESET berasal dari rail +5 V.'],
    ['R15', 'LR2512-22R0005F4, 0.5 mOhm', 'Shunt arus antara NETRAL dan NET_OUT.'],
    ['R10', '10D471K', 'MOV melintang antara LINE dan NETRAL.'],
    ['U1', 'acDcPsTegak', 'Modul catu AC/DC source ke +5 V; identitas pabrikan/MPN tidak ada pada source.'],
    ['U7', 'AMS1117-3.3', 'Regulator +5 V ke VCC 3.3 V.'],
], [24*mm,62*mm,88*mm], small=True), Spacer(1,8)]
s += [note('<b>Catatan wiring:</b> pada source saat ini yang disakelar relay adalah net NETRAL; LINE tetap ke J1. Ini adalah deskripsi koneksi, bukan penilaian kualitas atau perubahan desain.'), PageBreak()]

s += [p('3. Pin ESP-07 dan fungsi firmware', 'H1B'), tbl([
    ['Pin / GPIO', 'Koneksi board', 'Fungsi baseline firmware'],
    ['GPIO5', 'R22 -> Q3 -> coil SET U4', 'Relay SET; dipaksa nonaktif saat boot.'],
    ['GPIO4', 'R23 -> Q4 -> coil RESET U4', 'Relay RESET; dipaksa nonaktif saat boot.'],
    ['GPIO13', 'BL0940 TX/SDO', 'RX software UART BL0940.'],
    ['GPIO15', 'BL0940 RX/SDI', 'TX software UART BL0940.'],
    ['GPIO14', 'BL0940 ZX', 'Dicadangkan.'],
    ['GPIO12', 'BL0940 CF', 'Dicadangkan.'],
    ['GPIO0', 'Tombol config dan U6', 'Dinonaktifkan; pin boot strap.'],
    ['GPIO2', 'LED active-low', 'Dinonaktifkan; pin boot strap.'],
    ['UART0', 'U6 RXD/TXD', 'Konsol diagnostik 115200 baud.'],
], [28*mm,66*mm,80*mm], small=True), Spacer(1,8)]
s += [p('Firmware saat ini', 'H2B'), tbl([
    ['Fungsi', 'Status implementasi'],
    ['Identitas', 'smartplug-bringup 0.2.0-local. Target PlatformIO espressif8266@4.2.1, board esp07, framework Arduino. Profile esp07_safe dan esp07_local berhasil dibangun.'],
    ['BL0940', 'UART 4800 baud; paket 35-byte dengan checksum; field raw diparsing.'],
    ['Relay', 'Driver latching non-blocking; flag SMARTPLUG_ALLOW_RELAY_ACTUATION=0.'],
    ['Wi-Fi / REST / cloud / OTA', 'Profile esp07_local membuat SoftAP WPA2 dan REST HTTP read-only lokal; cloud dan OTA tidak diimplementasikan. Profile esp07_safe mematikan radio.'],
    ['SmartPlug local API', 'Endpoint read-only: capabilities, status, measurements/latest, dan health. Tidak ada endpoint aktuasi relay.'],
    ['Nilai listrik dan energi', 'Raw BL0940 tersedia. V/A/W/VA/PF dan Wh sejak boot hanya tersedia setelah kalibrasi. Counter konsumsi tidak persisten dan tidak mengintegrasikan gap sample di atas 5 detik.'],
    ['Deteksi SmartPlug', 'Standby/vampire detect-only dan anomali tegangan report-only tersedia setelah kalibrasi. Tidak ada pemutusan fisik, proteksi, atau stabilisasi suplai.'],
    ['Serial command', 'help, status, meter; perintah relay membutuhkan enable build dan token konfirmasi.'],
], [48*mm,126*mm], small=True), Spacer(1,8)]
s += [note('<b>Arti status relay:</b> board hanya dapat mengetahui perintah/pulse yang dikirim oleh firmware. Karena tidak ada jalur feedback kontak pada source, status mekanis kontak tidak tersedia.'), PageBreak()]

s += [p('4. Integrasi REST API lokal SmartPlug', 'H1B'), p('Bagian ini adalah kontrak integrasi firmware profile <b>esp07_local</b>. Ia menggambarkan image yang berhasil dibangun, bukan hasil uji runtime pada board fisik.', 'BodyB')]
s += [p('Cara terhubung', 'H2B'), tbl([
    ['Parameter', 'Nilai / kebijakan'],
    ['Transport', 'Wi-Fi SoftAP WPA2 dan HTTP lokal; internet/cloud tidak diperlukan.'],
    ['SSID setup/lab', 'SmartPlug-SmartPlug. Kredensial setup didefinisikan di firmware; belum merupakan mekanisme otorisasi produk akhir.'],
    ['Base URL', 'http://192.168.4.1 (default SoftAP ESP8266), port 80.'],
    ['Format respons', 'application/json dengan Cache-Control: no-store.'],
    ['Versi API', 'v1. Tidak ada endpoint write atau endpoint kontrol relay fisik.'],
], [46*mm,128*mm], small=True), Spacer(1,7)]
s += [p('Endpoint', 'H2B'), tbl([
    ['Method / path', 'HTTP', 'Tujuan integrasi'],
    ['GET /', '200', 'Indeks API dan daftar endpoint.'],
    ['GET /api/v1/capabilities', '200', 'Kemampuan firmware/board dan batas hardware.'],
    ['GET /api/v1/status', '200', 'Identitas firmware, Wi-Fi, state relay terperintah, dan status kalibrasi.'],
    ['GET /api/v1/measurements/latest', '200', 'Sampel BL0940 raw; unit listrik hanya saat terkalibrasi.'],
    ['GET /api/v1/health', '200', 'Kesehatan paket meter, standby detect-only, dan anomali tegangan report-only.'],
    ['GET path lain, termasuk /api/v1/relay', '404', 'Tidak ada kontrol relay; respons memuat safety hold.'],
], [65*mm,20*mm,89*mm], small=True), Spacer(1,7)]
s += [p('Semantik data yang wajib dipatuhi aplikasi', 'H2B'), tbl([
    ['Field/status', 'Arti yang benar'],
    ['calibration_state: not_calibrated', 'electrical harus null. Raw code BL0940 tidak boleh diberi label Volt, Ampere, Watt, PF, atau kWh.'],
    ['electrical (saat calibrated)', 'voltage_v, current_a, active_power_w, apparent_power_va, power_factor, dan energy_wh_since_boot. Energi adalah konsumsi sejak boot, bukan counter billing persisten.'],
    ['captured_age_ms', 'Usia sampel terakhir. Aplikasi harus memperlakukan sampel lama sebagai data stale sesuai kebijakan aplikasi.'],
    ['health.standby_detection', 'Default <= 2 W selama 300000 ms; hanya detect-only. relay_cut_enabled selalu false.'],
    ['health.voltage_anomaly', 'Batas default 198 V/242 V, report-only; bukan proteksi atau stabilisasi suplai.'],
], [58*mm,116*mm], small=True), Spacer(1,7)]
s += [p('Contoh respons awal yang aman', 'H2B'), Table([[p('GET /api/v1/measurements/latest<br/>{"sample_available":false,"calibration_state":"not_calibrated","captured_age_ms":null,"electrical":null,"raw":null}', 'CodeB')]], colWidths=[174*mm], style=[('BACKGROUND',(0,0),(-1,-1),PALE),('BOX',(0,0),(-1,-1),0.4,LINE),('LEFTPADDING',(0,0),(-1,-1),7),('RIGHTPADDING',(0,0),(-1,-1),7),('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6)]), Spacer(1,7)]
s += [note('<b>Batas keselamatan API:</b> endpoint apa pun tidak dapat mengaktifkan relay pada R0.2.0. Endpoint tidak dikenal membalas HTTP 404 dengan relay_control: not_available_hardware_safety_hold.'), PageBreak()]

s += [p('5. Batas dokumen dan referensi source', 'H1B'), p('Dokumen ini sengaja membedakan fakta board dengan data yang tidak ada di source. Dengan begitu, datasheet tetap ringkas dan dapat dibaca tanpa membuat spesifikasi fiktif.', 'BodyB')]
s += [tbl([
    ['Bidang', 'Status dari source yang diberikan'],
    ['BOM/part kritis', 'ESP-07, BL0940, AMS1117-3.3, AO3400A, SM4007PL, LR2512-22R0005F4, dan 10D471K tercatat. U1, P1, J1-J3 tidak memiliki MPN pabrikan yang terkunci.'],
    ['Enclosure/stop kontak', 'Tidak ada file enclosure, drawing mekanik, atau model socket pada arsip board.'],
    ['Output current/power', 'Tidak ada angka rating SmartPlug pada skematik/PCB yang diberikan.'],
    ['Safety/compliance', 'Tidak ada laporan uji, sertifikat, atau standar target di dalam arsip.'],
    ['Metering performance', 'Tidak ada data kalibrasi/akurasi unit fisik pada arsip. Firmware menolak menerbitkan unit listrik sebelum koefisien kalibrasi disetujui.'],
], [48*mm,126*mm], small=True), Spacer(1,9)]
s += [p('Sumber yang dipakai', 'H2B'), p('1. SmartPlugV2.zip yang diberikan pengguna, SHA-256 A1F555E324DDDF872162B4349204409EC8EEAD74FCFE2C729C733363C6DC8030. 2. 1-Schematic_smartPlug2Ver2.json. 3. 1-PCB_PCB_smartPlug2Ver2.json. 4. Firmware SmartPlug R0.2.0 SmartPlug, manifest build, API contract, dan status verifikasi pada workspace. Dokumen ini tidak menggunakan data dari board fisik atau pengujian mains.', 'SmallB')]
s += [Spacer(1,12), note('<b>Kesimpulan:</b> board ini adalah pengendali/metering AC satu kanal berbasis ESP-07 dan BL0940, dengan relay latching pada jalur NETRAL. Firmware SmartPlug R0.2.0 menyediakan local-first monitoring dan status dasar tanpa kontrol relay fisik. Penetapan rating arus/daya SmartPlug, enclosure final, akurasi metering, serta validasi runtime tetap memerlukan bukti fisik di luar wiring yang diberikan.')]
doc.build(s)
print(OUT)
