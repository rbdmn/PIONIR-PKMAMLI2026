from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import BaseDocTemplate, Frame, PageBreak, PageTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.graphics.shapes import Drawing, Line, Polygon, Rect, String

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output' / 'pdf' / 'SmartPlug-User-Integration-Datasheet-R2.5.pdf'
OUT.parent.mkdir(parents=True, exist_ok=True)

for font_path in (Path('C:/Windows/Fonts/arial.ttf'), Path('C:/Windows/Fonts/Arial.ttf')):
    if font_path.exists():
        pdfmetrics.registerFont(TTFont('UserSans', str(font_path)))
        break
else:
    raise RuntimeError('Arial font tidak ditemukan')

NAVY = colors.HexColor('#11263F')
BLUE = colors.HexColor('#0869B8')
TEAL = colors.HexColor('#007D78')
INK = colors.HexColor('#1D2939')
MUTED = colors.HexColor('#52647A')
LINE = colors.HexColor('#D6DFEA')
PALE = colors.HexColor('#F1F6FC')
PALE_TEAL = colors.HexColor('#E7F7F5')
PALE_AMBER = colors.HexColor('#FFF5DF')
PALE_RED = colors.HexColor('#FFF0F1')
AMBER = colors.HexColor('#B77000')
RED = colors.HexColor('#B4232B')

styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name='UserTitle', fontName='UserSans', fontSize=27, leading=32, textColor=NAVY, spaceAfter=5))
styles.add(ParagraphStyle(name='UserSubtitle', fontName='UserSans', fontSize=12, leading=16, textColor=BLUE, spaceAfter=7))
styles.add(ParagraphStyle(name='UserH1', fontName='UserSans', fontSize=16, leading=20, textColor=NAVY, spaceBefore=4, spaceAfter=7))
styles.add(ParagraphStyle(name='UserH2', fontName='UserSans', fontSize=11.5, leading=15, textColor=BLUE, spaceBefore=7, spaceAfter=5))
styles.add(ParagraphStyle(name='UserBody', fontName='UserSans', fontSize=8.9, leading=12.2, textColor=INK, spaceAfter=4))
styles.add(ParagraphStyle(name='UserSmall', fontName='UserSans', fontSize=7.4, leading=9.6, textColor=MUTED))
styles.add(ParagraphStyle(name='UserHead', fontName='UserSans', fontSize=7.3, leading=9, textColor=colors.white))
styles.add(ParagraphStyle(name='UserCell', fontName='UserSans', fontSize=7.5, leading=9.5, textColor=INK))
styles.add(ParagraphStyle(name='UserCardTitle', fontName='UserSans', fontSize=10, leading=13, textColor=NAVY))
styles.add(ParagraphStyle(name='UserCode', fontName='Courier', fontSize=6.5, leading=8.4, textColor=INK))


def P(text, style='UserBody'):
    return Paragraph(text, styles[style])


def table(rows, widths, small=False):
    body = 'UserCell' if not small else 'UserSmall'
    data = [[P(cell, 'UserHead' if row_index == 0 else body) for cell in row]
            for row_index, row in enumerate(rows)]
    result = Table(data, colWidths=widths, repeatRows=1, hAlign='LEFT')
    result.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), NAVY),
        ('GRID', (0, 0), (-1, -1), 0.35, LINE),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 5),
        ('RIGHTPADDING', (0, 0), (-1, -1), 5),
        ('TOPPADDING', (0, 0), (-1, -1), 4),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
    ]))
    return result


def band(text, bg, border):
    result = Table([[P(text)]], colWidths=[174 * mm])
    result.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), bg),
        ('BOX', (0, 0), (-1, -1), 0.55, border),
        ('LEFTPADDING', (0, 0), (-1, -1), 8),
        ('RIGHTPADDING', (0, 0), (-1, -1), 8),
        ('TOPPADDING', (0, 0), (-1, -1), 7),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 7),
    ]))
    return result


def card(title, text):
    result = Table([[P(title, 'UserCardTitle')], [P(text, 'UserCell')]], colWidths=[84 * mm])
    result.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), PALE_TEAL),
        ('BOX', (0, 0), (-1, -1), 0.55, TEAL),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 8),
        ('RIGHTPADDING', (0, 0), (-1, -1), 8),
        ('TOPPADDING', (0, 0), (-1, -1), 6),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
    ]))
    return result


