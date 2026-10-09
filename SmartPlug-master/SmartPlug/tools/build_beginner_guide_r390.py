"""Generate the beginner guide from the R3.9 commissioning workflow.
Vector diagrams and editable/selectable PDF text; no invented device photos.
"""
from pathlib import Path
from xml.sax.saxutils import escape
import json
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, KeepTogether, Flowable
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
REVISION = globals().get('GUIDE_REVISION', '3.9')
ANDROID_GUIDE = globals().get('ANDROID_GUIDE', False)
OUT = ROOT / f'output/pdf/SmartPlug-Panduan-Pengguna-R{REVISION}.pdf'
OUT.parent.mkdir(parents=True, exist_ok=True)
FONT = Path('C:/Windows/Fonts')
pdfmetrics.registerFont(TTFont('Guide', str(FONT/'arial.ttf')))
pdfmetrics.registerFont(TTFont('GuideBold', str(FONT/'arialbd.ttf')))
pdfmetrics.registerFontFamily('Guide', normal='Guide', bold='GuideBold', italic='Guide', boldItalic='GuideBold')
W, H = A4
CW = W - 100
styles = {
 'body': ParagraphStyle('body', fontName='Guide', fontSize=12, leading=18, textColor=colors.black, spaceAfter=8),
 'title': ParagraphStyle('title', fontName='GuideBold', fontSize=23, leading=29, textColor=colors.black, spaceAfter=18),
 'sub': ParagraphStyle('sub', fontName='GuideBold', fontSize=13, leading=18, textColor=colors.black, spaceBefore=12, spaceAfter=7),
 'small': ParagraphStyle('small', fontName='Guide', fontSize=10, leading=14.3, textColor=colors.black, spaceAfter=5),
 'step': ParagraphStyle('step', fontName='Guide', fontSize=12, leading=18, textColor=colors.black, leftIndent=23, firstLineIndent=-23, spaceAfter=11),
 'cell': ParagraphStyle('cell', fontName='Guide', fontSize=11, leading=15.8, textColor=colors.black),
}
story=[]
def p(text, style='body'): return Paragraph(text, styles[style])
def add(text): story.append(p(text))
def sub(text): story.append(p(text,'sub'))
def step(n, text): story.append(p(f'<b>{n:02d}.</b> {text}', 'step'))
def box(title,text):
 t=Table([[p(title,'sub')],[p(text)]],colWidths=[CW-24])
 t.setStyle(TableStyle([('BOX',(0,0),(-1,-1),1,colors.black),('LEFTPADDING',(0,0),(-1,-1),12),('RIGHTPADDING',(0,0),(-1,-1),12),('TOPPADDING',(0,0),(-1,0),0),('BOTTOMPADDING',(0,-1),(-1,-1),10)]))
 story.extend([Spacer(1,6),t,Spacer(1,10)])
def table(headers,rows,widths):
 data=[[p('<b>'+escape(h)+'</b>','cell') for h in headers]]+[[p(v,'cell') for v in row] for row in rows]
 t=Table(data,colWidths=widths,repeatRows=1,hAlign='LEFT')
 t.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('LINEBELOW',(0,0),(-1,0),1.2,colors.black),('LINEBELOW',(0,1),(-1,-1),.35,colors.black),('LEFTPADDING',(0,0),(-1,-1),7),('RIGHTPADDING',(0,0),(-1,-1),8),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),9)]))
 story.extend([t,Spacer(1,8)])
def page(title):
 if story: story.append(PageBreak())
 story.append(p(title,'title'))

class IntegrationDiagram(Flowable):
 def __init__(self): super().__init__(); self.width=CW; self.height=240
 def draw(self):
  c=self.canv;c.setStrokeColor(colors.black);c.setFillColor(colors.black);c.setLineWidth(1.3)
  def node(x,y,w,h,title,detail):
   c.roundRect(x,y,w,h,6,stroke=1,fill=0);c.setFont('GuideBold',12);c.drawCentredString(x+w/2,y+h-23,title);c.setFont('Guide',10);c.drawCentredString(x+w/2,y+19,detail)
  def tip(x,y,dx,dy):
   path=c.beginPath();path.moveTo(x,y);path.lineTo(x-dx*7-dy*3,y-dy*7+dx*3);path.lineTo(x-dx*7+dy*3,y-dy*7-dx*3);path.close();c.drawPath(path,fill=1,stroke=0)
  node(0,148,145,65,'SmartPlug','Pengukuran & relay')
  node(CW-145,148,145,65,'Satu aplikasi','Monitoring & kontrol')
  sx=(CW-160)/2;node(sx,10,160,66,'Server','Broker + penyimpanan')
  c.line(145,180,CW-145,180);tip(145,180,-1,0);tip(CW-145,180,1,0)
  c.setFont('GuideBold',10);c.drawCentredString(CW/2,196,'REST API langsung')
  c.setFont('Guide',9.5);c.drawCentredString(CW/2,161,'Tanpa server')
  c.line(72,148,72,43);c.line(72,43,sx,43);tip(72,148,0,1);tip(sx,43,1,0)
  c.setFont('GuideBold',10);c.drawString(82,105,'MQTT')
  x=CW-72;c.line(sx+160,43,x,43);c.line(x,43,x,148);tip(sx+160,43,-1,0);tip(x,148,0,1)
  c.setFont('GuideBold',10);c.drawRightString(x-10,113,'REST API')
  c.setFont('Guide',10);c.drawRightString(x-10,98,'server')

