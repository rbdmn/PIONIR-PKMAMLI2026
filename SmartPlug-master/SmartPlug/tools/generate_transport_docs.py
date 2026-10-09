from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import BaseDocTemplate, Frame, PageBreak, PageTemplate, Paragraph, Spacer, Table, TableStyle

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output' / 'pdf'
REST_DS = OUT / 'SmartPlug-REST-Product-Datasheet-R1.0.pdf'
REST_GUIDE = OUT / 'SmartPlug-REST-Integration-Guide-R1.0.pdf'
MQTT_DS = OUT / 'SmartPlug-MQTT-Product-Datasheet-R1.0.pdf'
MQTT_GUIDE = OUT / 'SmartPlug-MQTT-Integration-Guide-R1.0.pdf'

for font in (Path('C:/Windows/Fonts/arial.ttf'), Path('C:/Windows/Fonts/Arial.ttf')):
    if font.exists():
        pdfmetrics.registerFont(TTFont('SmartPlugSans', str(font)))
        break
else:
    raise RuntimeError('Arial font tidak ditemukan')

NAVY = colors.HexColor('#102A43')
BLUE = colors.HexColor('#1769AA')
INK = colors.HexColor('#172B3A')
MUTED = colors.HexColor('#5A7184')
LINE = colors.HexColor('#D5E1EA')
PALE = colors.HexColor('#F4F8FB')
PALE_BLUE = colors.HexColor('#E9F3FB')

styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name='T', fontName='SmartPlugSans', fontSize=26, leading=31, textColor=NAVY, spaceAfter=2))
styles.add(ParagraphStyle(name='ST', fontName='SmartPlugSans', fontSize=14, leading=18, textColor=BLUE, spaceAfter=6))
styles.add(ParagraphStyle(name='H', fontName='SmartPlugSans', fontSize=15, leading=19, textColor=NAVY, spaceBefore=7, spaceAfter=5))
styles.add(ParagraphStyle(name='B', fontName='SmartPlugSans', fontSize=9, leading=12.2, textColor=INK, spaceAfter=5))
styles.add(ParagraphStyle(name='S', fontName='SmartPlugSans', fontSize=7.4, leading=9.2, textColor=MUTED))
styles.add(ParagraphStyle(name='C', fontName='SmartPlugSans', fontSize=7.5, leading=9.7, textColor=INK))
styles.add(ParagraphStyle(name='CH', fontName='SmartPlugSans', fontSize=7.4, leading=9.1, textColor=colors.white))


def p(text, style='B'):
    return Paragraph(text, styles[style])


def table(rows, widths, tint=None):
    data = [[p(cell, 'CH' if r == 0 else 'C') for cell in row] for r, row in enumerate(rows)]
    result = Table(data, colWidths=widths, repeatRows=1, hAlign='LEFT')
    commands = [
        ('BACKGROUND', (0, 0), (-1, 0), NAVY),
        ('GRID', (0, 0), (-1, -1), .35, LINE),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 5), ('RIGHTPADDING', (0, 0), (-1, -1), 5),
        ('TOPPADDING', (0, 0), (-1, -1), 4), ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
    ]
    if tint:
        commands.append(('BACKGROUND', (0, 1), (-1, -1), tint))
    result.setStyle(TableStyle(commands))
    return result


def flow(items):
    result = Table([[p(f'<b>{i + 1}</b><br/>{item}', 'C') for i, item in enumerate(items)]],
                   colWidths=[174 * mm / len(items)] * len(items), hAlign='LEFT')
    result.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), PALE_BLUE), ('GRID', (0, 0), (-1, -1), .5, BLUE),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'), ('LEFTPADDING', (0, 0), (-1, -1), 7),
        ('RIGHTPADDING', (0, 0), (-1, -1), 7), ('TOPPADDING', (0, 0), (-1, -1), 7),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 7),
    ]))
    return result


def doc(path, title):
    result = BaseDocTemplate(str(path), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
                             topMargin=17 * mm, bottomMargin=18 * mm, title=title,
                             author='SmartPlug Engineering')
    def footer(canvas, current):
        canvas.saveState(); canvas.setStrokeColor(LINE); canvas.line(18 * mm, 13 * mm, 192 * mm, 13 * mm)
        canvas.setFillColor(MUTED); canvas.setFont('SmartPlugSans', 7)
        canvas.drawString(18 * mm, 8.5 * mm, title); canvas.drawRightString(192 * mm, 8.5 * mm, f'Halaman {current.page}')
        canvas.restoreState()
    result.addPageTemplates([PageTemplate(id='main', frames=[Frame(result.leftMargin, result.bottomMargin, result.width, result.height, id='main')], onPage=footer)])
    return result