def arrow(drawing, x1, y1, x2, y2, color=BLUE):
    drawing.add(Line(x1, y1, x2, y2, strokeColor=color, strokeWidth=1.5))
    drawing.add(Polygon([x2, y2, x2 - 5, y2 + 3, x2 - 5, y2 - 3], fillColor=color, strokeColor=color))


def visual_box(drawing, x, y, w, h, title, detail, fill=PALE):
    drawing.add(Rect(x, y, w, h, rx=5, ry=5, fillColor=fill, strokeColor=BLUE, strokeWidth=0.8))
    drawing.add(String(x + 8, y + h - 16, title, fontName='UserSans', fontSize=8.8, fillColor=NAVY))
    drawing.add(String(x + 8, y + 11, detail, fontName='UserSans', fontSize=6.8, fillColor=MUTED))


def workflow_diagram():
    d = Drawing(174 * mm, 34 * mm)
    visual_box(d, 0, 16, 138, 45, '1. SmartPlug', 'meter dan Wi-Fi lokal', PALE_TEAL)
    visual_box(d, 178, 16, 138, 45, '2. Aplikasi / pengendali', 'membaca REST API', PALE)
    visual_box(d, 356, 16, 138, 45, '3. Informasi energi', 'histori, biaya, rekomendasi', PALE_AMBER)
    arrow(d, 140, 38, 175, 38)
    arrow(d, 318, 38, 353, 38)
    return d


def connection_diagram():
    d = Drawing(174 * mm, 27 * mm)
    visual_box(d, 0, 12, 142, 40, 'SmartPlug', 'Wi-Fi lokal + API', PALE_TEAL)
    visual_box(d, 176, 12, 142, 40, 'Android / laptop', 'base URL perangkat', PALE)
    visual_box(d, 352, 12, 142, 40, 'Aplikasi', 'menampilkan data', PALE_AMBER)
    arrow(d, 144, 32, 173, 32)
    arrow(d, 320, 32, 349, 32)
    return d


def data_diagram():
    d = Drawing(174 * mm, 32 * mm)
    visual_box(d, 0, 15, 138, 42, 'Baca sampel', 'sample_available', PALE)
    visual_box(d, 178, 15, 138, 42, 'Cek kalibrasi', 'calibration_state', PALE_AMBER)
    visual_box(d, 356, 15, 138, 42, 'Tampilkan data', 'hanya electrical valid', PALE_TEAL)
    arrow(d, 140, 36, 175, 36)
    arrow(d, 318, 36, 353, 36)
    return d


def api_diagram():
    d = Drawing(174 * mm, 30 * mm)
    visual_box(d, 0, 14, 142, 42, '1. capabilities + status', 'cek fitur dan kondisi perangkat', PALE)
    visual_box(d, 176, 14, 142, 42, '2. measurements + health', 'baca data dan kualitas sampel', PALE_TEAL)
    visual_box(d, 352, 14, 142, 42, '3. Tampilan aplikasi', 'tampilkan hanya data valid', PALE_AMBER)
    arrow(d, 144, 35, 173, 35)
    arrow(d, 320, 35, 349, 35)
    return d


def state_diagram():
    d = Drawing(174 * mm, 29 * mm)
    visual_box(d, 0, 13, 142, 40, 'Belum ada sampel', 'tampilkan status menunggu', PALE)
    visual_box(d, 176, 13, 142, 40, 'Belum terkalibrasi', 'jangan tampilkan satuan listrik', PALE_AMBER)
    visual_box(d, 352, 13, 142, 40, 'Data valid', 'tampilkan electrical', PALE_TEAL)
    arrow(d, 144, 33, 173, 33)
    arrow(d, 320, 33, 349, 33)
    return d


def usage_summary_diagram():
    d = Drawing(174 * mm, 31 * mm)
    visual_box(d, 0, 14, 142, 42, 'Monitoring', 'tersedia melalui API lokal', PALE_TEAL)
    visual_box(d, 176, 14, 142, 42, 'Kontrol beban', 'tidak tersedia', PALE_AMBER)
    visual_box(d, 352, 14, 142, 42, 'Histori dan biaya', 'ditangani aplikasi', PALE)
    return d