page('SmartPlug\nPanduan pengguna'.replace('\n','<br/>'))
add('SmartPlug menghubungkan pengukuran energi dan kontrol beban listrik ke aplikasi Anda. Perangkat membaca tegangan, arus, daya, power factor, dan energi, lalu menyediakan data untuk aplikasi melalui REST API atau melalui server MQTT. Aplikasi tetap menjadi tempat monitoring dan kontrol sehari-hari.')
add('Konfigurasi dilakukan dari browser di HP atau laptop melalui Wi-Fi perangkat. Anda tidak perlu memasang perangkat lunak pemrograman. Halaman lokal menyediakan langkah setup, pengaturan koneksi, serta pemeriksaan fungsi sederhana sebelum perangkat digunakan bersama aplikasi.')
sub('Ikuti alur ini pada penggunaan pertama')
for n,t in enumerate([
 'Siapkan produk, kartu akses pemilik, dan Wi-Fi lokasi.',
 'Pindai QR Wi-Fi atau pilih nama jaringan perangkat secara manual.',
 'Buka halaman setup dan masuk sebagai admin.',
 'Ganti password admin awal, lalu sambungkan Wi-Fi lokasi.',
 'Pilih REST API atau isi pengaturan broker MQTT.',
 'Periksa pembacaan dan relay, kemudian gunakan aplikasi.'
],1): step(n,t)
box('Batas penggunaan panduan','Panduan ini untuk produk yang sudah dirakit, tertutup, dan dipasang dengan benar. Pemasangan kelistrikan, pembukaan casing, serta pemeriksaan rangkaian dilakukan oleh teknisi yang kompeten. Uji ON/OFF dapat memutus atau menyambungkan beban; gunakan beban uji yang memang boleh berubah keadaan.')
add('Simpan panduan dan kartu akses di tempat aman. Bila tampilan unit berbeda dari contoh alur di sini, lihat bagian Perangkat lama dan bantuan pada halaman 16.')

page('Daftar isi')
entries=[('Sebelum mulai',3),('Sambungkan HP ke Wi-Fi SmartPlug',4),('Buka halaman setup dan masuk',5),('Amankan akses perangkat',6),('Sambungkan Wi-Fi lokasi',7),('Pilih jalur integrasi aplikasi',8),('Isi pengaturan MQTT',9),('Atur penyimpanan energi lokal',10),('Periksa fungsi perangkat',11),('Gunakan aplikasi sehari-hari',12),('Jika koneksi atau login bermasalah',13),('Pemulihan dan reset konfigurasi',14),('Checklist dan catatan serah terima',15),('Perangkat lama dan bantuan',16)]
if ANDROID_GUIDE: entries.append(('Integrasi aplikasi Android',17))
for title,num in entries:
 t=Table([[p(title),p(str(num))]],colWidths=[CW-42,42])
 t.setStyle(TableStyle([('LINEBELOW',(0,0),(-1,-1),.3,colors.black),('BOTTOMPADDING',(0,0),(-1,-1),10 if ANDROID_GUIDE else 13),('TOPPADDING',(0,0),(-1,-1),7 if ANDROID_GUIDE else 9),('ALIGN',(1,0),(1,0),'RIGHT')]))
 story.append(t)

page('Sebelum mulai')
sub('Yang perlu tersedia')
for n,t in enumerate([
 'SmartPlug yang sudah terpasang dan siap diberi daya sesuai petunjuk produk. Jangan memakai rangkaian terbuka sebagai perangkat pengguna.',
 'HP atau laptop dengan Wi-Fi dan browser. HP dipakai untuk contoh langkah; laptop mengikuti alur yang sama.',
 'Label QR Wi-Fi serta kartu akses pemilik untuk unit Anda. Cocokkan ID pada kartu dengan ID unit.',
 'Nama dan password Wi-Fi lokasi 2,4 GHz. Minta kepada pemilik router jika Anda tidak mengetahui keduanya.',
 'Aplikasi yang akan dipakai. Jika memakai MQTT, pengelola juga harus menyediakan server yang sudah aktif beserta data koneksinya.'
],1):step(n,t)
sub('Tiga password yang berbeda')
table(['Jenis akses','Dipakai untuk'],[
 ('Password Wi-Fi perangkat','Menghubungkan HP ke jaringan SmartPlug. Ada pada label Wi-Fi unit; QR mengisikan data ini secara otomatis.'),
 ('Password admin perangkat','Masuk ke halaman setup dan mengubah pengaturan. Ada pada kartu akses pemilik, lalu diganti saat setup pertama.'),
 ('Password Wi-Fi lokasi','Menghubungkan SmartPlug ke router rumah atau kantor. Ini password router, bukan password admin SmartPlug.')
],[160,CW-160])
add('Pada mode MQTT ada kredensial tambahan: username dan password broker. Data itu berasal dari pengelola server, bukan dari label Wi-Fi SmartPlug.')
box('Jangan menebak kredensial','Jika kartu akses atau label hilang, hubungi penyedia unit. Jangan memasukkan password router ke kolom password admin. Jangan mengirimkan foto kartu admin ke grup umum.')