def header(subtitle, line):
    return [p('SMARTPLUG', 'T'), p(subtitle, 'ST'), p(line, 'S'), Spacer(1, 5)]


def build_rest_datasheet():
    story = header('REST API Product Datasheet R1.0', 'Firmware: smartplug-bringup 0.6.0-rest | Build profile: esp07_rest | 1 September 2026')
    story += [
        p('SmartPlug REST adalah firmware monitoring energi dan kontrol relay melalui dashboard Wi-Fi lokal dan REST API v1.', 'B'),
        p('Identitas dan fungsi', 'H'),
        table([
            ['Bidang', 'Spesifikasi'],
            ['Platform', 'ESP-07 / ESP8266, BL0940 UART 4800 baud, relay latching dual-coil.'],
            ['Koneksi', 'SoftAP SmartPlug + Wi-Fi Station; dashboard pada http://192.168.4.1.'],
            ['Monitoring', 'Polling 500 ms, checksum-valid, moving average 10 sampel, grafik Voltage / Current / Watt / Energy.'],
            ['Kalibrasi', 'Referensi V/A/W disimpan EEPROM dengan record terversi dan CRC32.'],
            ['Relay', 'Dashboard dan POST terautentikasi mengirim perintah ON/OFF; tombol mengikuti state relay terbaru.'],
            ['Keamanan', 'Kredensial awal setup, session 15 menit, HttpOnly cookie, SameSite, CSRF, lockout, rate-limit, audit RAM. Ganti password setelah setup.'],
        ], [43 * mm, 131 * mm], PALE),
        p('REST API v1', 'H'),
        table([
            ['Kelompok', 'Endpoint'],
            ['Pembacaan', 'GET /api/v1/capabilities, /status, /measurements/latest, /health.'],
            ['Autentikasi', 'POST /api/v1/auth/login dan /auth/logout; GET /auth/session.'],
            ['Kontrol', 'POST /api/v1/relay dengan state=on atau state=off.'],
            ['Konfigurasi', 'POST /api/v1/settings/wifi, /settings/calibration, /settings/access, /settings/wifi/reset.'],
        ], [43 * mm, 131 * mm]),
        p('Alur integrasi', 'H'), flow(['Hubungkan ke AP', 'Login admin', 'GET telemetry', 'POST kontrol / konfigurasi']),
        Spacer(1, 8),
        p('REST profile menggunakan API sebagai jalur integrasi utama. Kontrak endpoint, payload, dan session dijelaskan pada SmartPlug REST Integration Guide R1.0.', 'S'),
    ]
    doc(REST_DS, 'SmartPlug REST Product Datasheet R1.0').build(story)


def build_rest_guide():
    story = header('REST API Integration Guide R1.0', 'Firmware: smartplug-bringup 0.6.0-rest | Build profile: esp07_rest | 1 September 2026')
    story += [
        p('Koneksi dan login', 'H'),
        table([
            ['Langkah', 'Tindakan'],
            ['1', 'Hubungkan klien ke SSID <font face="Courier">SmartPlug-Setup</font> menggunakan password <font face="Courier">SmartPlug123</font>.'],
            ['2', 'Buka http://192.168.4.1 untuk dashboard dan telemetry lokal.'],
            ['3', 'POST /api/v1/auth/login dengan form username dan password. Simpan cookie sp_session serta csrf_token.'],
            ['4', 'Sertakan cookie dan header X-CSRF-Token pada setiap POST mutasi.'],
        ], [18 * mm, 156 * mm]),
        p('Format request', 'H'),
        table([
            ['Operasi', 'Request dan respons'],
            ['Baca sample', 'GET /api/v1/measurements/latest -> electrical, raw_codes, calibration, captured_at_ms.'],
            ['Relay ON', 'POST /api/v1/relay | Content-Type: application/x-www-form-urlencoded | body: state=on.'],
            ['Relay OFF', 'POST /api/v1/relay | Content-Type: application/x-www-form-urlencoded | body: state=off.'],
            ['Kalibrasi', 'POST /api/v1/settings/calibration dengan salah satu: voltage_v, current_a, atau power_w.'],
            ['Wi-Fi', 'POST /api/v1/settings/wifi dengan ssid dan password.'],
        ], [42 * mm, 132 * mm], PALE),
        p('Respons operasional', 'H'),
        table([
            ['Kode', 'Makna'],
            ['200', 'Pembacaan atau perubahan konfigurasi berhasil.'],
            ['202', 'Perintah relay masuk antrean pulse.'],
            ['400 / 401 / 403', 'Input, session, atau CSRF perlu diperbarui.'],
            ['429', 'Tunggu interval rate-limit sebelum mengirim kembali perintah.'],
        ], [42 * mm, 132 * mm]),
        p('Dashboard memperbarui telemetry setiap 500 ms. State calibration menunjukkan not_calibrated, partial, atau calibrated; nilai electrical sebelum kalibrasi ditampilkan sebagai 0.', 'S'),
    ]
    doc(REST_GUIDE, 'SmartPlug REST Integration Guide R1.0').build(story)


