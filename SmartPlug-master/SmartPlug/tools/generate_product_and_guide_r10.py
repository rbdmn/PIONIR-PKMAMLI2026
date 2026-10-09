from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate, Frame, PageBreak, PageTemplate, Paragraph, Spacer,
    Table, TableStyle,
)

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output' / 'pdf'
PRODUCT = OUT / 'SmartPlug-Product-Datasheet-R1.1.pdf'
GUIDE = OUT / 'SmartPlug-User-and-Integration-Guide-R1.1.pdf'

for candidate in (Path('C:/Windows/Fonts/arial.ttf'), Path('C:/Windows/Fonts/Arial.ttf')):
    if candidate.exists():
        pdfmetrics.registerFont(TTFont('SmartPlugSans', str(candidate)))
        break
else:
    raise RuntimeError('Arial font tidak ditemukan')

NAVY = colors.HexColor('#102A43')
BLUE = colors.HexColor('#1769AA')
TEAL = colors.HexColor('#087F8C')
INK = colors.HexColor('#172B3A')
MUTED = colors.HexColor('#5A7184')
LINE = colors.HexColor('#D5E1EA')
PALE = colors.HexColor('#F4F8FB')
PALE_BLUE = colors.HexColor('#E9F3FB')
PALE_TEAL = colors.HexColor('#E8F6F4')
AMBER = colors.HexColor('#FFF7E7')
RED = colors.HexColor('#A9363D')

styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name='TitleSP', fontName='SmartPlugSans', fontSize=27,
                          leading=32, textColor=NAVY, spaceAfter=3))
styles.add(ParagraphStyle(name='SubtitleSP', fontName='SmartPlugSans', fontSize=14,
                          leading=18, textColor=BLUE, spaceAfter=8))
styles.add(ParagraphStyle(name='H1SP', fontName='SmartPlugSans', fontSize=16,
                          leading=20, textColor=NAVY, spaceBefore=7, spaceAfter=7))
styles.add(ParagraphStyle(name='H2SP', fontName='SmartPlugSans', fontSize=11,
                          leading=14, textColor=BLUE, spaceBefore=8, spaceAfter=5))
styles.add(ParagraphStyle(name='BodySP', fontName='SmartPlugSans', fontSize=9,
                          leading=12.5, textColor=INK, spaceAfter=5))
styles.add(ParagraphStyle(name='SmallSP', fontName='SmartPlugSans', fontSize=7.2,
                          leading=9.2, textColor=MUTED))
styles.add(ParagraphStyle(name='HeadSP', fontName='SmartPlugSans', fontSize=7.3,
                          leading=9.2, textColor=colors.white))
styles.add(ParagraphStyle(name='CellSP', fontName='SmartPlugSans', fontSize=7.5,
                          leading=9.8, textColor=INK))
styles.add(ParagraphStyle(name='CellSmallSP', fontName='SmartPlugSans', fontSize=6.8,
                          leading=8.6, textColor=INK))
styles.add(ParagraphStyle(name='CodeSP', fontName='Courier', fontSize=6.9,
                          leading=8.8, textColor=INK))


def p(text, style='BodySP'):
    return Paragraph(text, styles[style])


def tbl(rows, widths, small=False, tint=None):
    data = []
    for row_index, row in enumerate(rows):
        data.append([
            p(cell, 'HeadSP' if row_index == 0 else ('CellSmallSP' if small else 'CellSP'))
            for cell in row
        ])
    result = Table(data, colWidths=widths, repeatRows=1, hAlign='LEFT')
    commands = [
        ('BACKGROUND', (0, 0), (-1, 0), NAVY),
        ('GRID', (0, 0), (-1, -1), 0.35, LINE),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 5),
        ('RIGHTPADDING', (0, 0), (-1, -1), 5),
        ('TOPPADDING', (0, 0), (-1, -1), 4),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
    ]
    if tint:
        commands.append(('BACKGROUND', (0, 1), (-1, -1), tint))
    result.setStyle(TableStyle(commands))
    return result