page('Sambungkan HP ke Wi-Fi SmartPlug')
sub('Cara A - menggunakan QR Wi-Fi')
step(1,'Nyalakan SmartPlug dan tunggu sekitar 15 detik. Dekatkan HP ke perangkat. Waktu ini adalah waktu tunggu praktis; indikator pada halaman tetap menjadi acuan keberhasilan.')
step(2,'Buka kamera atau pemindai QR pada HP. Arahkan ke <b>QR Wi-Fi</b> di label unit sampai muncul pilihan untuk bergabung ke jaringan.')
step(3,'Ketuk <b>Gabung</b> atau <b>Hubungkan</b>. Nama pilihan dapat berbeda pada tiap HP. QR ini menghubungkan Wi-Fi, bukan menggantikan login admin.')
step(4,'Jika HP menampilkan <b>Tidak ada internet</b>, pilih tetap terhubung. Jaringan SmartPlug dipakai untuk membuka halaman lokal; internet tidak diperlukan untuk tahap ini.')
sub('Cara B - pilih Wi-Fi secara manual')
step(1,'Buka Pengaturan HP, kemudian bagian Wi-Fi. Pada laptop, buka daftar jaringan Wi-Fi.')
step(2,'Pilih nama yang persis sama dengan label unit. Untuk unit berlabel, bentuk namanya <b>SmartPlug-</b> diikuti identitas unit.')
step(3,'Masukkan password Wi-Fi dari label. Huruf besar dan kecil harus sama. Tunggu sampai HP menyatakan tersambung.')
box('Tanda langkah ini berhasil','Nama jaringan yang aktif pada pengaturan Wi-Fi HP sama dengan label SmartPlug. Pesan tanpa internet tidak berarti setup gagal. Lanjutkan membuka browser; halaman setup tidak selalu terbuka otomatis.')
add('Jika QR tidak terbaca, pakai cara manual. Jika nama/password AP pernah diganti, gunakan data pengganti yang dicatat saat perubahan; QR awal tidak ikut berubah.')

page('Buka halaman setup dan masuk')
step(1,'Pastikan HP masih tersambung ke Wi-Fi SmartPlug, bukan kembali ke Wi-Fi lokasi atau jaringan lain.')
step(2,'Buka browser. Ketuk <b>bilah alamat</b> di bagian atas, bukan kotak pencarian pada halaman mesin pencari.')
box('Alamat saat tersambung ke AP SmartPlug','Ketik <b>http://192.168.4.1</b> lalu tekan Buka atau Enter. Tulis awalan <b>http://</b>, bukan https://. Alamat ini digunakan untuk setup lokal saat HP terhubung ke Wi-Fi perangkat.')
step(3,'Pastikan judul halaman adalah <b>SmartPlug</b> dengan keterangan <b>Konfigurasi &amp; pemeriksaan perangkat</b>. Bagian atas menampilkan identitas dan versi perangkat.')
step(4,'Pada bagian <b>1. Masuk sebagai admin</b>, isi password admin dari kartu akses pemilik. Jika sudah diganti sebelumnya, pakai password admin yang baru.')
step(5,'Tekan <b>Masuk</b>. Tunggu pesan <b>Berhasil masuk.</b> Form pengaturan akses dapat dipakai setelah login berhasil.')
sub('Mengapa kolom lain masih terkunci?')
add('Pada akses pertama, firmware meminta Anda mengganti password admin awal. Itu sebabnya pengaturan Wi-Fi, integrasi, penyimpanan, dan uji relay belum dapat dipakai. Ikuti halaman berikutnya terlebih dahulu.')
sub('Jika browser menampilkan "Tidak aman"')
add('Halaman lokal memakai HTTP dan tidak mengenkripsi lalu lintas. Pastikan Anda membuka alamat perangkat sendiri dari jaringan pribadi yang dipercaya. Jangan mengaktifkan penerusan port dari internet ke perangkat. Jangan memakai password yang sama dengan akun penting lain.')
add('Lima login gagal berurutan menyebabkan penundaan sementara sekitar satu menit. Berhenti menebak password, periksa kartu akses, lalu coba kembali setelah waktu tunggu.')

