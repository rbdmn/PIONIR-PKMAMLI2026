"""Source-checked Android integration chapter; no firmware mutations."""
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import Preformatted, Spacer, KeepTogether, Table, TableStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont


def append_android(g):
    page, add, sub = g['page'], g['add'], g['sub']
    step, box, table = g['step'], g['box'], g['table']
    story, cw = g['story'], g['CW']
    # New styles, not mutation: the first sixteen pages retain their layout.
    for key, size, leading in [('body',11.5,16.5),('step',11.5,16.5),
                              ('cell',11,14.5),('title',20,25)]:
        g['styles'][key] = ParagraphStyle('android-'+key, parent=g['styles'][key],
                                         fontSize=size, leading=leading)
    original_box = box

    def box(title, text):
        start = len(story)
        original_box(title, text)
        items = story[start:]
        del story[start:]
        story.append(KeepTogether(items))

    def table(headers, rows, widths):
        p = g['p']
        data = [[p('<b>'+h+'</b>', 'cell') for h in headers]]
        data += [[p(value, 'cell') for value in row] for row in rows]
        t = Table(data, colWidths=widths, repeatRows=1, hAlign='LEFT')
        t.setStyle(TableStyle([
            ('VALIGN',(0,0),(-1,-1),'TOP'),
            ('LINEBELOW',(0,0),(-1,0),1.2,colors.black),
            ('LINEBELOW',(0,1),(-1,-1),.35,colors.black),
            ('LEFTPADDING',(0,0),(-1,-1),7),
            ('RIGHTPADDING',(0,0),(-1,-1),8),
            ('TOPPADDING',(0,0),(-1,-1),6),
            ('BOTTOMPADDING',(0,0),(-1,-1),7)]))
        story.extend([t, Spacer(1,8)])

    pdfmetrics.registerFont(TTFont('AndroidCode', 'C:/Windows/Fonts/consolab.ttf'))
    code_style = ParagraphStyle('android-code', fontName='AndroidCode', fontSize=10.5,
                                leading=12.5, textColor=colors.black,
                                spaceBefore=7, spaceAfter=10)

    def code(value):
        value = value.strip('\n')
        for line in value.splitlines():
            assert pdfmetrics.stringWidth(line, 'AndroidCode', 10.5) <= cw-16, line
        story.extend([Preformatted(value, code_style), Spacer(1, 3)])

    def reference(label, url):
        story.append(g['p'](f'<a href="{url}" color="#000000"><u>{label}</u></a>', 'small'))

    # 17
    page('Integrasi aplikasi Android')
    add('Bab ini menjelaskan cara menghubungkan SmartPlug ke satu aplikasi Android. Pengguna mengikuti langkah penambahan perangkat; developer memakai kontrak HTTP untuk membangun fungsi baca dan kontrol. Halaman setup tetap dibuka melalui browser, sedangkan penggunaan harian dilakukan dari aplikasi.')
    add('Nama tombol aplikasi dalam bab ini, seperti Tambah perangkat atau Uji koneksi, adalah <b>contoh rancangan antarmuka</b>, bukan klaim bahwa APK tertentu sudah tersedia. Gunakan aplikasi dari penyedia sistem yang menerapkan kontrak ini. Bab ini tidak memasang APK dan tidak mengubah firmware.')
    sub('Pilih satu jalur untuk setiap unit')
    table(['Jalur','Alamat yang dimasukkan di Android'],[
        ('Langsung / REST','Alamat LAN SmartPlug. Aplikasi membaca pengukuran dan mengirim kontrol langsung ke unit.'),
        ('Melalui server / MQTT','Alamat REST server ESP32. SmartPlug terhubung ke broker MQTT; aplikasi Android tetap memakai REST, bukan akun broker.')
    ],[148,cw-148])
    sub('Peta bab Android')
    table(['Bagian','Halaman'],[
        ('Pengguna: koneksi langsung / melalui server','18 / 19'),
        ('Developer: sesi, pengukuran, dan relay langsung','20-22'),
        ('Developer: REST server dan histori','23-24'),
        ('Izin Android dan contoh Kotlin','25-26'),
        ('Troubleshooting dan uji penerimaan','27'),
        ('Ketentuan implementasi dan referensi','28')
    ],[cw-85,85])
    box('Persiapan sebelum menambah perangkat','Selesaikan setup dan ganti password admin awal pada halaman 3-11. Catat ID unit dan alamat LAN. Untuk jalur server, minta alamat REST server serta token aplikasi kepada pengelola. HP dan unit/server harus saling terjangkau pada jaringan lokal; paket data seluler saja tidak cukup.')

    # 18
    page('Android: koneksi langsung')
    add('Pakai cara ini bila satu aplikasi berkomunikasi langsung ke SmartPlug tanpa server perantara. Semua alamat di bawah adalah contoh; salin alamat LAN yang benar dari halaman setup unit Anda.')
    for n, text in enumerate([
        'Di halaman setup SmartPlug, pastikan Wi-Fi lokasi sudah tersambung. Pilih <b>REST API</b>, simpan, lalu catat <b>ID perangkat</b> dan <b>Alamat perangkat</b>.',
        'Pindahkan Wi-Fi HP dari AP SmartPlug ke Wi-Fi lokasi yang sama. Hindari jaringan tamu yang memblokir komunikasi antarperangkat.',
        'Di aplikasi Android, buka fungsi penambahan unit. Pilih jalur <b>Langsung / REST</b>. Masukkan alamat, misalnya <b>http://192.168.1.25</b>. Jangan menambahkan endpoint pengukuran pada kolom alamat dasar.',
        'Jalankan <b>Uji koneksi</b>. Aplikasi membaca identitas unit. Cocokkan ID yang tampil dengan ID pada halaman setup atau kartu unit sebelum menyimpan.',
        'Beri nama yang mudah dikenali, misalnya SmartPlug Ruang Kerja. Nama tampilan ini disimpan oleh aplikasi; ID unit tetap sama.',
        'Untuk monitoring, aplikasi dapat membaca data tanpa login perangkat. Untuk ON/OFF, masuk dengan username <b>admin</b> dan password admin yang sudah Anda ganti. Ini bukan password Wi-Fi.',
        'Pastikan data tampil dan diperbarui. Coba satu perintah dengan beban uji yang aman. Tunggu hasil selesai sebelum menekan tombol kebalikannya.'
    ],1): step(n,text)
    box('Tanda integrasi berhasil','ID cocok, pengukuran valid tampil, putus koneksi ditandai dengan jelas, dan hasil perintah ON/OFF dapat dibaca kembali. Tombol ON/OFF tidak boleh langsung dianggap berhasil hanya karena sudah ditekan.')
    sub('Jika alamat berubah setelah router mulai ulang')
    add('Lihat alamat LAN terbaru melalui setup, lalu ubah alamat unit di aplikasi dan cocokkan ID lagi. Pengelola router dapat membuat reservasi DHCP agar alamat tetap. Jangan menyalin 192.168.4.1 sebagai alamat harian setelah HP pindah ke router; alamat itu untuk AP setup.')

    # 19
    page('Android: melalui server MQTT')
    add('Pada jalur ini Android tidak perlu login ke broker MQTT. Aplikasi memakai REST server; server yang meneruskan pengukuran dan perintah ke SmartPlug. Aplikasi yang sama dapat menyediakan pilihan jalur tanpa membuat aplikasi kedua.')
    for n,text in enumerate([
        'Minta pengelola menyiapkan server ESP32: Wi-Fi lokasi, broker, API token aplikasi, serta SD card jika histori diperlukan. Pastikan server sudah dapat dijangkau.',
        'Pada halaman setup SmartPlug, sambungkan Wi-Fi lokasi dan pilih <b>MQTT</b>. Isi alamat broker, port, username, password, dan base topic sesuai halaman 9. Untuk ServerSmartPlug, pertahankan base topic <b>smartplug/</b> diikuti ID unit.',
        'Tunggu status <b>MQTT terhubung ke broker</b>. Jika belum terhubung, selesaikan masalah ini sebelum menambah unit di Android.',
        'Hubungkan HP ke Wi-Fi lokasi. Pada aplikasi, pilih <b>Melalui server</b>. Isi alamat REST server, misalnya <b>http://192.168.1.10</b>, dan <b>API token aplikasi</b> dari pengelola. Jangan memakai port 1883 untuk REST.',
        'Jalankan Uji koneksi. Aplikasi meminta daftar unit dari server. Pilih ID SmartPlug yang cocok; jangan memilih hanya berdasarkan urutan daftar.',
        'Simpan nama tampilan. Periksa pengukuran, kondisi koneksi, serta hasil perintah ON/OFF. Jika histori aktif, tunggu interval pencatatan selesai sebelum memeriksa data riwayat.'
    ],1):step(n,text)
    sub('Data akses yang tidak boleh tertukar')
    table(['Data','Dipakai oleh'],[
        ('Akun broker + port MQTT 1883','SmartPlug untuk terhubung ke server.'),
        ('API token + alamat HTTP server','Aplikasi Android untuk membaca dan mengontrol unit melalui server.'),
        ('Password admin SmartPlug','Browser setup perangkat; bukan token aplikasi server.')
    ],[cw/2,cw/2])
    add('Satu jaringan tidak selalu berarti antarperangkat diizinkan berkomunikasi. Jika daftar kosong, minta pengelola memeriksa isolasi Wi-Fi, akun broker, base topic, dan apakah server telah menerima pesan dari unit.')

    # 20
    page('Developer: akses REST langsung')
    add('Gunakan satu konfigurasi per unit: jalur koneksi, base URL, device_id, dan nama tampilan. Base URL tidak diakhiri /api/v1. Baca GET /api/v1/status untuk memverifikasi device_id sebelum menggunakan alamat yang baru dimasukkan.')
    sub('Login untuk kontrol')
    code('''POST /api/v1/auth/login HTTP/1.1
Content-Type: application/x-www-form-urlencoded

username=admin&password=<PASSWORD_URL_ENCODED>

HTTP/1.1 200 OK
Set-Cookie: sp_session=<SESSION>; Path=/;
 HttpOnly; SameSite=Strict; Max-Age=900

{"result":"authenticated","csrf_token":"<CSRF>",
 "expires_in_s":900}''')
    add('Contoh header Set-Cookie dipecah agar terbaca; pada protokol itu satu header. Bentuk body dengan encoder form, bukan penggabungan password mentah. Simpan cookie melalui cookie jar HTTP client dan CSRF token di memori, terpisah untuk setiap perangkat/origin.')
    sub('Aturan sesi yang harus ditangani')
    for n,text in enumerate([
        'Kirim cookie <b>sp_session</b> dan header <b>X-CSRF-Token</b> untuk setiap perubahan state. Body perangkat adalah form URL-encoded; jangan memakai contoh JSON relay server pada endpoint perangkat.',
        'Sesi diberi masa 900 detik. Permintaan terautentikasi memperpanjang batas server, tetapi masa cookie tetap perlu dihormati. Pada 401, hapus sesi lokal dan minta login ulang.',
        'Perangkat hanya menyimpan satu sesi aktif. Login baru, termasuk dari browser setup, mengganti sesi sebelumnya. Hindari beberapa pengontrol yang saling login berulang.',
        'Jika security.password_change_required bernilai true, arahkan pengguna menyelesaikan perubahan password di browser setup. Jangan melewati pembatasan ini.',
        'GET /api/v1/auth/session membaca sesi/CSRF yang masih valid. POST /api/v1/auth/logout memerlukan cookie dan CSRF, lalu hapus kredensial sesi dari aplikasi.'
    ],1):step(n,text)
    add('Lima login salah berurutan memicu penguncian 60 detik. Jangan menjalankan loop login otomatis. Endpoint baca pengukuran/status tetap publik pada LAN/AP; akses publik ini bukan izin mengubah konfigurasi.')

    # 21
    page('Developer: baca pengukuran')
    code('''GET /api/v1/measurements/allparameters

{
  "captured_at_ms": 125000,
  "has_sample": true,
  "fresh": true,
  "sample_age_ms": 120,
  "calibration": "calibrated",
  "electrical": {
    "voltage_v": 220.1, "current_a": 0.42,
    "active_power_w": 86.4,
    "apparent_power_va": 92.5,
    "power_factor": 0.934, "energy_wh": 1234.5
  }
}''')
    add('Contoh respons dipersingkat; angka hanya ilustrasi, bukan hasil uji. /measurements/latest adalah alias. Field tambahan boleh diabaikan. Gunakan tipe Double untuk angka pengukuran, dan jangan mengganti field hilang/null dengan nol.')
    table(['Field / kondisi','Perlakuan aplikasi'],[
        ('has_sample dan fresh','Tampilkan data sebagai terkini hanya bila keduanya true. Sampel lebih tua dari 5 detik dinyatakan tidak fresh.'),
        ('calibration','calibrated, partial, atau not_calibrated. Untuk tampilan seluruh parameter yang valid, tunggu calibrated; selain itu tampilkan Perlu pemeriksaan pemasang, bukan nol pasti.'),
        ('captured_at_ms','Waktu sejak perangkat boot, bukan Unix time. Dapat mulai lagi setelah reboot atau wrap. Jangan mengubahnya menjadi tanggal kalender.'),
        ('electrical.energy_wh','Counter kumulatif dalam Wh. Tampilkan kWh = Wh / 1000; jangan menambahkannya lagi dari W x waktu.'),
        ('Polling','Rekomendasi aplikasi: satu permintaan setiap 2 detik saat layar aktif, berurutan tanpa overlap. Hentikan saat layar ditinggalkan.')
    ],[155,cw-155])
    add('Bila hanya satu parameter diperlukan, gunakan prefix <b>/api/v1/measurements/</b> dengan voltage, current, active-power, apparent-power, power-factor, atau energy. Respons tunggal berisi parameter, value, unit, calibration, has_sample, fresh, dan sample_age_ms. Untuk satu layar lengkap, pilih allparameters.')

    # 22
    page('Developer: kontrol relay langsung')
    step(1,'Baca status. Pastikan relay.actuation_allowed bernilai true, sesi valid, dan tidak ada perintah yang masih menunggu. Gunakan state eksplisit on/off, bukan toggle yang bergantung pada kondisi lama.')
    code('''POST /api/v1/relay HTTP/1.1
Cookie: sp_session=<SESSION>
X-CSRF-Token: <CSRF>
Content-Type: application/x-www-form-urlencoded

state=on

HTTP/1.1 202 Accepted
{"result":"relay_command_queued","state":"on"}''')
    step(2,'Setelah 202, tampilkan Memproses dan nonaktifkan tombol sementara. Baca GET /api/v1/status, misalnya setiap 500 ms dengan batas tunggu aplikasi 5 detik. Ini strategi aplikasi, bukan jaminan waktu respons jaringan.')
    code('''GET /api/v1/status

{"relay":{"state":"on","actuation_allowed":true,
          "command_result":"completed"}}''')
    add('Contoh status dipersingkat. Nyatakan perintah selesai pada tingkat firmware bila command_result=completed dan state sama dengan tujuan. queued/pulsing berarti tunggu; rejected berarti ditolak. Tidak ada command_id pada REST langsung: serialkan perintah dan hindari pengontrol lain selama menunggu.')
    table(['Status terbaru','Tombol aplikasi'],[
        ('on','ON nonaktif; OFF tersedia jika akses kontrol valid.'),
        ('off','OFF nonaktif; ON tersedia jika akses kontrol valid.'),
        ('unknown','Keduanya dapat tersedia jika tersambung dan diizinkan.'),
        ('transitioning / request menunggu','Kedua tombol nonaktif sampai hasil diterima.'),
        ('Koneksi putus / timeout','Nonaktifkan kontrol. Baca ulang status sebelum menawarkan percobaan baru.')
    ],[177,cw-177])
    box('Jangan mengulang POST secara buta','Tunggu sekurangnya satu detik antarperintah. Jika respons hilang, perintah mungkin sudah dijalankan: baca status dahulu. completed berarti pulsa kontrol selesai, bukan pembuktian posisi kontak fisik atau keamanan isolasi listrik.')

    # 23
    page('Developer: baca melalui server')
    add('Gunakan base URL server dan header Authorization: Bearer pada seluruh endpoint /api/v1. Token diberikan pengelola; server tidak menyediakan login username/password aplikasi seperti SmartPlug. Jangan menyertakan token pada URL, log, atau kode APK yang dibagikan.')
    code('''GET /api/v1/devices HTTP/1.1
Authorization: Bearer <API_TOKEN_APLIKASI>

{"devices":[{"device_id":"SP-84F3EB123456",
 "online":true,"last_seen_utc":1789010220,
 "relay":"off","energy_wh":1234.5}]}''')
    add('Pilih ID dari daftar dan cocokkan dengan unit. Simpan device_id sebagai kunci permanen; alamat IP maupun nama tampilan bukan identitas unit. X-API-Key dapat dipakai sebagai alternatif Bearer, tetapi pilih satu mekanisme secara konsisten.')
    code('''GET /api/v1/devices/SP-84F3EB123456/latest
Authorization: Bearer <API_TOKEN_APLIKASI>

{
  "device_id": "SP-84F3EB123456",
  "received_at_utc": 1789010220,
  "online": true, "calibrated": true,
  "electrical": {
    "voltage_v": 220.1, "current_a": 0.42,
    "active_power_w": 86.4,
    "apparent_power_va": 92.5,
    "power_factor": 0.934, "energy_wh": 1234.5
  }
}''')
    sub('Jangan menyamakan format server dan perangkat')
    add('Server memakai calibrated berupa boolean; perangkat memakai calibration berupa teks. Timestamp server adalah detik UTC, bukan milidetik sejak boot. Server saat ini tidak mengirim has_sample/fresh/sample_age_ms seperti perangkat. Buat adapter model yang berbeda, lalu ubah ke model tampilan Android yang sama.')
    box('Indikator koneksi bukan bukti sampel baru','online menyatakan kondisi yang diketahui server. received_at_utc juga dapat diperbarui oleh pesan availability atau parameter tunggal; nilainya bukan waktu pasti satu snapshot lengkap. Jangan memberi label Pengukuran tervalidasi realtime hanya dari dua field ini. Timestamp sampel lengkap yang terpisah perlu ditambahkan pada server untuk jaminan tersebut.')

    # 24
    page('Server: perintah dan histori')
    code('''POST /api/v1/devices/SP-84F3EB123456/relay
Authorization: Bearer <API_TOKEN_APLIKASI>
Content-Type: application/json

{"state":"off"}

HTTP/1.1 202 Accepted
{"command_id":"cmd-125000-1","status":"queued",
 "state":"off"}

GET /api/v1/commands/cmd-125000-1
Authorization: Bearer <API_TOKEN_APLIKASI>

{"command_id":"cmd-125000-1",
 "device_id":"SP-84F3EB123456","state":"off",
 "status":"completed"}''')
    add('Simpan command_id yang dikembalikan, lalu poll hasil berurutan. Status dapat queued, completed, rejected, atau timeout. Server menandai timeout setelah 5 detik tanpa hasil. Hanya satu command pending per unit; riwayat command lama tidak dipertahankan setelah command berikutnya menggantikannya.')
    add('ACK MQTT belum membawa ID transaksi end-to-end. completed adalah hasil korelasi server atas state/ACK, bukan jaminan exactly-once atau feedback kontak. Jangan mengirim perintah paralel dari beberapa aplikasi. Setelah timeout, baca ulang ringkasan unit sebelum mencoba lagi.')
    sub('Riwayat pengukuran dari server')
    code('''GET /api/v1/devices/SP-84F3EB123456/history
    ?from=1789010160&to=1789013760&resolution=1m
Authorization: Bearer <API_TOKEN_APLIKASI>''')
    add('Path dan query di atas adalah satu URL tanpa spasi/baris baru. from/to memakai detik Unix UTC. Pilihan resolution: 1m, 5m, 30m, 1h, atau 1d. Respons berisi device_id, resolution, dan samples; setiap item memiliki timestamp_utc serta enam field electrical seperti halaman 23, langsung di item tanpa pembungkus electrical.')
    add('Minta rentang pendek lebih dahulu; endpoint belum menyediakan pagination. GET /health menyediakan sd_ready dan time_synchronized tanpa token. Histori memerlukan SD siap dan waktu server benar. samples kosong berarti tidak ada record pada rentang itu, bukan konsumsi nol.')
    add('Energi pada histori adalah counter kumulatif, bukan rata-rata atau energi per interval. Hitung selisih dua counter valid dalam satu rangkaian yang konsisten. Counter menurun/berganti unit harus ditandai sebagai diskontinuitas, bukan konsumsi negatif. LittleFS perangkat menyimpan checkpoint energi, bukan endpoint histori lengkap.')

    # 25
    page('Android: izin dan jaringan HTTP')
    add('Untuk aplikasi native, deklarasikan INTERNET dan ACCESS_NETWORK_STATE. Jalankan HTTP di worker/coroutine, bukan main thread. Pemilihan Wi-Fi manual melalui Pengaturan HP cukup untuk alur panduan; tidak perlu menambahkan fitur scan Wi-Fi otomatis. [A1]')
    code('''<!-- Di dalam elemen manifest -->
<uses-permission
    android:name="android.permission.INTERNET" />
<uses-permission
    android:name="android.permission.ACCESS_NETWORK_STATE" />''')
    sub('Akses jaringan lokal pada Android baru')
    add('Untuk aplikasi target SDK 37 atau lebih tinggi pada Android 17+, deklarasikan <b>android.permission.ACCESS_LOCAL_NETWORK</b>, periksa lalu minta izin runtime sebelum akses LAN. Tangani penolakan dengan petunjuk membuka izin aplikasi. Target SDK 36 atau lebih rendah tidak meminta izin baru ini; Android 16 mempunyai mekanisme uji opt-in tersendiri. [A2]')
    sub('Izinkan HTTP hanya untuk tujuan yang ditetapkan')
    add('Firmware sekarang memakai HTTP, bukan HTTPS. Android target API 28+ memblokir cleartext secara default. Contoh berikut membatasi pengecualian ke dua IP LAN contoh yang telah ditetapkan; sesuaikan dengan alamat instalasi. [A3]')
    code('''<!-- Atribut pada elemen application -->
android:networkSecurityConfig="@xml/network_security_config"

<!-- Isi res/xml/network_security_config.xml -->
<network-security-config>
  <base-config cleartextTrafficPermitted="false" />
  <domain-config cleartextTrafficPermitted="true">
    <domain>192.168.1.25</domain>
    <domain>192.168.1.10</domain>
  </domain-config>
</network-security-config>''')
    add('Daftar XML bersifat statis, bukan otomatis mengikuti input IP pengguna dan bukan subnet wildcard. Jika produk harus menerima alamat LAN dinamis, tentukan kebijakan khusus: HTTP client hanya boleh mengakses origin LAN yang disetujui, jangan mengikuti redirect untuk request berisi kredensial, dan tinjau risikonya sebelum memperluas pengecualian HTTP.')

    # 26
    page('Contoh Kotlin: baca langsung')
    add('Potongan ini membaca pengukuran REST langsung menggunakan API platform. Jalankan fungsi pada worker thread setelah izin jaringan diberikan. Contoh tidak mencakup UI, lifecycle, cookie sesi, maupun kontrol relay; bukan APK siap pakai. Base URL berasal dari alamat LAN yang sudah disetujui dan ID-nya telah diperiksa.')
    code('''import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class Reading(
    val voltageV: Double, val currentA: Double,
    val powerW: Double, val powerFactor: Double,
    val energyWh: Double
)

fun readDirect(baseUrl: String): Reading {
    val url = URL(baseUrl.trimEnd('/') +
        "/api/v1/measurements/allparameters")
    val c = url.openConnection() as HttpURLConnection
    c.connectTimeout = 5000
    c.readTimeout = 5000
    c.instanceFollowRedirects = false
    c.useCaches = false
    try {
        check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        val j = c.inputStream.bufferedReader().use {
            JSONObject(it.readText())
        }
        check(j.optBoolean("has_sample") &&
              j.optBoolean("fresh")) { "Data belum valid" }
        check(j.optString("calibration") == "calibrated") {
            "Perlu pemeriksaan pemasang"
        }
        val e = j.getJSONObject("electrical")
        fun number(key: String): Double = e.getDouble(key)
            .also { check(it.isFinite()) }
        return Reading(number("voltage_v"), number("current_a"),
            number("active_power_w"), number("power_factor"),
            number("energy_wh"))
    } finally {
        c.disconnect()
    }
}''')
    add('Tangkap exception pada pemanggil: tampilkan pesan yang dapat dipahami, jangan mengganti hasil dengan Reading berisi nol. Format kWh hanya pada tampilan dengan energyWh / 1000.0. Saat mode server dipilih, gunakan endpoint, token, dan parser server yang berbeda; jangan memakai fungsi ini tanpa adaptasi.')

    # 27
    page('Android: masalah dan pengujian')
    table(['Gejala / respons','Tindakan aplikasi'],[
        ('Timeout / koneksi ditolak','Periksa Wi-Fi aktif, alamat/port, isolasi jaringan, VPN, dan izin LAN. Tampilkan Terputus; jangan mengirim POST ulang otomatis.'),
        ('Cleartext tidak diizinkan','Developer memperbaiki konfigurasi HTTP Android untuk origin yang tepat; bukan meminta pengguna mematikan keamanan HP.'),
        ('401','Perangkat: login ulang. Server: periksa API token. Bedakan kedua jenis akses.'),
        ('403 csrf_invalid / password_change_required','Periksa pasangan cookie/CSRF; atau arahkan perubahan password awal melalui setup.'),
        ('409 relay_busy / command_pending','Tunggu command berjalan selesai. device_offline berarti cek jalur MQTT sebelum kontrol.'),
        ('429','Rate limit: tunggu, jangan spam. login_temporarily_locked: beri jeda satu menit.'),
        ('404','Periksa endpoint dan device_id. command_not_found dapat berarti ID lama sudah digantikan atau server mulai ulang.'),
        ('503 pada server','server_not_configured: pengelola melengkapi setup. sd_card_unavailable: periksa SD untuk histori.')
    ],[170,cw-170])
    sub('Checklist penerimaan Android')
    for n,text in enumerate([
        'Uji kedua jalur secara terpisah: identitas benar, satuan benar, serta nama tampilan tidak mengubah device_id.',
        'Putus/sambungkan Wi-Fi HP, cabut jaringan server, dan ubah IP uji: data lama ditandai, kontrol tidak mengirim ulang perintah lama.',
        'Uji sesi habis, password salah, izin LAN ditolak, serta login browser yang mengganti sesi. Aplikasi menampilkan langkah pemulihan, bukan terus loading.',
        'Uji ON/OFF dengan beban yang aman, satu per satu. Uji layar diputar, aplikasi ditutup/dibuka, dan double-tap: tidak boleh ada perintah duplikat atau polling ganda.',
        'Bandingkan nilai aplikasi dengan respons API yang sama. Uji null, field hilang, stale, histori kosong, dan energi turun: jangan membuat angka nol atau histori palsu.'
    ],1):step(n,text)

    # 28
    page('Ketentuan integrasi dan referensi')
    sub('Kontrak yang digunakan')
    add('Bab Android ini sesuai pemeriksaan source SmartPlug R3.9.0 dan ServerSmartPlug R3.8.0. Revisi panduan naik menjadi R3.10; firmware tidak berubah pada revisi dokumen ini. Contoh JSON dipersingkat dan nilai contoh bukan pembacaan perangkat. Pengujian APK Android pada unit fisik tetap menjadi bagian uji integrasi pengembang.')
    sub('Keputusan yang wajib diterapkan aplikasi')
    table(['Area','Ketentuan'],[
        ('Satu aplikasi, dua adapter','REST langsung dan REST server berbagi layar, tetapi tidak berbagi sesi, parser, atau skema autentikasi.'),
        ('Kredensial','Sesi sementara di memori; jika token perlu disimpan, gunakan enkripsi dengan kunci yang dilindungi Android Keystore. Jangan taruh rahasia pada log, URL, screenshot, atau konstanta APK. [A4]'),
        ('Koneksi dan retry','Polling hanya saat diperlukan. Rekomendasi retry baca 2, 4, 8, hingga 30 detik; hanya satu request aktif. POST kontrol harus diputuskan ulang setelah status diperiksa.'),
        ('Batas server saat ini','API token memberi akses server, bukan akun/hak akses terpisah tiap pengguna. Belum ada timestamp terpisah untuk freshness snapshot lengkap atau ID command MQTT end-to-end.'),
        ('Energi dan histori','Server saat ini menerima energi sampai 1.000.000 Wh dan memakai float. Jangan menyatakan presisi/rentang melebihi implementasi itu. Histori REST langsung tidak tersedia; checkpoint lokal bukan database histori.')
    ],[147,cw-147])
    sub('Referensi Android resmi')
    reference('[A1] Android Developers - Connect to the network', 'https://developer.android.com/develop/connectivity/network-ops/connecting')
    reference('[A2] Android Developers - Local network permission', 'https://developer.android.com/privacy-and-security/local-network-permission')
    reference('[A3] Android Developers - Network security configuration', 'https://developer.android.com/privacy-and-security/security-config')
    reference('[A4] Android Developers - Android Keystore system', 'https://developer.android.com/privacy-and-security/keystore')
    add('Referensi dapat diketuk. Periksa ulang aturan izin saat target SDK berubah. Pada serah terima, catat ID, mode, alamat LAN, versi, hasil checklist, dan kontak pengelola. Simpan password/token secara privat. Jangan membuka HTTP perangkat/server langsung ke internet; akses jarak jauh memerlukan jalur aman tambahan.')
