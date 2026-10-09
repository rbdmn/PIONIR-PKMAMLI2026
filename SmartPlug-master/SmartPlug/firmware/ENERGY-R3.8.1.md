# Perubahan energi SmartPlug R3.8.1

## Aturan akumulasi

Counter energi BL0940 adalah satu-satunya sumber pertambahan energi. Untuk dua
sampel valid yang berurutan:

`delta_Wh = delta_CF * wattHoursPerCf`

Tidak ada penambahan berdasarkan `daya * waktu` di antara perubahan counter.
Nilai energi karenanya dapat tetap sementara pada beban kecil sampai counter
bertambah. Smoothing hanya berlaku untuk pengukuran sesaat; counter tidak
dirata-ratakan. REST dan MQTT memakai akumulator yang sama.

Counter CF_CNT memiliki lebar 24 bit menurut
[datasheet BL0940](https://www.belling.com.cn/media/file_object/bel_product/BL0940/datasheet/BL0940_V1.1_en.pdf).
Selisih dihitung modulo 2^24. Pertambahan dibatasi oleh jangkauan register daya
bertanda 24 bit, koefisien konversi, durasi antar-sampel, headroom 2x dan dua
count untuk toleransi kuantisasi. Batas ini pemeriksaan konsistensi data,
bukan rating listrik atau fungsi proteksi beban.

## Reset, pemulihan, dan celah data

- Sampel pertama menetapkan baseline tanpa menghitung counter historisnya lagi.
- Rollover yang menghasilkan delta kecil dan masuk akal tetap dihitung.
- Penurunan counter akibat reset yang menghasilkan delta modulo besar ditolak.
  Total tetap dipertahankan; sampel tersebut menjadi baseline baru.
- Lonjakan counter yang tidak masuk akal juga ditolak dan baseline diperbarui.
- Jeda data lebih dari lima detik memulai baseline baru. Energi selama celah
  tersebut tidak diperkirakan; ini dapat menyebabkan pencatatan kurang pada
  periode gangguan. Nilai terakhir tetap dapat diperiksa melalui health API.
- Perubahan koefisien memulai baseline baru tanpa mengubah energi sebelumnya.
- Pemulihan counter tersimpan/server mempertahankan total dan memulai baseline
  baru. NaN, infinity dan nilai pemulihan negatif tidak diterima.
- Akumulasi menggunakan double agar langkah kecil tidak hilang karena pembulatan
  pada total besar. Format file LittleFS lama tetap kompatibel (float + CRC);
  perombakan penyimpanan dan batas 1.000 kWh belum termasuk revisi ini.

Tanpa penanda reset independen, reset yang terjadi persis dekat rollover atau
reset dan kenaikan ulang selama data hilang tidak selalu dapat dibedakan dari
counter normal. Revisi ini mencegah lonjakan besar, bukan mengklaim pemulihan
seluruh energi yang tidak teramati.

## Data lama dan kalibrasi

Revisi ini tidak menghapus atau mengurangi counter tersimpan, termasuk counter
server. Nilai historis yang sudah terlalu besar akibat firmware lama tidak
dapat dikoreksi otomatis tanpa acuan. Koreksi awal unit perlu keputusan QC.

Koefisien energi harus diperoleh dari selisih energi alat acuan dibagi selisih
CF pada interval yang sama. Endpoint kalibrasi engineering lama belum diubah
menjadi workflow QC berbasis sesi; jangan menganggapnya validasi produksi.

## Verifikasi

Uji host menjalankan header C++ yang sama dengan firmware ESP. Sebelum perubahan
algoritma, suite regresi menangkap hitungan ganda dan lonjakan saat reset.
Lihat `ENERGY-TEST-STATUS-R3.8.1.md` untuk hasil akhir, ukuran build dan hash.

Uji alat acuan pada unit fisik masih diperlukan: beban rendah/menengah/tinggi,
perubahan beban, pengamatan energi selama interval cukup panjang, dan pemulihan
setelah gangguan. Tidak ada upload, aktuasi relay atau uji mains dalam revisi ini.
