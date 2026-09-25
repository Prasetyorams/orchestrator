/**
 * Kamus bahasa Jawa (ngoko).
 *
 * Hanya teks antarmuka. Pesan dari server tetap tampil dalam bahasa Indonesia
 * — sama seperti teks yang belum ada di kamus mana pun — karena bahasa Jawa
 * di sini bahasa tambahan, bukan bahasa yang dijanjikan penuh.
 */
export const JV: Record<string, string> = {
  // navigasi
  Beranda: "Ngarep",
  Pemantauan: "Ngawasi",
  Pekerjaan: "Pagawéan",
  Catatan: "Cathetan",
  Pemicu: "Pamicu",
  Otomasi: "Otomatisasi",
  Proses: "Proses",
  Paket: "Pakèt",
  Antrean: "Antrèan",
  Aset: "Asèt",
  Kredensial: "Kredensial",
  Robot: "Robot",
  Mesin: "Mesin",
  Lingkungan: "Lingkungan",
  Gudang: "Gudhang",
  Penyewa: "Panyéwa",
  Pengguna: "Pangguna",
  Peran: "Peran",
  Lisensi: "Lisènsi",
  Setelan: "Setelan",

  // bentuk tunggal untuk judul kolom dan isian
  "Robot|satu": "Robot",
  "Proses|satu": "Proses",
  "Mesin|satu": "Mesin",
  "Lingkungan|satu": "Lingkungan",
  "Paket|satu": "Pakèt",

  // bilah atas
  "Cari proses, robot, antrean...": "Golèk proses, robot, antrèan...",

  // dasbor
  "Robot Aktif": "Robot urip",
  "Pekerjaan Berjalan": "Pagawéan mlaku",
  "Tingkat Keberhasilan": "Tingkat kasil",
  "Sedang Berjalan": "Lagi mlaku",
  "Pemicu Berikutnya": "Pamicu sabanjuré",
  "Peringatan Terbaru": "Pènget anyar",
  "Ringkasan Antrean": "Ringkesan antrèan",
  "Tidak bisa mengambil data dasbor.": "Ora bisa njupuk data dasbor.",
  Harian: "Saben dina",
  Mingguan: "Saben minggu",
  Bulanan: "Saben sasi",
  Tahunan: "Saben taun",
  "Rentang waktu: {0}": "Rentang wektu: {0}",
  "Hari ini": "Dina iki",
  "Sejak {0}": "Wiwit {0}",
  "Saat ini": "Saiki",
  "Keadaan saat ini, tidak bergantung pada rentang waktu.": "Kahanan saiki, ora gumantung rentang wektu.",
  "Riwayat pekerjaan": "Riwayat pagawéan",
  "{0} terdaftar · {1} terputus": "{0} kadhaftar · {1} pedhot",
  "{0} menunggu": "{0} ngentèni",
  "{0} pekerjaan hari ini": "{0} pagawéan dina iki",
  "{0} pekerjaan minggu ini": "{0} pagawéan minggu iki",
  "{0} pekerjaan bulan ini": "{0} pagawéan sasi iki",
  "{0} pekerjaan tahun ini": "{0} pagawéan taun iki",
  "Riwayat per jam, hari ini": "Riwayat saben jam, dina iki",
  "Riwayat per hari, minggu ini": "Riwayat saben dina, minggu iki",
  "Riwayat per hari, bulan ini": "Riwayat saben dina, sasi iki",
  "Riwayat per bulan, tahun ini": "Riwayat saben sasi, taun iki",
  "{0}: {1} berhasil, {2} gagal": "{0}: {1} kasil, {2} gagal",
  "Belum ada peringatan.": "Durung ana pènget.",
  "Tidak ada peringatan pada tingkat ini.": "Ora ana pènget ing tingkat iki.",
  "tiap {0} menit": "saben {0} menit",

  // kolom
  Nama: "Jeneng",
  Status: "Kahanan",
  Keadaan: "Kahanan",
  Prioritas: "Prioritas",
  Sumber: "Sumber",
  Kemajuan: "Kemajuan",
  Dibuat: "Digawé",
  Dimulai: "Diwiwiti",
  Selesai: "Rampung",
  Keterangan: "Katrangan",
  Versi: "Vèrsi",
  Ukuran: "Ukuran",
  Diterbitkan: "Diterbitaké",
  Berkas: "Berkas",
  Tingkat: "Tingkat",
  Pesan: "Pesen",
  Waktu: "Wektu",
  Jadwal: "Jadwal",
  "Jalan Berikutnya": "Mlaku sabanjuré",
  "Jalan Terakhir": "Mlaku pungkasan",
  "Zona Waktu": "Zona wektu",
  Percobaan: "Nyoba",
  Rujukan: "Rujukan",
  Tipe: "Jinis",
  Denyut: "Denyut",
  Memori: "Mèmori",
  Nilai: "Isi",
  Diubah: "Diowahi",
  Baru: "Anyar",
  Diproses: "Diproses",
  Berhasil: "Kasil",
  Gagal: "Gagal",
  Total: "Gunggung",
  Isi: "Isi",
  Galat: "Kaluputan",
  Info: "Info",
  Diunggah: "Diunggah",
  Oleh: "Déning",
  Kode: "Kodhe",
  "Masuk terakhir": "Mlebu pungkasan",
  Produk: "Prodhuk",
  Terpakai: "Kanggo",
  "Berlaku sampai": "Laku nganti",
  Izin: "Idin",

  // tombol dan aksi
  Muat: "Muat manèh",
  "Muat ulang": "Muat manèh",
  Jalankan: "Lakokna",
  Hentikan: "Mandhegna",
  Hapus: "Busak",
  Simpan: "Simpen",
  Batal: "Batal",
  Tutup: "Tutup",
  Tambah: "Tambah",
  Sunting: "Sunting",
  Unduh: "Undhuh",
  Unggah: "Unggah",
  Cari: "Golèk",
  Detail: "Rincian",
  Nyalakan: "Uripna",
  Matikan: "Patènana",
  Keluar: "Metu",
  Masuk: "Mlebu",
  "Tandai semua dibaca": "Tandhani kabèh wis diwaca",
  Tampilkan: "Tampilaké",
  Sembunyikan: "Singidaké",

  // pesan umum
  "Belum ada data.": "Durung ana data.",
  "Memuat...": "Ngemot...",
  "Tidak ada yang cocok.": "Ora ana sing cocog.",
  "Yakin menghapus": "Busak",
  Halaman: "Kaca",
  dari: "saka",
  dipilih: "dipilih",
  baris: "baris",
  "Nama pengguna": "Jeneng pangguna",
  "Kata sandi": "Tembung sandi",
  Bahasa: "Basa",
  "Pilih semua di halaman ini": "Pilih kabèh ing kaca iki",
  "Pilih baris": "Pilih baris",
  "Terjadi kesalahan.": "Ana kaluputan.",
  "(kosong)": "(kosong)",

  // profil
  "Menu profil": "Menu profil",
  "Ubah profil": "Owahi profil",
  "Ubah kata sandi": "Owahi tembung sandi",
  "Nama tampilan": "Jeneng tampilan",
  Surel: "Email",
  "Belum ada surel": "Durung ana email",
  "Kosongkan untuk menghapus.": "Kosongna yèn arep dibusak.",
  "Nama pengguna dipakai untuk masuk dan hanya bisa diubah Administrator.":
    "Jeneng pangguna dienggo mlebu lan mung bisa diowahi Administrator.",
  "Kata sandi saat ini": "Tembung sandi saiki",
  "Kata sandi baru": "Tembung sandi anyar",
  "Ulangi kata sandi baru": "Baleni tembung sandi anyar",
  "Minimal 8 karakter.": "Paling sethithik 8 karakter.",
  "Nama tampilan wajib diisi.": "Jeneng tampilan kudu diisi.",
  "Semua isian wajib diisi.": "Kabèh isian kudu diisi.",
  "Kata sandi baru minimal 8 karakter.": "Tembung sandi anyar paling sethithik 8 karakter.",
  "Ulangan kata sandi baru tidak sama.": "Tembung sandi anyar sing dibaleni ora padha.",
  "Kata sandi baru harus berbeda dari yang lama.": "Tembung sandi anyar kudu béda karo sing lawas.",
  "Kata sandi berhasil diganti.": "Tembung sandi kasil diganti.",
  "Menyimpan...": "Nyimpen...",

  // masuk
  "Nama pengguna atau kata sandi salah.": "Jeneng pangguna utawa tembung sandi salah.",
  "Memeriksa...": "Mriksa...",

  // proses
  "Klik ganda pada barisnya untuk melihat riwayat jalan dan catatannya.":
    "Klik kaping pindho ing barisé kanggo ndeleng riwayat mlaku lan cathetané.",
  "Sedang berjalan ({0}). Bisa dijalankan lagi setelah selesai.":
    "Lagi mlaku ({0}). Bisa dilakokaké manèh sawisé rampung.",

  // paket
  "Versi Terbaru": "Vèrsi paling anyar",
  "Jumlah Versi": "Cacahé vèrsi",
  "Diterbitkan Oleh": "Diterbitaké déning",
  "Titik Masuk": "Titik mlebu",
  "Riwayat versi": "Riwayat vèrsi",
  Terbaru: "Paling anyar",
  "Satu baris per paket, dengan versi tertingginya. Klik ganda untuk melihat semua versinya.":
    "Siji baris saben pakèt, karo vèrsi paling dhuwuré. Klik kaping pindho kanggo ndeleng kabèh vèrsiné.",
  "Belum ada paket yang diterbitkan. Terbitkan dari Studio: tab Design → grup ForgeHub → Terbitkan.":
    "Durung ana pakèt sing diterbitaké. Terbitaké saka Studio: tab Design → grup ForgeHub → Terbitkan.",

  // aset
  "Tambah aset": "Tambah asèt",
  "Semua tipe": "Kabèh jinis",
  "Nilai yang dipakai bersama oleh banyak proses. Isi aset Secret dan kata sandi aset Credential tidak pernah tampil di daftar.":
    "Isi sing dienggo bebarengan déning akèh proses. Isi asèt Secret lan tembung sandi asèt Credential ora tau katon ing daftar.",
  "Kata sandi aset Credential tidak pernah dikirim ke layar ini — hanya robot yang memintanya lewat activity Get Credential yang menerimanya.":
    "Tembung sandi asèt Credential ora tau dikirim menyang layar iki — mung robot sing njaluk liwat activity Get Credential sing nampa.",
  "Disandikan sebelum disimpan, dan tidak pernah muncul di daftar.":
    "Disandèni sadurungé disimpen, lan ora tau katon ing daftar.",
  "Kosongkan kalau tidak ingin menggantinya.": "Kosongna yèn ora arep diganti.",
  "Tanpa kata sandi": "Tanpa tembung sandi",
  "Tampilkan kata sandi": "Tampilaké tembung sandi",
  "Sembunyikan kata sandi": "Singidaké tembung sandi",
  "Nama aset wajib diisi.": "Jeneng asèt kudu diisi.",

  // catatan
  "Ikuti otomatis": "Tut wuri otomatis",
  "Semua tingkat": "Kabèh tingkat",
  "Paling banyak 500 baris terbaru. Untuk catatan satu proses atau satu pekerjaan, buka detailnya dari halaman Proses atau Pekerjaan.":
    "Paling akèh 500 baris paling anyar. Kanggo cathetan siji proses utawa siji pagawéan, bukak rincian saka kaca Proses utawa Pagawéan.",

  // pekerjaan
  "Pilih prosesnya dulu.": "Pilih prosesé dhisik.",
  "Argumen masukan bukan JSON yang sah.": "Argumen input dudu JSON sing sah.",
  'Contoh: {"in_Nama":"Budi"}': 'Tuladha: {"in_Nama":"Budi"}',

  // pemicu
  "Tambah pemicu": "Tambah pamicu",
  "Nama pemicu wajib diisi.": "Jeneng pamicu kudu diisi.",
  "Selang waktu": "Selang wektu",
  "Ekspresi cron": "Ekspresi cron",
  "Lima ruas: menit jam tanggal bulan hari. Kalau tanggal DAN hari sama-sama diisi, cukup salah satu cocok.":
    "Lima ruas: menit jam tanggal sasi dina. Yèn tanggal LAN dina padha diisi, cukup salah siji sing cocog.",
  "Jalankan tiap (menit)": "Lakokna saben (menit)",
  "Jadwalnya dihitung menurut zona ini, bukan waktu server.":
    "Jadwalé diétung miturut zona iki, dudu wektu server.",
  "Tiap 15 menit": "Saben 15 menit",
  "Tiap jam, di menit ke-0": "Saben jam, ing menit 0",
  "Tiap hari pukul 07:00": "Saben dina jam 07:00",
  "Tiap hari kerja pukul 07:00": "Saben dina kerja jam 07:00",
  "Tiap tanggal 1 tengah malam": "Saben tanggal 1 tengah wengi",
  "Tiap Sabtu pukul 22:30": "Saben Setu jam 22:30",

  // antrean
  "Klik ganda pada barisnya untuk melihat butir-butirnya.":
    "Klik kaping pindho ing barisé kanggo ndeleng isiné.",
  "maks {0}": "maks {0}",

  // robot dan lingkungan
  "Robot mendaftarkan dirinya sendiri saat JakRunner berdenyut pertama kali; tidak perlu dibuat lebih dulu di sini.":
    "Robot ndhaftar dhéwé nalika JakRunner denyut sepisanan; ora perlu digawé dhisik ing kéné.",
  "Belum ada robot yang mendaftar.": "Durung ana robot sing ndhaftar.",
  "Tambah lingkungan": "Tambah lingkungan",
  "Nama lingkungan wajib diisi.": "Jeneng lingkungan kudu diisi.",

  // gudang
  "Berkas yang dipakai bersama oleh proses — masukan, keluaran, lampiran. Klik ganda untuk melihat isinya.":
    "Berkas sing dienggo bebarengan déning proses — input, output, lampiran. Klik kaping pindho kanggo ndeleng isiné.",
  "Berkasnya tidak bisa dibaca.": "Berkasé ora bisa diwaca.",

  // pengguna
  "Tambah pengguna": "Tambah pangguna",
  "Nama pengguna wajib diisi.": "Jeneng pangguna kudu diisi.",
  "Pengguna baru butuh kata sandi minimal 6 karakter.":
    "Pangguna anyar butuh tembung sandi paling sethithik 6 karakter.",
  "Minimal 6 karakter.": "Paling sethithik 6 karakter.",
  Aktif: "Aktif",

  // setelan
  Layanan: "Layanan",
  "Penyewa aktif": "Panyéwa saiki",
  "Basis data": "Basis data",
  "Zona tampilan": "Zona tampilan",
  "Waktu server": "Wektu server",
  "Robot dianggap putus setelah": "Robot dianggep pedhot sawisé",
  "Masa berlaku token": "Umur token",
  "{0} detik": "{0} detik",
  "{0} jam": "{0} jam",
  "Isi basis data": "Isi basis data",
  "tanpa batas": "tanpa wates",

  // keadaan kosong
  "Tidak ada pekerjaan yang sedang berjalan.": "Ora ana pagawéan sing mlaku.",
  "Tidak ada pemicu yang aktif.": "Ora ana pamicu sing urip.",

  // potongan yang dirangkai
  terdaftar: "kadhaftar",
  terputus: "pedhot",
  menunggu: "ngentèni",
  "pekerjaan hari ini": "pagawéan dina iki",
  berhasil: "kasil",
  gagal: "gagal",
  "CPU / Memori": "CPU / mèmori",
  "Semua keadaan": "Kabèh kahanan",
  "Semua proses": "Kabèh proses",
};