page('Amankan akses perangkat')
step(1,'Buka bagian <b>2. Amankan akses perangkat</b>. Bagian ini terbuka otomatis jika password admin awal belum diganti.')
step(2,'Biarkan <b>Nama Wi-Fi perangkat (AP)</b> sesuai label jika tidak perlu diubah. Biarkan <b>Password Wi-Fi perangkat baru</b> kosong untuk mempertahankan password AP yang ada.')
step(3,'Isi <b>Password admin baru</b> dengan 12-63 karakter tanpa spasi. Gunakan gabungan karakter yang sulit ditebak, berbeda dari password Wi-Fi dan berbeda dari password admin lama.')
step(4,'Masukkan password yang sama pada <b>Ulangi password admin baru</b>. Catat password baru secara aman. Jangan menuliskannya pada label yang dapat dilihat orang umum.')
step(5,'Tekan <b>Simpan akses dan mulai ulang</b>. Baca konfirmasi, lalu lanjutkan bila akses baru sudah dicatat.')
step(6,'Setelah muncul pesan tersimpan, perangkat mulai ulang. Tunggu sekitar 15 detik. Periksa kembali koneksi Wi-Fi HP, lalu buka ulang halaman setup.')
step(7,'Masuk memakai password admin baru. Pengaturan berikutnya dapat digunakan setelah halaman membaca status perangkat.')
box('Jika Anda juga mengganti AP','Nama AP maksimum 32 karakter. Password AP harus 8-63 karakter tanpa spasi. Setelah perangkat mulai ulang, pilih nama/password AP yang baru pada HP. QR lama tetap berisi data lama dan tidak akan otomatis diperbarui. Minta label baru kepada pengelola bila diperlukan.')
sub('Yang tetap dan yang berubah')
add('Mengganti password admin saja tidak mengubah Wi-Fi AP atau QR Wi-Fi. Mengganti nama/password AP tidak mengubah identitas perangkat. Ketiga hal ini berbeda, sehingga aplikasi tetap dapat mengenali ID perangkat yang sama.')

page('Sambungkan Wi-Fi lokasi')
add('Pada tahap ini SmartPlug bergabung ke router rumah atau kantor. HP dapat tetap tersambung ke AP SmartPlug selama Anda mengisi pengaturan.')
step(1,'Cari bagian <b>3. Sambungkan Wi-Fi lokasi</b>. Jika tombol belum aktif, masuk sebagai admin dan selesaikan penggantian password awal.')
step(2,'Isi <b>Nama Wi-Fi lokasi (SSID)</b> persis seperti nama jaringan 2,4 GHz yang diberikan pemilik router. Jaringan 5 GHz saja tidak dapat digunakan oleh perangkat ini.')
step(3,'Isi <b>Password Wi-Fi lokasi</b>. Password 8-63 karakter; kosong hanya jika router memang memakai jaringan terbuka. Hindari jaringan terbuka untuk penggunaan produk.')
step(4,'Tekan <b>Simpan dan hubungkan</b>. Password di kolom akan dikosongkan setelah tersimpan; ini bukan berarti password yang disimpan terhapus.')
step(5,'Tunggu dan baca kotak status pada bagian yang sama. <b>Pengaturan tersimpan</b> belum berarti koneksi sudah berhasil. Tanda berhasil adalah <b>Tersambung:</b> diikuti nama Wi-Fi dan alamat perangkat.')
step(6,'Catat <b>Alamat perangkat</b> yang muncul. Aplikasi REST langsung memakai alamat pada jaringan lokasi ini, bukan alamat AP ketika HP sudah pindah ke router.')
box('Contoh - bukan nilai yang harus disalin','Nama Wi-Fi: WiFi-Rumah<br/>Alamat perangkat setelah tersambung: 192.168.1.25<br/>Alamat pada unit Anda dapat berbeda. Gunakan yang benar-benar ditampilkan oleh halaman.')
sub('Jika status terus mencoba tersambung')
add('Periksa nama Wi-Fi, huruf besar/kecil password, dan jarak ke router. Pastikan jaringan tidak membutuhkan login halaman hotel/kantor. Saat menyimpan ulang, isi ulang password Wi-Fi. Jika status tetap tidak berubah setelah sekitar satu menit, perbaiki isian atau minta bantuan pengelola jaringan.')

page('Pilih jalur integrasi aplikasi')
add('Satu aplikasi cukup. Yang berbeda adalah jalur koneksi di belakangnya. Pilih mode sesuai sistem yang dipasang pengelola; Anda tidak perlu menjalankan dua aplikasi.')
story.append(IntegrationDiagram())
sub('REST API - tanpa server perantara')
add('Pilih <b>REST API</b> pada bagian <b>4. Pilih jalur aplikasi</b>, lalu tekan <b>Simpan mode integrasi</b>. Aplikasi berkomunikasi langsung dengan SmartPlug melalui jaringan lokasi. Pengelola aplikasi memerlukan alamat perangkat dan identitas unit.')
sub('MQTT - melalui server')
add('Pilih <b>MQTT</b> jika tersedia server yang menjalankan broker. SmartPlug bertukar data dan perintah dengan server melalui MQTT; aplikasi berkomunikasi dengan REST API server. Pengaturan broker dijelaskan pada halaman berikutnya.')
box('Sebelum melanjutkan','Memilih mode tidak memasang aplikasi atau membuat server otomatis. Jika Anda belum menerima aplikasi atau data server, minta kepada penyedia sistem. Jangan mengisi alamat perkiraan. Pada profil khusus REST, pilihan MQTT memang tidak tersedia.')
add('Halaman lokal tetap dapat dipakai untuk setup dan QC. Pergantian mode hanya memilih jalur integrasi operasional; halaman lokal bukan pengganti aplikasi harian.')