def relay_state_diagram():
    d = Drawing(174 * mm, 31 * mm)
    visual_box(d, 0, 14, 142, 42, 'State belum diketahui', 'ON dan OFF tersedia', PALE)
    visual_box(d, 176, 14, 142, 42, 'State ON', 'ON nonaktif, OFF tersedia', PALE_TEAL)
    visual_box(d, 352, 14, 142, 42, 'State OFF', 'OFF nonaktif, ON tersedia', PALE_RED)
    arrow(d, 144, 35, 173, 35)
    arrow(d, 320, 35, 349, 35)
    return d


def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(LINE)
    canvas.setLineWidth(0.5)
    canvas.line(18 * mm, 13 * mm, 192 * mm, 13 * mm)
    canvas.setFillColor(MUTED)
    canvas.setFont('UserSans', 7)
    canvas.drawString(18 * mm, 8.5 * mm, 'SmartPlug - Panduan Dashboard dan Integrasi Lokal R2.5')
    canvas.drawRightString(192 * mm, 8.5 * mm, f'Halaman {doc.page}')
    canvas.restoreState()


doc = BaseDocTemplate(
    str(OUT), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
    topMargin=17 * mm, bottomMargin=18 * mm,
    title='SmartPlug User and Integration Datasheet R2.5',
    author='SmartPlug Engineering',
    subject='User and local API integration guide for evaluation build',
)
frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id='main')
doc.addPageTemplates([PageTemplate(id='main', frames=[frame], onPage=footer)])

story = []
story += [P('SMARTPLUG', 'UserTitle')]
story += [P('Panduan Pengguna dan Integrasi Lokal', 'UserSubtitle')]
story += [P('Untuk pengguna dan pengembang aplikasi. Dokumen ini menjelaskan fungsi firmware lokal yang tersedia saat ini.', 'UserSmall')]
story += [Spacer(1, 5)]
story += [P('SmartPlug bekerja sebagai sumber data energi di jaringan lokal. Perangkat membaca data meter, lalu aplikasi membaca data tersebut melalui REST API untuk membuat tampilan, histori, biaya, dan rekomendasi. Tidak ada ketergantungan pada cloud untuk alur dasar ini.', 'UserBody')]