def note(text, tone='amber'):
    background, border = (AMBER, colors.HexColor('#C48A21')) if tone == 'amber' else (PALE_TEAL, TEAL)
    result = Table([[p(text)]], colWidths=[174 * mm])
    result.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), background),
        ('BOX', (0, 0), (-1, -1), 0.55, border),
        ('LEFTPADDING', (0, 0), (-1, -1), 8),
        ('RIGHTPADDING', (0, 0), (-1, -1), 8),
        ('TOPPADDING', (0, 0), (-1, -1), 7),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 7),
    ]))
    return result


def flow(items):
    cells = [[p('<b>%d</b><br/>%s' % (index + 1, text), 'CellSP') for index, text in enumerate(items)]]
    table = Table(cells, colWidths=[174 * mm / len(items)] * len(items), hAlign='LEFT')
    table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), PALE_BLUE),
        ('GRID', (0, 0), (-1, -1), 0.5, BLUE),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('LEFTPADDING', (0, 0), (-1, -1), 7),
        ('RIGHTPADDING', (0, 0), (-1, -1), 7),
        ('TOPPADDING', (0, 0), (-1, -1), 8),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 8),
    ]))
    return table


def document(path, title, footer):
    doc = BaseDocTemplate(
        str(path), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
        topMargin=17 * mm, bottomMargin=18 * mm, title=title,
        author='SmartPlug Engineering',
    )

    def draw_footer(canvas, current_doc):
        canvas.saveState()
        canvas.setStrokeColor(LINE)
        canvas.line(18 * mm, 13 * mm, 192 * mm, 13 * mm)
        canvas.setFillColor(MUTED)
        canvas.setFont('SmartPlugSans', 7)
        canvas.drawString(18 * mm, 8.5 * mm, footer)
        canvas.drawRightString(192 * mm, 8.5 * mm, 'Halaman %d' % current_doc.page)
        canvas.restoreState()

    doc.addPageTemplates([
        PageTemplate(id='main', frames=[Frame(doc.leftMargin, doc.bottomMargin,
                     doc.width, doc.height, id='main')], onPage=draw_footer)
    ])
    return doc