page('Isi pengaturan MQTT')
step(1,'Pastikan bagian Wi-Fi lokasi sudah berstatus <b>Tersambung</b>. Pastikan server broker aktif dan berada pada jaringan yang dapat dijangkau SmartPlug.')
step(2,'Pilih <b>MQTT</b> di bagian <b>4. Pilih jalur aplikasi</b>. Kolom broker akan muncul.')
table(['Kolom','Cara mengisinya'],[
 ('Alamat broker','Isi host atau IP yang diberikan pengelola. Contoh bentuk IP: 192.168.1.10. Jangan menambahkan http://, nomor port, atau /halaman.'),
 ('Port broker','Isi nomor port dari pengelola. Nilai awal 1883 untuk MQTT biasa. Port 8883 tidak otomatis mengaktifkan enkripsi pada firmware ini.'),
 ('Username broker','Isi akun yang disediakan untuk perangkat. Jangan memakai username admin halaman setup kecuali pengelola memang menentukan demikian.'),
 ('Password broker','Isi password akun broker saat konfigurasi pertama. Saat mengedit konfigurasi yang sudah tersimpan, kolom kosong mempertahankan password lama.'),
 ('Base topic','Gunakan nilai otomatis smartplug/SP- diikuti MAC lengkap unit, kecuali pengelola server memberikan nilai lain. Jangan menambahkan # atau +.')
],[132,CW-132])
step(3,'Tekan <b>Simpan mode integrasi</b>. Tunggu sampai kotak status menampilkan <b>MQTT terhubung ke broker.</b> Jika belum, periksa semua isian bersama pengelola.')
step(4,'Buka aplikasi yang disediakan. Minta pengelola memastikan unit dengan ID yang sama terdaftar, datanya diperbarui, dan perintah ON/OFF mendapat hasil yang benar.')
box('Jaringan pribadi saja','Koneksi MQTT perangkat ini memakai TCP tanpa TLS. Gunakan broker pada jaringan pribadi yang dikendalikan pengelola. Jangan memasukkan kredensial broker publik atau membuka akses dari internet tanpa rancangan keamanan tambahan.')

page('Atur penyimpanan energi lokal')
add('Bagian <b>5. Penyimpanan energi lokal</b> mengatur apakah total energi dipertahankan di memori internal. Pengaturan awal nonaktif dan pilihannya diingat setelah perangkat mulai ulang. Penyimpanan ini bukan database riwayat tegangan, arus, atau grafik.')
sub('Mengaktifkan penyimpanan')
step(1,'Buka bagian penyimpanan. Pilih <b>Aktif</b>, lalu tekan <b>Simpan dan mulai ulang</b>.')
step(2,'Tunggu perangkat kembali, buka ulang halaman, lalu masuk sebagai admin. Periksa status penyimpanan.')
step(3,'Jika status <b>Penyimpanan siap</b>, tidak perlu menekan tombol lain. Total energi disimpan berkala setiap lima menit bila nilainya berubah.')
step(4,'Jika status <b>Penyimpanan belum siap</b>, baca penjelasannya. Tombol <b>Siapkan penyimpanan</b> melakukan format dan menghapus seluruh record lokal. Gunakan hanya bila Anda mengizinkan penghapusan data lama; jika ragu, hentikan dan hubungi penyedia.')
step(5,'Setelah menyetujui konfirmasi, tunggu status siap. Proses ini tidak mengubah total energi yang sedang berjalan di RAM, tetapi menghapus record yang sebelumnya ada di LittleFS.')
sub('Menonaktifkan penyimpanan')
add('Pilih <b>Nonaktif</b>, lalu simpan. Perangkat mulai ulang dan tidak lagi membaca/menulis LittleFS untuk energi. Record lama tidak dihapus; jika fitur diaktifkan kembali, record lama yang masih valid dapat dipakai kembali.')
box('Apa yang terjadi saat listrik terputus?','Pada REST, pemulihan memakai simpanan lokal terakhir jika fitur aktif. Energi setelah simpan terakhir dapat hilang. Pada MQTT, total valid yang lebih besar dari server dapat menaikkan kembali counter perangkat. Data yang tidak sempat disimpan atau diterima server tidak dapat dijamin pulih.')
add('Jika muncul Simpan energi gagal atau peringatan record, jangan terus memformat. Catat pesan, versi, serta ID unit dan hubungi penyedia.')