def build_mqtt_datasheet():
    story = header('MQTT Product Datasheet R1.0', 'Firmware: smartplug-bringup 0.6.0-mqtt | Build profile: esp07_mqtt | 1 September 2026')
    story += [
        p('SmartPlug MQTT mengirim pembacaan energi ke broker MQTT dan menerima perintah relay dari aplikasi, server, atau dashboard lain di jaringan.', 'B'),
        p('Yang perlu disiapkan', 'H'),
        table([
            ['Kebutuhan', 'Penjelasan untuk pengguna'],
            ['Board SmartPlug', 'Board sudah di-upload dengan image build profile <font face="Courier">esp07_mqtt</font>.'],
            ['Kabel serial', 'Hanya dipakai installer untuk mengisi alamat broker; tidak diperlukan untuk menyambung ke Access Point SmartPlug.'],
            ['Wi-Fi tujuan', 'Nama serta password Wi-Fi yang dapat menjangkau broker MQTT.'],
            ['Broker MQTT', 'Alamat host/IP dan port broker. Port umum adalah 1883. Siapkan username/password bila broker menggunakannya.'],
            ['Aplikasi MQTT', 'Contoh: MQTT Explorer, Node-RED, Home Assistant, atau mosquitto_sub / mosquitto_pub.'],
        ], [43 * mm, 131 * mm], PALE),
        p('Cara kerja singkat', 'H'),
        flow(['SmartPlug membaca BL0940', 'Wi-Fi Station terhubung', 'MQTT publish telemetry', 'Aplikasi membaca dan mengirim command']),
        p('Istilah penting', 'H'),
        table([
            ['Istilah', 'Arti'],
            ['Access Point SmartPlug', 'Wi-Fi yang dipancarkan SmartPlug untuk setup lokal. Dashboard dibuka di 192.168.4.1.'],
            ['Wi-Fi Station', 'Wi-Fi lokasi instalasi yang digunakan SmartPlug untuk mencapai broker MQTT.'],
            ['Broker MQTT', 'Server perantara yang menerima telemetry dan meneruskan command ke SmartPlug.'],
            ['Base topic', 'Jalur utama perangkat, misalnya <font face="Courier">smartplug/A1B2C3</font>. Semua topik perangkat berada di bawah jalur ini.'],
        ], [43 * mm, 131 * mm]),
        PageBreak(),
        p('Fungsi firmware MQTT', 'H'),
        table([
            ['Bidang', 'Spesifikasi'],
            ['Platform', 'ESP-07 / ESP8266, BL0940 UART 4800, relay latching dual-coil.'],
            ['Jalur integrasi', 'Wi-Fi Station ke broker MQTT; SoftAP dan dashboard lokal tetap aktif.'],
            ['Konfigurasi broker', 'Host, port, user, password, dan base topic disimpan dalam record EEPROM MQTT dengan CRC32.'],
            ['Telemetry', 'Publish setiap 500 ms pada <font face="Courier">&lt;base&gt;/telemetry</font>.'],
            ['Availability', 'LWT offline dan retained online pada <font face="Courier">&lt;base&gt;/availability</font>.'],
            ['Relay', 'Subscribe <font face="Courier">&lt;base&gt;/cmd/relay</font>; publish hasil ke <font face="Courier">&lt;base&gt;/ack/relay</font>.'],
            ['State', 'Status retained pada <font face="Courier">&lt;base&gt;/state</font>.'],
        ], [43 * mm, 131 * mm], PALE),
        p('Topik MQTT', 'H'),
        table([
            ['Topik', 'Payload / arah'],
            ['&lt;base&gt;/telemetry', 'Device -> broker | electrical, energy_wh, raw_codes, counter paket.'],
            ['&lt;base&gt;/state', 'Device -> broker retained | device_id, relay, relay_actuation, wifi_connected.'],
            ['&lt;base&gt;/availability', 'Device -> broker retained | online atau offline.'],
            ['&lt;base&gt;/cmd/relay', 'Broker -> device | on atau off.'],
            ['&lt;base&gt;/ack/relay', 'Device -> broker | accepted dan state.'],
        ], [52 * mm, 122 * mm]),
        Spacer(1, 7),
        p('Mulai paling mudah: sambungkan ke <font face="Courier">SmartPlug-Setup</font> dengan password <font face="Courier">SmartPlug123</font>, atur Wi-Fi Station dari dashboard, isi broker melalui serial, lalu subscribe topik <font face="Courier">&lt;base&gt;/#</font> dari aplikasi MQTT. Panduan langkah demi langkah ada pada SmartPlug MQTT Integration Guide R1.0.', 'S'),
    ]
    doc(MQTT_DS, 'SmartPlug MQTT Product Datasheet R1.0').build(story)