def build_product():
    story = [
        p('SMARTPLUG', 'TitleSP'),
        p('Product Datasheet R1.1', 'SubtitleSP'),
        p('Revisi 1 September 2026 | ESP-07 / ESP8266 | BL0940 | local-only dashboard', 'SmallSP'),
        Spacer(1, 5),
        p('SmartPlug adalah monitor energi AC satu kanal dengan Wi-Fi lokal dan relay latching. Perangkat menyediakan dashboard lokal, REST API v1, kalibrasi tersimpan, serta kontrol relay terautentikasi untuk sistem 220 VAC 50 Hz.', 'BodySP'),
        p('Identitas dan ruang lingkup', 'H1SP'),
        tbl([
            ['Bidang', 'Spesifikasi'],
            ['Nama', 'SmartPlug'],
            ['Source board', 'smartPlug2Ver2 / smartPlug2'],
            ['MCU dan radio', 'ESP-07 berbasis ESP8266'],
            ['IC metering', 'BL0940, komunikasi UART 4800 baud'],
            ['Relay source', 'HFE20-1/5-1HST-L2, latching dual-coil, satu kontak NO'],
            ['Firmware local', 'smartplug-bringup 0.5.0-local-secure, profile esp07_local'],
            ['Sistem kerja', '220 VAC, 50 Hz; Wi-Fi lokal SoftAP + Station'],
        ], [50 * mm, 124 * mm], tint=PALE),
        Spacer(1, 8),
        p('Fungsi firmware yang tersedia', 'H2SP'),
        tbl([
            ['Fungsi', 'Perilaku'],
            ['Monitoring lokal', 'Dashboard dan REST API HTTP lokal; tidak memerlukan internet.'],
            ['Metering', 'Polling 500 ms, checksum BL0940 tervalidasi, moving average hingga 10 sampel valid.'],
            ['Grafik', 'Voltage, Current, Watt, Energy; rentang 1-60 menit, tooltip, statistik, Wh/kWh, dan zero latch. Riwayat grafik berada di browser.'],
            ['Kalibrasi', 'Referensi V/A/W disimpan di EEPROM terversi dengan CRC32 dan diterapkan kembali saat boot.'],
            ['Kontrol relay', 'Perintah ON/OFF terautentikasi melalui dashboard dan POST API; dashboard mengelola tombol berdasarkan state relay terbaru.'],
            ['Keamanan lokal', 'Kredensial awal unik per reset, session 15 menit, cookie HttpOnly/SameSite, CSRF token, lockout login, dan rate-limit.'],
        ], [45 * mm, 129 * mm], small=True),
        p('Arsitektur ringkas', 'H1SP'),
        flow(['Mains AC', 'BL0940 + shunt / divider', 'ESP-07 firmware', 'Wi-Fi lokal + dashboard']),
        Spacer(1, 8),
        tbl([
            ['Bagian', 'Fakta dari source'],
            ['Input / output', 'P1 input: EARTH, NETRAL, LINE. Output: J1 LINE, J2 OUT, J3 EARTH.'],
            ['Jalur relay', 'NETRAL -> R15 0.5 mOhm -> NET_OUT -> kontak relay -> OUT. LINE menuju J1 langsung.'],
            ['Sense tegangan', 'Lima resistor seri 390 kOhm dan resistor sisi rendah 523 Ohm.'],
            ['Catu internal', 'U1 AC/DC source ke +5 V lalu AMS1117-3.3 ke rail 3.3 V.'],
            ['Antarmuka servis', 'U6 enam pin: VCC, GND, RXD, TXD, RST, GPIO0.'],
            ['GPIO utama', 'GPIO5 relay SET; GPIO4 relay RESET; GPIO0 tombol config; GPIO2 LED active-low.'],
        ], [48 * mm, 126 * mm], small=True),
        Spacer(1, 8),
        p('Referensi dokumen: schematic/PCB SmartPlugV2, firmware SmartPlug local 0.5.0, dan kontrak LOCAL-API.md.', 'SmallSP'),
    ]
    document(PRODUCT, 'SmartPlug Product Datasheet R1.1',
             'SmartPlug Product Datasheet R1.1').build(story)