page('Periksa fungsi perangkat')
add('Lakukan pemeriksaan ini setelah setup, bersama pemasang bila Anda belum terbiasa dengan peralatan listrik. Gunakan beban uji yang boleh dinyalakan dan dimatikan. Jangan memakai alat penunjang keselamatan, beban kritis, atau alat yang dapat membahayakan saat menyala otomatis.')
step(1,'Buka bagian <b>6. Pemeriksaan fungsi (QC)</b>. Pastikan muncul <b>Pembacaan sensor aktif.</b> Tanda garis pada nilai berarti data belum tersedia/terhenti, bukan nol yang sudah terukur.')
step(2,'Amati tegangan. Amati arus, daya, power factor, dan total energi. Besarnya mengikuti sumber serta beban. Nilai mendekati nol dapat normal untuk arus/daya saat tidak ada konsumsi.')
step(3,'Periksa bahwa beban uji dan area sekitar aman. Tekan <b>Uji ON</b>, baca konfirmasi, lalu setujui. Tunggu sampai status menunjukkan ON dan periksa respons beban.')
step(4,'Saat relay ON, tombol ON tidak dapat ditekan dan tombol OFF tersedia. Setelah jeda setidaknya satu detik, tekan <b>Uji OFF</b>. Pastikan status berubah menjadi OFF dan beban merespons.')
step(5,'Pastikan hanya tombol kebalikan dari status terakhir yang aktif. Saat status belum diketahui, kedua tombol dapat tersedia; saat sedang memproses, tunggu sampai selesai.')
step(6,'Jika ON/OFF terbalik, beban tidak merespons, muncul bau/panas tidak normal, atau data tidak masuk akal, hentikan pengujian. Minta teknisi memeriksa unit; jangan memperbaiki rangkaian melalui coba-coba tombol.')
box('Makna status relay','ON/OFF menyatakan pulsa perintah telah selesai. Perangkat tidak memiliki pembacaan kontak fisik yang menjamin jalur beban benar-benar terbuka/tertutup. Arus nol juga tidak membuktikan beban terisolasi. Untuk pekerjaan kelistrikan, putuskan sumber daya melalui cara yang ditetapkan pemasang.')
add('Saat boot, firmware memakai tiga pembacaan arus valid berurutan untuk menentukan pulsa awal ON atau OFF. Karena perilaku awal ini dapat memengaruhi beban, jangan melakukan restart pada beban yang harus terus menyala.')

page('Gunakan aplikasi sehari-hari')
step(1,'Setelah QC selesai, keluar dari akun admin halaman setup dengan menekan <b>Keluar</b>.')
step(2,'Pindahkan HP dari Wi-Fi SmartPlug ke Wi-Fi lokasi yang dipakai aplikasi/server. Jangan menghapus catatan AP; AP tetap diperlukan bila Anda ingin mengubah konfigurasi.')
step(3,'Buka aplikasi yang disediakan pengelola. Gunakan identitas unit yang ditampilkan di halaman setup untuk memastikan perangkat yang dipilih benar.')
step(4,'Periksa bahwa data aplikasi bertambah baru, bukan hanya menampilkan nilai tersimpan. Coba perintah relay sekali dengan beban uji yang aman. Tunggu hasilnya sebelum mengirim perintah berikutnya.')
sub('Membaca parameter')
table(['Parameter','Arti singkat'],[
 ('Tegangan (V)','Besarnya tegangan yang dibaca perangkat.'),
 ('Arus (A)','Arus yang mengalir pada beban.'),
 ('Daya aktif (W)','Daya yang sedang digunakan beban.'),
 ('Power factor','Perbandingan daya aktif dengan daya semu; tidak memiliki satuan.'),
 ('Energi (Wh / kWh)','Akumulasi pemakaian. 1 kWh = 1.000 Wh. Ini bukan nilai daya sesaat.')
],[140,CW-140])
add('Pembacaan memakai smoothing sehingga perubahan nilai dapat tampak bertahap. Jangan memakai halaman QC untuk menetapkan akurasi meter; verifikasi akurasi memerlukan pengujian pemasang dengan alat referensi yang sesuai.')
sub('Riwayat dan pemulihan energi')
add('Dalam sistem MQTT, penyimpanan riwayat menjadi tugas server. Pada perangkat, LittleFS hanya menyimpan total energi terakhir. Nilai server yang lebih kecil tidak menurunkan counter perangkat. Saat koneksi terputus, jangan menganggap histori yang tidak terkirim akan dibuat ulang secara lengkap.')