story += [P('Apa yang dilakukan SmartPlug saat ini', 'UserH1')]
cards = Table([
    [card('Monitoring lokal', 'Menyediakan data meter BL0940 melalui jaringan Wi-Fi lokal. Internet tidak diperlukan.'),
     card('REST API v1', 'Aplikasi Android atau pengendali lokal membaca status, kemampuan, pengukuran, dan kesehatan perangkat.')],
    [card('Histori di aplikasi', 'Histori, grafik, biaya, dan rekomendasi energi disimpan dan diolah oleh aplikasi Android, bukan perangkat.'),
     card('Deteksi dasar', 'Standby/vampire dan anomali tegangan tersedia sebagai status setelah kalibrasi; keduanya tidak mengontrol listrik.')],
], colWidths=[85 * mm, 85 * mm], hAlign='LEFT')
cards.setStyle(TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LEFTPADDING', (0, 0), (-1, -1), 0), ('RIGHTPADDING', (0, 0), (-1, -1), 4), ('TOPPADDING', (0, 0), (-1, -1), 0), ('BOTTOMPADDING', (0, 0), (-1, -1), 5)]))
story += [cards, Spacer(1, 6)]

story += [P('Alur penggunaan', 'UserH2')]
story += [workflow_diagram(), P('Alurnya satu arah: perangkat menyediakan data lokal, aplikasi meminta data melalui API, lalu aplikasi mengubahnya menjadi informasi yang mudah dibaca. Perangkat tidak menggantikan aplikasi dan tidak menjalankan perhitungan biaya atau penyimpanan histori permanen.', 'UserBody'), Spacer(1, 5)]
story += [P('Yang perlu diketahui sebelum integrasi', 'UserH2')]
story += [table([
    ['Topik', 'Status yang harus dipahami pengguna'],
    ['Kontrol beban', 'Tidak tersedia. API tidak mempunyai endpoint relay dan perangkat tidak memutus atau menyambung beban.'],
    ['Nilai listrik', 'Pada konfigurasi saat ini, V/A/W/PF/Wh belum diterbitkan sampai kalibrasi fisik disetujui.'],
    ['Riwayat energi', 'Counter energy sejak boot tidak persisten. Gunakan aplikasi Android untuk histori dan perhitungan biaya.'],
    ['Koneksi internet', 'Tidak diperlukan untuk API lokal. Cloud dan OTA tidak tersedia pada build ini.'],
    ['Tampilan aplikasi', 'Bukan bagian dari firmware. Browser hanya menerima JSON; tampilan, grafik, biaya, dan rekomendasi harus disediakan aplikasi Android/pengendali.'],
], [48 * mm, 126 * mm], small=True), PageBreak()]

story += [P('1. Cara terhubung ke perangkat', 'UserH1')]
story += [P('Gunakan langkah ini hanya pada unit evaluation yang telah disiapkan tim teknis.', 'UserBody')]
story += [connection_diagram(), P('Android atau laptop tidak perlu terhubung ke internet untuk membaca perangkat. Setelah tersambung ke Wi-Fi SmartPlug, aplikasi mengakses alamat lokal perangkat dan menerima respons JSON. Tampilan pengguna tetap dibuat oleh aplikasi, bukan oleh halaman web perangkat.', 'UserBody')]
story += [table([
    ['Langkah', 'Tindakan'],
    ['1', 'Hubungkan Android atau laptop ke Wi-Fi yang dipancarkan SmartPlug.'],
    ['2', 'Masukkan kredensial setup yang disertakan bersama unit. Jika belum tersedia, hubungi penyedia unit.'],
    ['3', 'Buka <b>http://192.168.4.1</b> pada port 80, atau gunakan alamat ini sebagai base URL di aplikasi.'],
    ['4', 'Baca GET /api/v1/status dan GET /api/v1/capabilities sebelum menampilkan data kepada pengguna.'],
], [20 * mm, 154 * mm]), Spacer(1, 7)]

story += [P('Kontrak REST API v1', 'UserH2')]
story += [table([
    ['Method / endpoint', 'Hasil', 'Kegunaan'],
    ['GET /', '200', 'Daftar endpoint yang tersedia.'],
    ['GET /api/v1/capabilities', '200', 'Cek fitur firmware dan batas perangkat.'],
    ['GET /api/v1/status', '200', 'Identitas firmware, Wi-Fi, status perintah relay, dan status kalibrasi.'],
    ['GET /api/v1/measurements/latest', '200', 'Pengukuran terbaru, usia sampel, dan data raw.'],
    ['GET /api/v1/health', '200', 'Kesehatan paket meter serta status deteksi.'],
    ['GET endpoint lain', '404', 'Tidak tersedia. /api/v1/relay tidak mengontrol relay.'],
], [65 * mm, 20 * mm, 89 * mm], small=True), Spacer(1, 7)]

story += [P('Aturan respons', 'UserH2')]
story += [table([
    ['Parameter', 'Nilai'],
    ['Content-Type', 'application/json'],
    ['Cache', 'Cache-Control: no-store'],
    ['Versi', 'API v1'],
    ['Internet', 'Tidak diperlukan'],
    ['Kontrol fisik', 'Tidak tersedia; endpoint tidak dikenal mengembalikan HTTP 404 dan safety hold.'],
], [45 * mm, 129 * mm], small=True), Spacer(1, 8)]
story += [P('Keamanan dan pengalaman aplikasi', 'UserH2')]
story += [table([
    ['Topik', 'Batas yang harus diterapkan aplikasi'],
    ['Transport', 'HTTP lokal tanpa TLS. Jangan mengirim data rahasia melalui API ini dan jangan menganggapnya sebagai transport produk akhir yang terautentikasi.'],
    ['Browser', 'Membuka base URL menampilkan indeks JSON API, bukan dashboard pengguna.'],
    ['Kegagalan koneksi', 'Tidak ada SLA waktu respons atau bukti runtime hardware saat ini. Aplikasi harus menampilkan status tidak terhubung ketika request gagal atau data stale.'],
], [43 * mm, 131 * mm], small=True), Spacer(1, 8)]
story += [PageBreak(), P('Peta respons untuk aplikasi', 'UserH1')]
story += [P('Aplikasi tidak perlu menebak kemampuan perangkat. Mulailah dengan membaca capabilities dan status, lalu gunakan hasilnya untuk menentukan data mana yang dapat ditampilkan. Sesudah itu, baca pengukuran terbaru dan health untuk menjaga tampilan tetap sesuai keadaan perangkat.', 'UserBody')]
story += [table([
    ['Respons', 'Field yang perlu dibaca aplikasi'],
    ['capabilities', 'api_version, transport, dan features. Gunakan feature flag sebagai sumber kebenaran; jangan mengasumsikan relay, MicroSD, harmonik, atau phase imbalance tersedia.'],
    ['status', 'firmware, version, uptime_ms, wifi, relay, dan calibration. relay.commanded_state bukan feedback kontak fisik. uptime_ms kembali nol saat perangkat restart.'],
    ['measurements/latest', 'sample_available, calibration_state, captured_age_ms, electrical, dan raw. Ikuti aturan di halaman berikutnya.'],
    ['health', 'meter.has_poll_result, meter.latest_poll_valid, packet counter, standby_detection, dan voltage_anomaly.'],
], [43 * mm, 131 * mm], small=True), Spacer(1, 9)]
story += [P('Urutan pembacaan respons', 'UserH2'), api_diagram(), P('Urutan ini menghindari tampilan yang menyesatkan. Feature flag dan status kalibrasi menjadi sumber kebenaran, sedangkan nilai pengukuran hanya ditampilkan ketika sampel serta kalibrasi memenuhi syarat.', 'UserBody')]
story += [P('Tampilan yang sesuai keadaan perangkat', 'UserH2'), state_diagram(), P('Gunakan tiga keadaan ini sebagai dasar tampilan aplikasi. Dengan cara ini, pengguna dapat membedakan perangkat yang belum memberi sampel, perangkat yang belum siap memberi satuan listrik, dan perangkat yang sudah memiliki data yang dapat ditampilkan.', 'UserBody'), PageBreak()]

story += [P('2. Membaca data dengan benar', 'UserH1')]
story += [P('Jangan memberi arti satuan listrik kepada raw code. Aplikasi harus selalu membaca status kalibrasi dan usia sampel terlebih dahulu. Diagram berikut menunjukkan urutan pemeriksaan yang harus dilakukan sebelum nilai listrik ditampilkan.', 'UserBody')]
story += [data_diagram(), P('Jika sampel belum tersedia, aplikasi menampilkan status menunggu data. Jika perangkat belum terkalibrasi, aplikasi tidak boleh menampilkan Volt, Ampere, Watt, faktor daya, energi, atau biaya. Nilai tersebut hanya dapat ditampilkan ketika field electrical tersedia.', 'UserBody')]
story += [P('Respons ketika belum ada sampel meter', 'UserH2')]
initial = Table([[P('GET /api/v1/measurements/latest<br/>{"sample_available":false,"calibration_state":"not_calibrated","captured_age_ms":null,"electrical":null,"raw":null}', 'UserCode')]], colWidths=[174 * mm])
initial.setStyle(TableStyle([
    ('BACKGROUND', (0, 0), (-1, -1), PALE), ('BOX', (0, 0), (-1, -1), 0.4, LINE),
    ('LEFTPADDING', (0, 0), (-1, -1), 8), ('RIGHTPADDING', (0, 0), (-1, -1), 8),
    ('TOPPADDING', (0, 0), (-1, -1), 7), ('BOTTOMPADDING', (0, 0), (-1, -1), 7),
]))
story += [initial, Spacer(1, 8)]
story += [P('Arti field penting', 'UserH2')]
story += [table([
    ['Field', 'Cara aplikasi harus menggunakannya'],
    ['sample_available', 'false berarti belum ada paket meter valid. Jangan menampilkan nilai lama sebagai nilai saat ini.'],
    ['calibration_state', 'not_calibrated berarti electrical bernilai null, walaupun raw mungkin ada. Jangan menampilkan Volt, Ampere, Watt, PF, Wh, atau biaya.'],
    ['captured_age_ms', 'Usia sampel terakhir. Perangkat tidak mengirim timestamp kalender; aplikasi harus mencatat waktu penerimaan sendiri dan menandai data stale sesuai kebijakannya.'],
    ['raw', 'Dapat muncul setelah paket meter valid, termasuk saat belum terkalibrasi. Hanya untuk diagnosa dan kalibrasi tim teknis; bukan data pengguna.'],
    ['electrical', 'Muncul hanya setelah kalibrasi. Field yang tersedia: voltage_v, current_a, active_power_w, apparent_power_va, power_factor, energy_wh_since_boot.'],
    ['energy_wh_since_boot', 'Energi konsumsi sejak boot. Nilainya kembali nol ketika perangkat restart dan tidak digunakan sebagai catatan tagihan permanen.'],
], [54 * mm, 120 * mm], small=True), Spacer(1, 7)]

story += [P('Deteksi yang tersedia setelah kalibrasi', 'UserH2')]
story += [table([
    ['Fungsi', 'Arti untuk pengguna'],
    ['Standby/vampire', 'Status pending atau detected ketika daya aktif absolut <= 2 W selama 300000 ms. Ini hanya informasi; tidak memutus beban.'],
    ['Anomali tegangan', 'Status undervoltage, normal, atau overvoltage dengan batas default 198 V dan 242 V. Ini hanya informasi; bukan proteksi listrik.'],
], [50 * mm, 124 * mm], small=True), PageBreak()]

story += [P('3. Batas fungsi dan status evaluasi', 'UserH1')]
story += [P('Bagian ini sengaja ringkas: pengguna perlu mengetahui apa yang dapat dipakai sekarang dan apa yang tidak boleh diasumsikan.', 'UserBody')]
story += [P('Ringkasan fungsi utama', 'UserH2'), usage_summary_diagram(), P('SmartPlug berfungsi sebagai sumber data lokal. Pengambilan keputusan, tampilan, histori, serta perhitungan biaya berada di aplikasi atau pengendali yang terhubung. Perangkat tidak digunakan untuk mengendalikan beban pada konfigurasi ini.', 'UserBody')]
story += [table([
    ['Fungsi', 'Status untuk pengguna'],
    ['Tampilan, grafik, histori, biaya, rekomendasi', 'Dilaksanakan oleh aplikasi Android/pengendali lokal. Perangkat menyediakan sumber data lokal.'],
    ['Kontrol relay manual', 'Tidak tersedia. Jangan mengasumsikan API dapat memutus atau menyambung beban.'],
    ['Auto-cut vampire power', 'Tidak tersedia. Device hanya mendeteksi status standby setelah kalibrasi.'],
    ['MicroSD CSV di perangkat', 'Tidak tersedia. Simpan histori/ekspor pada aplikasi.'],
    ['Harmonik dan ketidakseimbangan fase', 'Tidak tersedia pada perangkat ini.'],
    ['Stabilisasi fluktuasi listrik', 'Tidak tersedia. Status anomali hanya untuk pelaporan.'],
    ['Discovery otomatis dan pairing produk', 'Belum tersedia. Gunakan koneksi SoftAP setup secara langsung.'],
    ['Skala koneksi dan respons API', 'Belum tervalidasi pada perangkat nyata. Jangan membuat klaim jumlah client atau waktu respons untuk penggunaan lapangan.'],
], [58 * mm, 116 * mm], small=True), Spacer(1, 8)]

story += [P('Status evaluasi yang perlu diketahui', 'UserH2')]
story += [table([
    ['Area', 'Status saat ini'],
    ['Kesiapan firmware', 'Firmware lokal tersedia untuk ESP-07.'],
    ['Pengujian perangkat nyata', 'Belum ada pengujian pada unit fisik untuk koneksi Wi-Fi/API maupun metering dengan alat acuan.'],
    ['Klaim akurasi/rating', 'Tidak tersedia. Jangan gunakan perangkat sebagai acuan tagihan, proteksi listrik, atau produk plug-in untuk pengguna umum.'],
], [54 * mm, 120 * mm], small=True), Spacer(1, 8)]

story = []
story += [P('SMARTPLUG', 'UserTitle')]
story += [P('Panduan Dashboard dan Integrasi Lokal', 'UserSubtitle')]
story += [P('Revisi R2.5 | firmware dashboard lokal | ESP-07 / ESP8266', 'UserSmall')]
story += [Spacer(1, 6)]
story += [P('SmartPlug menyediakan dashboard dan REST API pada jaringan lokal. Dashboard ditujukan untuk melihat keadaan meter, melakukan kalibrasi sementara, mengatur koneksi Wi-Fi, serta evaluasi relay pada build yang mengizinkannya. Dokumen ini menjelaskan perilaku firmware saat ini; bukan klaim rating, akurasi, atau kesiapan produk pasar.', 'UserBody')]

story += [P('Ringkasan dashboard lokal', 'UserH1')]
story += [workflow_diagram(), Spacer(1, 4)]
story += [table([
    ['Area', 'Perilaku firmware saat ini'],
    ['Akses lokal', 'SoftAP SmartPlug-Setup pada http://192.168.4.1. Dashboard dan REST API berjalan tanpa internet.'],
    ['Pembacaan meter', 'BL0940 dipoll setiap 500 ms. Tegangan, arus, daya aktif, dan temperatur memakai moving average hingga 10 sampel valid terakhir.'],
    ['Grafik browser', 'Tab Voltage, Current, Watt, dan Energy. Rentang X 1 sampai 60 menit, tooltip, min/rata-rata/maks, pilihan Wh/kWh, dan 0 LATCH. Riwayat 1 jam berada di browser selama halaman tetap terbuka.'],
    ['Tema dan tampilan', 'Dashboard responsif untuk desktop/HP, dengan pilihan Dark atau Light yang disimpan di browser.'],
    ['Kalibrasi', 'Panel Kalibrasi sensor menerima referensi Voltage, Current, dan Watt. Hasil hanya berlaku saat runtime dan belum disimpan ke EEPROM.'],
], [43 * mm, 131 * mm], small=True), Spacer(1, 7)]

story += [P('Cara terhubung', 'UserH2')]
story += [table([
    ['Langkah', 'Tindakan'],
    ['1', 'Hubungkan Android atau laptop ke Wi-Fi SmartPlug-Setup.'],
    ['2', 'Buka http://192.168.4.1. Halaman awal adalah dashboard, bukan indeks JSON.'],
    ['3', 'Periksa indikator Sensor. Hijau berarti paket meter valid diterima baru-baru ini; biru berarti menunggu paket pertama; merah berarti tidak menerima data valid.'],
    ['4', 'Gunakan panel Sambungkan ke Wi-Fi yang ada bila perangkat perlu ikut jaringan Wi-Fi existing. SoftAP tetap aktif sebagai jalur setup.'],
], [20 * mm, 154 * mm]), PageBreak()]

story += [P('Membaca nilai dan grafik dengan benar', 'UserH1')]
story += [P('Nilai pada tab dashboard berasal dari endpoint pengukuran terbaru. Dashboard menampilkan nol ketika parameter belum dikalibrasi, tetapi nol tersebut bukan hasil ukur fisik. Selalu baca status calibration di API atau label Kalibrasi pada dashboard sebelum memakai nilai sebagai data teknis.', 'UserBody')]
story += [data_diagram(), Spacer(1, 4)]
story += [table([
    ['Kondisi', 'Tampilan / arti'],
    ['Belum ada paket valid', 'Indikator Sensor menunggu atau tidak aktif. Raw dan nilai teknik tidak boleh dipakai sebagai pembacaan.'],
    ['not_calibrated atau partial', 'Field electrical tetap numerik 0 pada API. Raw code dapat terlihat untuk diagnostik, tetapi tidak setara Volt, Ampere, Watt, atau Wh.'],
    ['calibrated', 'Firmware menerbitkan voltage_v, current_a, active_power_w, energy_wh, apparent_power_va, dan power_factor. Energi dihitung sejak boot dan kembali nol saat reboot.'],
    ['0 LATCH ON', 'Grafik memaksa sumbu Y memasukkan nol dan memberi garis putus-putus pada Y=0. Saat OFF, skala Y mengikuti data.'],
    ['Energy Wh/kWh', 'Mengubah satuan tampilan dan grafik saja; nilai sumber dan kalkulasi internal tetap Wh.'],
], [48 * mm, 126 * mm], small=True), Spacer(1, 7)]

story += [P('REST API v1', 'UserH2')]
story += [table([
    ['Endpoint', 'Kegunaan'],
    ['GET /', 'Dashboard lokal responsif.'],
    ['GET /api/v1', 'Indeks API.'],
    ['GET /api/v1/capabilities', 'Feature flag dashboard, Wi-Fi, relay evaluation, dan metering.'],
    ['GET /api/v1/status', 'Firmware, Wi-Fi, state relay yang diperintah, serta status sistem.'],
    ['GET /api/v1/measurements/latest', 'Nilai electrical, calibration, dan raw_codes.'],
    ['GET /api/v1/health', 'Kesehatan komunikasi BL0940 serta counter paket.'],
    ['POST /api/v1/settings/wifi dan /reset', 'Simpan atau hapus Wi-Fi existing; memerlukan HTTP Basic Authentication.'],
    ['POST /api/v1/settings/calibration', 'Kalibrasi runtime; memerlukan HTTP Basic Authentication.'],
], [68 * mm, 106 * mm], small=True), PageBreak()]

story += [P('Kontrol relay, Wi-Fi, dan batas penggunaan', 'UserH1')]
story += [P('Tombol ON/OFF pada dashboard hanya tersedia bila firmware dibangun dengan aktuasi relay evaluation diizinkan. Ketika firmware mengetahui perintah terakhir sejak boot, tombol untuk keadaan yang sama dinonaktifkan: state ON hanya menyisakan OFF, dan state OFF hanya menyisakan ON. Jika state belum diketahui, keduanya dapat dipilih.', 'UserBody')]
story += [relay_state_diagram(), Spacer(1, 5)]
story += [table([
    ['Topik', 'Batas penting'],
    ['Status relay', 'Status dashboard adalah state perintah firmware, bukan feedback kontak fisik. Board tidak memiliki feedback kontak relay.'],
    ['Endpoint relay', 'GET /relay/on dan GET /relay/off tersedia untuk evaluasi build yang mengizinkan aktuasi. Endpoint ini tidak merupakan proteksi listrik atau klaim kontrol produk.'],
    ['Wi-Fi reset', 'Tahan tombol fisik setelah boot normal selama 10 detik untuk menghapus Wi-Fi tersimpan. Jangan menahan GPIO0 saat power-up karena itu pin boot ESP8266.'],
    ['OTA', 'Tidak tersedia pada flash fisik 512 KB. Gunakan pemrograman serial hanya saat AC terlepas.'],
    ['Riwayat', 'Riwayat grafik berada di browser dan hilang bila halaman direfresh. Untuk histori permanen atau biaya, gunakan aplikasi/pengendali eksternal.'],
], [48 * mm, 126 * mm], small=True), Spacer(1, 7)]

story += [P('Batas produk yang masih berlaku', 'UserH2')]
story += [table([
    ['Bidang', 'Status'],
    ['Rating tegangan, arus, daya, beban, dan akurasi', 'Belum dirilis sebagai klaim produk. Nilai relay komponen tidak otomatis menjadi rating SmartPlug.'],
    ['Proteksi, keselamatan mains, dan sertifikasi', 'Belum divalidasi untuk penggunaan produk umum. Dokumen ini bukan panduan pemasangan listrik.'],
    ['Harmonik, ketidakseimbangan fase, dan stabilisasi suplai', 'Tidak tersedia.'],
    ['Standby/vampire dan anomali tegangan', 'Informasi setelah kalibrasi; tidak memutus beban dan bukan proteksi.'],
], [58 * mm, 116 * mm], small=True), Spacer(1, 8)]
story += [P('Referensi source: firmware/src/SmartPlugApi.cpp, firmware/src/main.cpp, firmware/include/SmartPlugMetering.h, dan firmware/LOCAL-API.md. Dokumen ini diperbarui untuk dashboard lokal R2.5; build dan uji hardware nyata tetap memerlukan verifikasi terpisah.', 'UserSmall')]

doc.build(story)
print(OUT)