def build_guide():
    story = [
        p('SMARTPLUG', 'TitleSP'),
        p('Panduan Pengguna dan Integrasi Lokal R1.1', 'SubtitleSP'),
        p('Revisi 1 September 2026 | untuk dashboard, commissioning, dan REST API lokal', 'SmallSP'),
        Spacer(1, 5),
        p('Panduan ini menjelaskan penggunaan dashboard, commissioning, kalibrasi, kontrol relay, dan REST API SmartPlug pada jaringan lokal.', 'BodySP'),
        p('<b>Informasi akses awal:</b> catat SSID, password AP, dan password admin dari label atau record provisioning. Perangkat baru dan perangkat setelah factory reset juga mencetak kredensial awal pada serial boot.', 'BodySP'),
        Spacer(1, 8),
        p('Alur penggunaan', 'H1SP'),
        flow(['Hubungkan ke AP SmartPlug', 'Buka 192.168.4.1', 'Baca sensor dan kalibrasi', 'Login hanya untuk mengubah perangkat']),
        Spacer(1, 8),
        p('Terhubung ke perangkat', 'H2SP'),
        tbl([
            ['Langkah', 'Tindakan'],
            ['1', 'Cari Wi-Fi bernama SmartPlug-&lt;device-id&gt; dan masukkan password AP yang dicatat saat provisioning.'],
            ['2', 'Buka http://192.168.4.1. Pesan "no internet" dari telepon/laptop adalah normal untuk jaringan lokal ini.'],
            ['3', 'Dashboard membuka telemetry tanpa login. Password admin diminta hanya ketika menjalankan relay, mengubah Wi-Fi, kalibrasi, atau konfigurasi access point.'],
            ['4', 'Jika session habis, masukkan password admin lagi. Session berlaku maksimal 15 menit.'],
        ], [18 * mm, 156 * mm]),
        Spacer(1, 8),
        p('Makna indikator dan pembacaan', 'H2SP'),
        tbl([
            ['Indikator', 'Arti'],
            ['Sensor: aktif', 'Paket BL0940 checksum-valid diterima dan pembacaan meter sedang berjalan.'],
            ['Sensor: menunggu', 'Firmware/AP berjalan tetapi belum menerima paket valid pertama.'],
            ['Sensor: tidak aktif', 'Polling sudah terjadi namun data valid tidak diterima dalam batas waktu. Periksa supply/komunikasi meter secara aman.'],
            ['Kalibrasi not_calibrated / partial', 'Nilai electrical ditampilkan sebagai 0 placeholder. Ini bukan Volt, Ampere, Watt, atau Wh fisik.'],
            ['Kalibrasi calibrated', 'Koefisien V/A/W tersedia dan nilai engineering tampil pada dashboard serta API.'],
        ], [52 * mm, 122 * mm], small=True),
        PageBreak(),
        p('Grafik dan data', 'H1SP'),
        p('Dashboard memperbarui data setiap 500 ms. Tegangan, arus, daya aktif, dan energi menggunakan moving average hingga 10 sampel valid; jendela penuh mencakup sekitar lima detik. Gunakan tab Voltage, Current, Watt, atau Energy untuk mengganti seri.', 'BodySP'),
        tbl([
            ['Kontrol grafik', 'Fungsi'],
            ['Range X', 'Slider 1 sampai 60 menit. Riwayat berada dalam memori browser dan hilang saat halaman direfresh.'],
            ['0 LATCH', 'Saat ON, skala Y selalu memuat nol dan ditandai garis putus-putus. Saat OFF, skala mengikuti data.'],
            ['Energy Wh/kWh', 'Mengubah satuan tampilan dan grafik; sumber internal tetap Wh.'],
            ['Tooltip / statistik', 'Menampilkan titik data serta nilai terakhir, minimum, rata-rata, dan maksimum dalam jendela aktif.'],
            ['Energi', 'Counter energi dimulai pada boot perangkat dan menyajikan total energi untuk sesi aktif.'],
        ], [52 * mm, 122 * mm], small=True),
        Spacer(1, 8),
        p('Kalibrasi yang benar', 'H2SP'),
        p('Gunakan alat referensi yang sesuai dan beban stabil. Isi Voltage, Current, atau Watt pada panel Kalibrasi lalu pilih tombol parameter yang sesuai. Firmware menolak nilai referensi nol, raw meter nol, atau input tidak valid. Hasil disimpan ke EEPROM dengan CRC dan dimuat kembali saat boot.', 'BodySP'),
        tbl([
            ['Urutan', 'Praktik minimum'],
            ['1', 'Pastikan Sensor aktif dan raw meter berubah/masuk akal.'],
            ['2', 'Gunakan pembacaan alat referensi sebagai input, bukan angka perkiraan.'],
            ['3', 'Kalibrasikan V, A, dan W. State menjadi calibrated hanya bila ketiganya tersedia.'],
            ['4', 'Simpan catatan alat, beban, tanggal, dan hasil untuk setiap perangkat.'],
        ], [18 * mm, 156 * mm]),
        p('Konfigurasi jaringan dan keamanan', 'H1SP'),
        tbl([
            ['Fungsi', 'Cara dan akibat'],
            ['Wi-Fi existing', 'Buka panel Sambungkan ke Wi-Fi yang ada, isi SSID/password, lalu simpan. AP SmartPlug tetap aktif sebagai jalur pemulihan.'],
            ['Ubah AP/admin', 'Pada panel Pengaturan Access Point & Admin, ubah nama AP/password dan opsional password admin. Password admin baru minimal 12 karakter. Perangkat reboot dan session lama tidak berlaku.'],
            ['Factory reset', 'Tahan tombol GPIO0 selama 10 detik setelah boot normal atau gunakan reset dari dashboard. Semua Wi-Fi, kalibrasi, session, dan kredensial pengguna dihapus; kredensial awal baru dibuat. Jangan menahan GPIO0 ketika power-up karena ini pin boot ESP8266.'],
            ['Login lockout', 'Lima login salah berurutan mengunci login selama 60 detik.'],
        ], [48 * mm, 126 * mm], small=True),
        Spacer(1, 8),
        p('Kontrol relay', 'H2SP'),
        p('Tombol ON/OFF mengirim POST terautentikasi dan memperbarui state relay pada dashboard. Jika state ON diketahui, tombol ON dinonaktifkan; jika state OFF diketahui, tombol OFF dinonaktifkan; jika state belum tersedia, kedua tombol tetap dapat digunakan.', 'BodySP'),
        Spacer(1, 8),
        PageBreak(),
        p('REST API untuk aplikasi lokal', 'H1SP'),
        p('Aplikasi lokal memakai alur yang sama dengan dashboard untuk membaca data dan mengubah konfigurasi perangkat.', 'BodySP'),
        flow(['POST login', 'Simpan cookie + CSRF', 'GET data / status', 'POST relay / settings']),
        Spacer(1, 8),
        tbl([
            ['Endpoint', 'Kegunaan'],
            ['GET /api/v1/capabilities', 'Feature flag API, security, metering, dan relay actuation.'],
            ['GET /api/v1/status', 'Firmware, device_id, Wi-Fi, state relay terperintah.'],
            ['GET /api/v1/measurements/latest', 'electrical, calibration, raw_codes, dan captured_at_ms.'],
            ['GET /api/v1/health', 'State pembaca meter dan counter paket valid/tidak valid.'],
            ['POST /api/v1/auth/login', 'Form username/password, mengembalikan csrf_token dan cookie session.'],
            ['POST /api/v1/relay', 'Form state=on/off, wajib session + X-CSRF-Token. Minimum 1 detik antar perintah.'],
            ['POST /api/v1/settings/*', 'Wi-Fi, kalibrasi, access point, dan reset; wajib session + CSRF.'],
        ], [65 * mm, 109 * mm], small=True),
        Spacer(1, 8),
        p('Format request mutasi', 'H2SP'),
        tbl([
            ['Komponen', 'Nilai yang dikirim aplikasi'],
            ['Session', 'Cookie session dari response POST /api/v1/auth/login.'],
            ['CSRF', 'Header X-CSRF-Token berisi csrf_token dari response login.'],
            ['Body', 'Form URL-encoded; contoh relay: state=on atau state=off.'],
            ['Respons', 'JSON dengan state terbaru atau error API yang konsisten.'],
        ], [48 * mm, 126 * mm], small=True),
        Spacer(1, 8),
        p('Troubleshooting ringkas', 'H2SP'),
        tbl([
            ['Gejala', 'Tindakan'],
            ['AP terlihat tetapi 192.168.4.1 tidak terbuka', 'Pastikan perangkat klien mendapat IP 192.168.4.x dari DHCP AP; putus/sambung ulang Wi-Fi bila mendapat IP 169.254.x.x.'],
            ['Grafik 0 atau belum ada data', 'Periksa indikator Sensor dan calibration. Nilai 0 saat not_calibrated adalah placeholder.'],
            ['Relay ditolak', 'Login ulang, tunggu minimal satu detik, dan cek feature relay_actuation pada capabilities.'],
            ['Lupa kredensial', 'Gunakan factory reset sesuai prosedur, lalu ambil kredensial awal baru dari record provisioning/serial aman.'],
        ], [55 * mm, 119 * mm], small=True),
        Spacer(1, 9),
    ]
    document(GUIDE, 'SmartPlug User and Integration Guide R1.1',
             'SmartPlug User and Integration Guide R1.1 - local firmware').build(story)


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    build_product()
    build_guide()
    print(PRODUCT)
    print(GUIDE)