page('Jika koneksi atau login bermasalah')
table(['Yang terlihat','Langkah yang dilakukan'],[
 ('Nama Wi-Fi SmartPlug tidak muncul','Pastikan perangkat mendapat daya dan dekatkan HP. Tunggu lalu buka ulang daftar Wi-Fi. Periksa apakah nama AP pernah diganti. Jangan mereset sebelum memeriksa catatan akses.'),
 ('QR tidak terbaca atau gagal bergabung','Pilih jaringan secara manual dan gunakan password label. Jika AP sudah diganti, gunakan catatan baru, bukan QR lama.'),
 ('Tersambung, tetapi tanpa internet','Tetap pada Wi-Fi SmartPlug. Buka alamat lokal melalui bilah alamat browser. Internet tidak dibutuhkan untuk setup AP.'),
 ('Halaman tidak terbuka / jawaban kosong','Periksa jaringan aktif, ketik http://192.168.4.1 secara lengkap, lalu muat ulang. Matikan sementara VPN atau fitur pindah otomatis jaringan bila mengganggu; aktifkan kembali sesudah setup.'),
 ('Laptop memiliki dua Wi-Fi','Pastikan adapter yang terhubung ke SmartPlug mendapat alamat 192.168.4.x. Jika membingungkan, coba memakai HP terlebih dahulu. Jangan memasang alamat statis sembarangan.'),
 ('Alamat laptop dimulai 169.254','Laptop belum mendapat alamat dari perangkat. Putuskan koneksi Wi-Fi SmartPlug lalu hubungkan kembali. Hindari reset berulang. Jika tetap terjadi, hubungi penyedia.'),
 ('Password admin tidak cocok','Periksa apakah memakai password admin, bukan password Wi-Fi. Pakai password baru jika sudah diganti. Setelah lima gagal, tunggu satu menit.'),
 ('Tombol pengaturan tidak aktif','Masuk sebagai admin. Bila masih terkunci, selesaikan penggantian password admin awal. Sesi yang berakhir perlu login ulang.'),
 ('Wi-Fi lokasi / MQTT belum terhubung','Periksa status pada langkah 3 dan 4. Pastikan router 2,4 GHz, server aktif, dan data akun benar. Tersimpan tidak sama dengan tersambung.')
],[146,CW-146])

page('Pemulihan dan reset konfigurasi')
add('Reset bukan langkah pertama untuk setiap masalah. Coba periksa koneksi dan password terlebih dahulu. Lakukan reset jika konfigurasi memang perlu dikembalikan dan kartu akses awal unit masih tersedia.')
sub('Jika masih dapat masuk sebagai admin')
step(1,'Buka halaman lokal dan masuk. Selesaikan penggantian password awal bila halaman mewajibkannya.')
step(2,'Buka <b>Pemulihan konfigurasi</b>. Baca dampaknya, lalu tekan <b>Reset konfigurasi perangkat</b>.')
step(3,'Setujui konfirmasi hanya setelah memastikan beban boleh terganggu oleh restart dan data akses awal tersedia.')
step(4,'Tunggu perangkat mulai ulang. Sambungkan HP memakai nama/password AP awal unit. Masuk memakai password admin awal, lalu ulangi setup dan penggantian password.')
sub('Jika password admin lupa')
add('Jika produk memiliki tombol konfigurasi yang dapat diakses dari luar casing, nyalakan unit terlebih dahulu, tunggu boot selesai, lalu tahan tombol tersebut selama 10 detik. Lepaskan setelah perangkat mereset/mulai ulang. Jangan menahan tombol saat pertama kali memasang daya. Bila tombol tidak tersedia dari luar, hubungi penyedia; jangan membuka casing.')
table(['Dikembalikan / dihapus','Tetap ada'],[
 ('Wi-Fi lokasi dan akun broker. Mode kembali REST. Nama/password AP serta admin kembali ke akses awal firmware unit. Penyimpanan energi kembali nonaktif.','ID unit tidak berubah. Record energi lokal tidak otomatis dihapus. Salinan data pada server tidak dihapus oleh reset perangkat.')
],[CW/2,CW/2])
box('Bukan prosedur pindah pemilik','Reset konfigurasi bukan penghapusan seluruh data. Untuk penjualan ulang atau perpindahan pemilik, minta penyedia menghapus data perangkat dan server serta menyiapkan ulang kredensial/label. Jangan menyerahkan kartu akses lama tanpa proses tersebut.')

page('Checklist dan catatan serah terima')
add('Isi bersama pemasang setelah langkah setup dan pemeriksaan selesai. Jangan mengisi password pada lembar ini; simpan password pada kartu akses pribadi atau pengelola password yang Anda gunakan.')
table(['Pemeriksaan','Hasil'],[
 ('ID unit pada perangkat, label, dan aplikasi sama.','Lulus / Periksa'),
 ('QR atau password AP berhasil dipakai oleh HP pemilik.','Lulus / Periksa'),
 ('Password admin awal sudah diganti dan dicatat aman.','Lulus / Periksa'),
 ('Wi-Fi lokasi berstatus tersambung.','Lulus / Periksa'),
 ('Mode aplikasi benar; broker tersambung jika memakai MQTT.','Lulus / Periksa'),
 ('Pembacaan sensor aktif; respons beban ON/OFF benar.','Lulus / Periksa'),
 ('Pilihan penyimpanan lokal dan konsekuensinya dipahami.','Lulus / Periksa'),
 ('Pemilik tahu cara membuka setup dan meminta bantuan.','Lulus / Periksa')
],[CW-112,112])
sub('Catatan unit - tanpa password')
table(['Informasi','Catatan'],[
 ('ID perangkat','................................................................'),
 ('Nama/lokasi pemakaian','................................................................'),
 ('Nama Wi-Fi lokasi','................................................................'),
 ('Alamat perangkat di LAN','................................................................'),
 ('Mode / alamat server','................................................................'),
 ('Penyimpanan lokal','Aktif / Nonaktif'),
 ('Kontak penyedia / pemasang','................................................................')
],[167,CW-167])