def build_mqtt_guide():
    story = header('MQTT Integration Guide R1.0', 'Firmware: smartplug-bringup 0.6.0-mqtt | Build profile: esp07_mqtt | 1 September 2026')
    story += [
        p('Panduan ini dimulai dari board yang baru dinyalakan sampai telemetry muncul di aplikasi MQTT. Kerjakan tahap berikut secara berurutan.', 'B'),
        p('Tahap 1 - Siapkan informasi', 'H'),
        table([
            ['Siapkan', 'Contoh / cara mendapatkannya'],
            ['Wi-Fi lokasi', 'SSID: <font face="Courier">Rumah-2G</font>; password Wi-Fi lokasi. Gunakan jaringan 2.4 GHz yang dapat menjangkau broker.'],
            ['Broker MQTT', 'Host: <font face="Courier">192.168.1.10</font>; port: <font face="Courier">1883</font>.'],
            ['Akun broker', 'Username/password broker bila broker tidak menerima koneksi anonymous.'],
            ['Base topic', 'Gunakan <font face="Courier">smartplug/&lt;device-id&gt;</font>. Ganti <font face="Courier">&lt;device-id&gt;</font> dengan ID yang tercetak di serial boot.'],
        ], [43 * mm, 131 * mm], PALE),
        p('Tahap 2 - Nyalakan dan masuk ke dashboard', 'H'),
        table([
            ['Langkah', 'Yang dilakukan'],
            ['1', 'Di telepon/laptop, hubungkan ke Wi-Fi <font face="Courier">SmartPlug-Setup</font>.'],
            ['2', 'Masukkan password <font face="Courier">SmartPlug123</font>.'],
            ['3', 'Buka browser ke <font face="Courier">http://192.168.4.1</font>. Pesan jaringan tanpa internet dapat diabaikan selama setup lokal.'],
        ], [18 * mm, 156 * mm]),
        p('Tahap 3 - Hubungkan SmartPlug ke Wi-Fi lokasi', 'H'),
        p('Pada dashboard, buka panel <b>Sambungkan ke Wi-Fi yang ada</b>. Isi Nama Wi-Fi dan Password Wi-Fi lokasi, kemudian tekan <b>Simpan & hubungkan</b>. SmartPlug tetap memancarkan Access Point-nya sehingga dashboard setup tetap dapat dibuka.', 'B'),
        table([
            ['Periksa', 'Hasil yang diharapkan'],
            ['Dashboard', 'Status Wi-Fi Station berubah menjadi connected dan menampilkan alamat IP Station.'],
            ['Serial Monitor', 'Perangkat siap mencoba koneksi broker setelah broker disimpan.'],
        ], [43 * mm, 131 * mm]),
        PageBreak(),
        p('Tahap 4 - Isi broker MQTT melalui serial', 'H'),
        p('Ketik satu per satu di Serial Monitor. Ganti nilai contoh dengan nilai broker Anda. Jangan ketik baris user/password bila broker Anda anonymous.', 'B'),
        table([
            ['Urutan', 'Perintah serial'],
            ['1', 'mqtt host 192.168.1.10'],
            ['2', 'mqtt port 1883'],
            ['3', 'mqtt user smartplug-user  (hanya bila broker memakai akun)'],
            ['4', 'mqtt pass smartplug-password  (hanya bila broker memakai akun)'],
            ['5', 'mqtt topic smartplug/A1B2C3'],
            ['6', 'mqtt save'],
            ['7', 'mqtt show'],
        ], [35 * mm, 139 * mm], PALE),
        p('Cara membaca hasil serial', 'H'),
        table([
            ['Pesan serial', 'Arti dan tindakan'],
            ['OK mqtt_setting_staged', 'Satu nilai sudah diterima, tetapi belum disimpan permanen. Lanjutkan sampai mqtt save.'],
            ['OK mqtt_settings_saved', 'Host, port, akun, dan topic sudah disimpan ke EEPROM.'],
            ['INFO mqtt_connected', 'SmartPlug berhasil masuk ke broker dan langsung mulai publish.'],
            ['WARN mqtt_connect_failed state=...', 'Periksa Wi-Fi Station, host/IP broker, port, serta username/password; lalu jalankan mqtt show.'],
            ['mqtt show: configured=true, connected=true', 'Konfigurasi tersimpan dan koneksi broker aktif.'],
        ], [60 * mm, 114 * mm]),
        p('Tahap 5 - Lihat telemetry dari aplikasi MQTT', 'H'),
        p('Subscribe semua topik perangkat lebih dulu. Contoh untuk mosquitto client:', 'B'),
        table([
            ['Broker anonymous', '<font face="Courier">mosquitto_sub -h 192.168.1.10 -p 1883 -t "smartplug/A1B2C3/#" -v</font>'],
            ['Broker memakai akun', '<font face="Courier">mosquitto_sub -h 192.168.1.10 -p 1883 -u smartplug-user -P smartplug-password -t "smartplug/A1B2C3/#" -v</font>'],
        ], [43 * mm, 131 * mm]),
        PageBreak(),
        p('Tahap 6 - Pastikan topik sudah benar', 'H'),
        table([
            ['Topik yang terlihat', 'Isi yang seharusnya muncul'],
            ['<font face="Courier">smartplug/A1B2C3/availability</font>', '<font face="Courier">online</font> setelah koneksi broker berhasil.'],
            ['<font face="Courier">smartplug/A1B2C3/state</font>', 'JSON status perangkat dan state relay, misalnya relay <font face="Courier">off</font>.'],
            ['<font face="Courier">smartplug/A1B2C3/telemetry</font>', 'JSON telemetry setiap 500 ms: voltage_v, current_a, active_power_w, energy_wh, raw_codes, dan counter paket.'],
        ], [60 * mm, 114 * mm], PALE),
        p('Tahap 7 - Uji perintah relay MQTT', 'H'),
        p('Publish payload <font face="Courier">on</font> atau <font face="Courier">off</font> ke topic command. Contoh:', 'B'),
        table([
            ['Relay ON', '<font face="Courier">mosquitto_pub -h 192.168.1.10 -t "smartplug/A1B2C3/cmd/relay" -m on</font>'],
            ['Relay OFF', '<font face="Courier">mosquitto_pub -h 192.168.1.10 -t "smartplug/A1B2C3/cmd/relay" -m off</font>'],
            ['Konfirmasi', 'Baca <font face="Courier">ack/relay</font> untuk accepted/state dan <font face="Courier">state</font> untuk state terbaru.'],
        ], [43 * mm, 131 * mm]),
        p('Tahap 8 - Kalibrasi pembacaan', 'H'),
        p('Buka dashboard SmartPlug lalu masukkan referensi Voltage, Current, dan Watt pada panel Kalibrasi. Sebelum ketiga parameter tersedia, telemetry electrical ditampilkan sebagai 0; raw_codes tetap menunjukkan data meter yang diterima.', 'B'),
        p('Jika ada masalah', 'H'),
        table([
            ['Gejala', 'Tindakan cepat'],
            ['Tidak menemukan Wi-Fi SmartPlug', 'Cari SSID <font face="Courier">SmartPlug-Setup</font>, lalu restart perangkat bila SSID belum terlihat.'],
            ['mqtt show: configured=false', 'Ulangi host, port, topic, kemudian jalankan mqtt save.'],
            ['configured=true tetapi connected=false', 'Pastikan Wi-Fi Station connected, broker hidup, host/IP dan port benar, serta kredensial broker sesuai.'],
            ['Tidak ada telemetry', 'Pastikan topic yang di-subscribe sama persis dengan mqtt topic; periksa Sensor aktif pada dashboard.'],
            ['Tidak ada ack relay', 'Pastikan publish ke <font face="Courier">&lt;base&gt;/cmd/relay</font> dengan payload huruf kecil on/off.'],
        ], [60 * mm, 114 * mm]),
        p('Perintah pendukung: <font face="Courier">mqtt help</font> menampilkan daftar perintah; <font face="Courier">mqtt clear</font> menghapus konfigurasi broker dan mengembalikan setup broker dari awal.', 'S'),
    ]
    doc(MQTT_GUIDE, 'SmartPlug MQTT Integration Guide R1.0').build(story)


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    build_rest_datasheet(); build_rest_guide(); build_mqtt_datasheet(); build_mqtt_guide()
    for output in (REST_DS, REST_GUIDE, MQTT_DS, MQTT_GUIDE):
        print(output)