page('Perangkat lama dan bantuan')
sub('Jika unit belum memakai label unik')
add('Unit lama dengan konfigurasi bawaan bersama dapat memakai nama Wi-Fi <b>SmartPlug-Setup</b>, password Wi-Fi <b>SmartPlug123</b>, dan password admin awal <b>SmartPlug123</b>. Data ini hanya untuk unit lama yang belum pernah diubah, bukan kredensial universal untuk unit berlabel baru.')
add('Jika aksesnya sudah diganti, gunakan catatan pemilik. Setelah firmware baru dipasang oleh penyedia, password admin bawaan bersama harus diganti sebelum pengaturan lain dapat digunakan. Ganti juga password AP bersama. Unit dengan kredensial bersama tidak boleh dianggap selesai dipersiapkan untuk penyerahan produk.')
sub('Jika tampilan menu berbeda')
add('Panduan mengikuti halaman setup revisi R3.9. Nomor firmware ada di kanan atas halaman. Jika masih ada grafik lama, tidak ada langkah setup bernomor, atau tidak ada penyimpanan energi lokal, catat versi dan minta penyedia memeriksa firmware unit. Membuka PDF ini tidak memperbarui perangkat.')
sub('Informasi yang dikirim saat meminta bantuan')
for n,t in enumerate([
 'ID perangkat dan versi yang tampil pada halaman.',
 'Pesan kesalahan persis seperti yang terlihat, serta langkah terakhir sebelum masalah terjadi.',
 'Apakah HP terhubung ke AP SmartPlug atau Wi-Fi lokasi, dan apakah mode yang dipilih REST atau MQTT.',
 'Foto halaman dengan password, QR, dan data pribadi ditutup. Jangan mengirim password admin, password Wi-Fi, atau password broker.'
],1):step(n,t)
sub('Istilah yang sering muncul')
add('<b>AP:</b> Wi-Fi yang dipancarkan perangkat untuk setup. <b>SSID:</b> nama jaringan Wi-Fi. <b>LAN:</b> jaringan lokal rumah/kantor. <b>Broker:</b> layanan penghubung pesan MQTT. <b>QC:</b> pemeriksaan fungsi sebelum pemakaian. <b>LittleFS:</b> penyimpanan lokal perangkat, jika diaktifkan.')
box('Simpan bersama unit','Simpan panduan, kartu akses pemilik, dan kontak penyedia. Aplikasi serta server mengikuti panduan pengelola sistem masing-masing; nama menu aplikasi tidak disamakan secara paksa dengan halaman setup SmartPlug.')

if ANDROID_GUIDE:
 while story and isinstance(story[-1], Spacer): story.pop()
 from android_guide_sections import append_android
 append_android(globals())

def footer(canvas,doc):
 canvas.saveState();canvas.setStrokeColor(colors.black);canvas.setFillColor(colors.black)
 canvas.setLineWidth(.6);canvas.line(50,43,W-50,43);canvas.setFont('Guide',9)
 canvas.drawString(50,28,f'SmartPlug | Panduan pengguna | Revisi {REVISION}')
 canvas.drawRightString(W-50,28,str(doc.page));canvas.restoreState()
doc=SimpleDocTemplate(str(OUT),pagesize=A4,leftMargin=50,rightMargin=50,topMargin=45,bottomMargin=59,title='SmartPlug - Panduan pengguna',author='SmartPlug',subject='Konfigurasi AP, integrasi aplikasi, dan pemeriksaan fungsi')
while story and isinstance(story[-1], Spacer): story.pop()
doc.build(story,onFirstPage=footer,onLaterPages=footer)
reader=PdfReader(str(OUT))
assert len(reader.pages)==(28 if ANDROID_GUIDE else 16), f'Unexpected pagination: {len(reader.pages)}'
for title,num in entries:
 assert title in reader.pages[num-1].extract_text(),(title,num)
if ANDROID_GUIDE:
 android_titles = ['Integrasi aplikasi Android','Android: koneksi langsung',
  'Android: melalui server MQTT','Developer: akses REST langsung',
  'Developer: baca pengukuran','Developer: kontrol relay langsung',
  'Developer: baca melalui server','Server: perintah dan histori',
  'Android: izin dan jaringan HTTP','Contoh Kotlin: baca langsung',
  'Android: masalah dan pengujian','Ketentuan integrasi dan referensi']
 for num,title in enumerate(android_titles,17):
  assert title in reader.pages[num-1].extract_text(), (title,num)
text='\n'.join(page.extract_text() for page in reader.pages)
assert 'serial monitor' not in text.lower()
assert 'RAW' not in text
print(json.dumps({'output':str(OUT),'pages':len(reader.pages),'toc_checked':True,'text_characters':len(text)}))
