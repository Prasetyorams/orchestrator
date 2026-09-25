/**
 * Kamus bahasa Inggris.
 *
 * Kuncinya teks Indonesianya sendiri (lihat lib/bahasa.ts). {0}, {1}, ...
 * adalah tempat nilai yang dirangkai — angka, nama — supaya urutan kata
 * mengikuti bahasa tujuannya, bukan urutan potongan di kode.
 */

/** Teks antarmuka: dipakai lewat t(). */
export const EN: Record<string, string> = {
  // navigasi
  Beranda: "Home",
  Pemantauan: "Monitoring",
  Pekerjaan: "Jobs",
  Catatan: "Logs",
  Pemicu: "Triggers",
  Otomasi: "Automation",
  Proses: "Processes",
  Paket: "Packages",
  Antrean: "Queues",
  Aset: "Assets",
  Kredensial: "Credentials",
  Robot: "Robots",
  Mesin: "Machines",
  Lingkungan: "Environments",
  Gudang: "Storage",
  Penyewa: "Tenants",
  Pengguna: "Users",
  Peran: "Roles",
  Lisensi: "Licensing",
  Setelan: "Settings",

  // bentuk tunggal untuk judul kolom dan isian — lihat terjemahkan() di lib/bahasa.ts
  "Robot|satu": "Robot",
  "Proses|satu": "Process",
  "Mesin|satu": "Machine",
  "Lingkungan|satu": "Environment",
  "Paket|satu": "Package",

  // bilah atas
  "Cari proses, robot, antrean...": "Search processes, robots, queues...",

  // dasbor
  "Robot Aktif": "Active robots",
  "Pekerjaan Berjalan": "Running jobs",
  "Tingkat Keberhasilan": "Success rate",
  "Sedang Berjalan": "In progress",
  "Pemicu Berikutnya": "Upcoming triggers",
  "Peringatan Terbaru": "Recent alerts",
  "Ringkasan Antrean": "Queue summary",
  "Tidak bisa mengambil data dasbor.": "Couldn't load the dashboard.",
  Harian: "Daily",
  Mingguan: "Weekly",
  Bulanan: "Monthly",
  Tahunan: "Yearly",
  "Rentang waktu: {0}": "Time range: {0}",
  "Hari ini": "Today",
  "Sejak {0}": "Since {0}",
  "Saat ini": "Now",
  "Keadaan saat ini, tidak bergantung pada rentang waktu.": "Current state; not tied to a time range.",
  "Riwayat pekerjaan": "Job history",
  "{0} terdaftar · {1} terputus": "{0} registered · {1} disconnected",
  "{0} menunggu": "{0} waiting",
  "{0} pekerjaan hari ini": "{0} jobs today",
  "{0} pekerjaan minggu ini": "{0} jobs this week",
  "{0} pekerjaan bulan ini": "{0} jobs this month",
  "{0} pekerjaan tahun ini": "{0} jobs this year",
  "Riwayat per jam, hari ini": "Hourly history, today",
  "Riwayat per hari, minggu ini": "Daily history, this week",
  "Riwayat per hari, bulan ini": "Daily history, this month",
  "Riwayat per bulan, tahun ini": "Monthly history, this year",
  "{0}: {1} berhasil, {2} gagal": "{0}: {1} succeeded, {2} failed",
  "Belum ada peringatan.": "No alerts yet.",
  "Tidak ada peringatan pada tingkat ini.": "No alerts at this level.",
  "tiap {0} menit": "every {0} minutes",

  // kolom
  Nama: "Name",
  Status: "State",
  Keadaan: "State",
  Prioritas: "Priority",
  Sumber: "Source",
  Kemajuan: "Progress",
  Dibuat: "Created",
  Dimulai: "Started",
  Selesai: "Ended",
  Keterangan: "Description",
  Versi: "Version",
  Ukuran: "Size",
  Diterbitkan: "Published",
  Berkas: "Files",
  Tingkat: "Level",
  Pesan: "Message",
  Waktu: "Time",
  Jadwal: "Schedule",
  "Jalan Berikutnya": "Next run",
  "Jalan Terakhir": "Last run",
  "Zona Waktu": "Time zone",
  Percobaan: "Retries",
  Rujukan: "Reference",
  Tipe: "Type",
  Denyut: "Heartbeat",
  Memori: "Memory",
  Nilai: "Value",
  Diubah: "Modified",
  Baru: "New",
  Diproses: "In progress",
  Berhasil: "Succeeded",
  Gagal: "Failed",
  Total: "Total",
  Isi: "Content",
  Galat: "Error",
  Info: "Info",
  Diunggah: "Uploaded",
  Oleh: "By",
  Kode: "Code",
  "Masuk terakhir": "Last sign-in",
  Produk: "Product",
  Terpakai: "Used",
  "Berlaku sampai": "Valid until",
  Izin: "Permissions",

  // tombol dan aksi
  Muat: "Refresh",
  "Muat ulang": "Refresh",
  Jalankan: "Run",
  Hentikan: "Stop",
  Hapus: "Delete",
  Simpan: "Save",
  Batal: "Cancel",
  Tutup: "Close",
  Tambah: "Add",
  Sunting: "Edit",
  Unduh: "Download",
  Unggah: "Upload",
  Cari: "Search",
  Detail: "Details",
  Nyalakan: "Enable",
  Matikan: "Disable",
  Keluar: "Sign out",
  Masuk: "Sign in",
  "Tandai semua dibaca": "Mark all read",
  Tampilkan: "Show",
  Sembunyikan: "Hide",

  // pesan umum
  "Belum ada data.": "Nothing here yet.",
  "Memuat...": "Loading...",
  "Tidak ada yang cocok.": "No matches.",
  "Yakin menghapus": "Delete",
  Halaman: "Page",
  dari: "of",
  dipilih: "selected",
  baris: "rows",
  "Nama pengguna": "Username",
  "Kata sandi": "Password",
  Bahasa: "Language",
  "Pilih semua di halaman ini": "Select all on this page",
  "Pilih baris": "Select row",
  "Terjadi kesalahan.": "Something went wrong.",
  "(kosong)": "(empty)",

  // profil
  "Menu profil": "Profile menu",
  "Ubah profil": "Edit profile",
  "Ubah kata sandi": "Change password",
  "Nama tampilan": "Display name",
  Surel: "Email",
  "Belum ada surel": "No email yet",
  "Kosongkan untuk menghapus.": "Leave empty to remove it.",
  "Nama pengguna dipakai untuk masuk dan hanya bisa diubah Administrator.":
    "The username is used to sign in and can only be changed by an Administrator.",
  "Kata sandi saat ini": "Current password",
  "Kata sandi baru": "New password",
  "Ulangi kata sandi baru": "Confirm new password",
  "Minimal 8 karakter.": "At least 8 characters.",
  "Nama tampilan wajib diisi.": "Display name is required.",
  "Semua isian wajib diisi.": "All fields are required.",
  "Kata sandi baru minimal 8 karakter.": "The new password must be at least 8 characters.",
  "Ulangan kata sandi baru tidak sama.": "The new passwords don't match.",
  "Kata sandi baru harus berbeda dari yang lama.": "The new password must differ from the current one.",
  "Kata sandi berhasil diganti.": "Your password has been changed.",
  "Menyimpan...": "Saving...",

  // masuk
  "Nama pengguna atau kata sandi salah.": "Wrong username or password.",
  "Memeriksa...": "Checking...",

  // proses
  "Klik ganda pada barisnya untuk melihat riwayat jalan dan catatannya.":
    "Double-click a row to see its run history and logs.",
  "Sedang berjalan ({0}). Bisa dijalankan lagi setelah selesai.":
    "Running ({0}). You can run it again once it finishes.",

  // paket
  "Versi Terbaru": "Latest version",
  "Jumlah Versi": "Versions",
  "Diterbitkan Oleh": "Published by",
  "Titik Masuk": "Entry point",
  "Riwayat versi": "Version history",
  Terbaru: "Latest",
  "Satu baris per paket, dengan versi tertingginya. Klik ganda untuk melihat semua versinya.":
    "One row per package, showing its highest version. Double-click to see every version.",
  "Belum ada paket yang diterbitkan. Terbitkan dari Studio: tab Design → grup ForgeHub → Terbitkan.":
    "No packages published yet. Publish from Studio: Design tab → ForgeHub group → Terbitkan.",

  // aset
  "Tambah aset": "Add asset",
  "Semua tipe": "All types",
  "Nilai yang dipakai bersama oleh banyak proses. Isi aset Secret dan kata sandi aset Credential tidak pernah tampil di daftar.":
    "Values shared by many processes. Secret values and Credential passwords are never shown in the list.",
  "Kata sandi aset Credential tidak pernah dikirim ke layar ini — hanya robot yang memintanya lewat activity Get Credential yang menerimanya.":
    "A Credential's password is never sent to this screen — only a robot that asks for it through the Get Credential activity receives it.",
  "Disandikan sebelum disimpan, dan tidak pernah muncul di daftar.":
    "Encrypted before it's saved, and never shown in the list.",
  "Kosongkan kalau tidak ingin menggantinya.": "Leave empty to keep the current one.",
  "Tanpa kata sandi": "No password",
  "Tampilkan kata sandi": "Show password",
  "Sembunyikan kata sandi": "Hide password",
  "Nama aset wajib diisi.": "Asset name is required.",

  // catatan
  "Ikuti otomatis": "Follow live",
  "Semua tingkat": "All levels",
  "Paling banyak 500 baris terbaru. Untuk catatan satu proses atau satu pekerjaan, buka detailnya dari halaman Proses atau Pekerjaan.":
    "Shows at most the latest 500 lines. For the logs of one process or one job, open its details from the Processes or Jobs page.",

  // pekerjaan
  "Pilih prosesnya dulu.": "Choose a process first.",
  "Argumen masukan bukan JSON yang sah.": "The input arguments aren't valid JSON.",
  'Contoh: {"in_Nama":"Budi"}': 'Example: {"in_Name":"Budi"}',

  // pemicu
  "Tambah pemicu": "Add trigger",
  "Nama pemicu wajib diisi.": "Trigger name is required.",
  "Selang waktu": "Interval",
  "Ekspresi cron": "Cron expression",
  "Lima ruas: menit jam tanggal bulan hari. Kalau tanggal DAN hari sama-sama diisi, cukup salah satu cocok.":
    "Five fields: minute hour day-of-month month day-of-week. If both day-of-month AND day-of-week are set, matching either one is enough.",
  "Jalankan tiap (menit)": "Run every (minutes)",
  "Jadwalnya dihitung menurut zona ini, bukan waktu server.":
    "The schedule follows this time zone, not the server's clock.",
  "Tiap 15 menit": "Every 15 minutes",
  "Tiap jam, di menit ke-0": "Every hour, on the hour",
  "Tiap hari pukul 07:00": "Every day at 07:00",
  "Tiap hari kerja pukul 07:00": "Every weekday at 07:00",
  "Tiap tanggal 1 tengah malam": "Midnight on the 1st of every month",
  "Tiap Sabtu pukul 22:30": "Every Saturday at 22:30",

  // antrean
  "Klik ganda pada barisnya untuk melihat butir-butirnya.": "Double-click a row to see its items.",
  "maks {0}": "max {0}",

  // robot dan lingkungan
  "Robot mendaftarkan dirinya sendiri saat JakRunner berdenyut pertama kali; tidak perlu dibuat lebih dulu di sini.":
    "Robots register themselves on JakRunner's first heartbeat; there's no need to create them here first.",
  "Belum ada robot yang mendaftar.": "No robot has registered yet.",
  "Tambah lingkungan": "Add environment",
  "Nama lingkungan wajib diisi.": "Environment name is required.",

  // gudang
  "Berkas yang dipakai bersama oleh proses — masukan, keluaran, lampiran. Klik ganda untuk melihat isinya.":
    "Files shared by processes — inputs, outputs, attachments. Double-click to see what's inside.",
  "Berkasnya tidak bisa dibaca.": "The file couldn't be read.",

  // pengguna
  "Tambah pengguna": "Add user",
  "Nama pengguna wajib diisi.": "Username is required.",
  "Pengguna baru butuh kata sandi minimal 6 karakter.":
    "A new user needs a password of at least 6 characters.",
  "Minimal 6 karakter.": "At least 6 characters.",
  Aktif: "Active",

  // setelan
  Layanan: "Service",
  "Penyewa aktif": "Current tenant",
  "Basis data": "Database",
  "Zona tampilan": "Display time zone",
  "Waktu server": "Server time",
  "Robot dianggap putus setelah": "Robots count as disconnected after",
  "Masa berlaku token": "Token lifetime",
  "{0} detik": "{0} seconds",
  "{0} jam": "{0} hours",
  "Isi basis data": "Database contents",
  "tanpa batas": "unlimited",

  // keadaan kosong
  "Tidak ada pekerjaan yang sedang berjalan.": "No jobs are running.",
  "Tidak ada pemicu yang aktif.": "No triggers are enabled.",

  // potongan yang dirangkai
  terdaftar: "registered",
  terputus: "disconnected",
  menunggu: "waiting",
  "pekerjaan hari ini": "jobs today",
  berhasil: "succeeded",
  gagal: "failed",
  "CPU / Memori": "CPU / memory",
  "Semua keadaan": "All states",
  "Semua proses": "All processes",
};

/**
 * Teks yang datang dari SERVER: pesan galat, judul dan isi peringatan,
 * keterangan pekerjaan, baris catatan ForgeHub dan JakRunner, serta
 * keterangan data awal. Dipakai lewat tp().
 *
 * Terpisah dari teks antarmuka dengan sengaja. Pola seperti "{0} menunggu"
 * boleh dipakai antarmuka, tapi tidak boleh ikut mencocokkan baris catatan
 * robot yang kebetulan berakhiran " menunggu".
 *
 * Teks yang tidak ada di sini tampil apa adanya. Itu yang terjadi pada pesan
 * yang ditulis workflow sendiri — isinya milik pembuat workflow, bukan
 * ForgeHub.
 */
export const PESAN_EN: Record<string, string> = {
  // --- galat: umum ---
  "Terjadi kesalahan di server.": "Something went wrong on the server.",
  "Badan permintaan bukan JSON yang sah.": "The request body isn't valid JSON.",

  // --- galat: masuk, pengguna, profil ---
  "Pengguna sudah tidak ada.": "This user no longer exists.",
  "Kata sandi minimal 6 karakter.": "The password must be at least 6 characters.",
  "Tidak bisa menghapus akun yang sedang dipakai.": "You can't delete the account you're signed in with.",
  "Ini satu-satunya Administrator yang tersisa.": "This is the only Administrator left.",
  "Pengguna tidak ada.": "User not found.",
  "Hanya Administrator yang boleh mengubah pengguna.": "Only an Administrator can change users.",
  "Hanya Administrator yang boleh menghapus pengguna.": "Only an Administrator can delete users.",
  "Nama tampilan paling panjang {0} karakter.": "The display name can be at most {0} characters.",
  "Alamat surel paling panjang {0} karakter.": "The email address can be at most {0} characters.",
  "Alamat surel tidak sah.": "That email address isn't valid.",
  "Kata sandi saat ini wajib diisi.": "The current password is required.",
  "Kata sandi saat ini salah.": "The current password is wrong.",

  // --- galat: proses, paket, pekerjaan ---
  "Nama proses wajib diisi.": "Process name is required.",
  "Proses '{0}' tidak ada.": "Process '{0}' doesn't exist.",
  "Nama paket wajib diisi.": "Package name is required.",
  "Paket tidak ada atau tanpa isi.": "The package doesn't exist or has no content.",
  "Paket tidak ada.": "Package not found.",
  "contentBase64 bukan base64 yang sah.": "contentBase64 isn't valid base64.",
  "Paket terlalu besar. Batasnya {0} MB.": "The package is too large. The limit is {0} MB.",
  "Pekerjaan tidak ada.": "Job not found.",
  "processName wajib diisi.": "processName is required.",
  "Proses '{0}' belum diterbitkan ke ForgeHub.": "Process '{0}' hasn't been published to ForgeHub.",
  "Parameter 'robot' wajib diisi.": "The 'robot' parameter is required.",
  "Keadaan tidak dikenal: '{0}'.": "Unknown state: '{0}'.",
  "Pekerjaan itu tidak sedang menunggu atau berjalan.": "That job isn't pending or running.",

  // --- galat: catatan, peringatan, dasbor ---
  'Butuh { "lines": [ ... ] }.': 'Expected { "lines": [ ... ] }.',
  "Terlalu banyak baris dalam satu kiriman. Batasnya {0}.": "Too many lines in one batch. The limit is {0}.",
  "Parameter 'olderThanDays' wajib diisi dan minimal 1.":
    "The 'olderThanDays' parameter is required and must be at least 1.",
  "Tingkat catatan tidak dikenal: '{0}'.": "Unknown log level: '{0}'.",
  "Tingkat peringatan tidak dikenal: '{0}'.": "Unknown alert level: '{0}'.",
  "Periode tidak dikenal: '{0}'. Pilih today, week, month, atau year.":
    "Unknown period: '{0}'. Choose today, week, month, or year.",

  // --- galat: antrean ---
  "Nama antrean wajib diisi.": "Queue name is required.",
  "Antrean '{0}' sudah ada.": "Queue '{0}' already exists.",
  "Antrean tidak ada.": "Queue not found.",
  "Antrean '{0}' tidak ada.": "Queue '{0}' doesn't exist.",
  "Butir dengan referensi '{0}' sudah menunggu di antrean ini.":
    "An item with reference '{0}' is already waiting in this queue.",
  "Status hasil tidak dikenal: '{0}'.": "Unknown result status: '{0}'.",
  "Butir antrean tidak ada.": "Queue item not found.",

  // --- galat: robot, mesin, lingkungan ---
  "Robot '{0}' tidak ada.": "Robot '{0}' doesn't exist.",
  "Nama robot wajib diisi.": "Robot name is required.",
  "Robot '{0}' sudah ada.": "Robot '{0}' already exists.",
  "Nama mesin wajib diisi.": "Machine name is required.",
  "Mesin '{0}' sudah ada.": "Machine '{0}' already exists.",
  "Mesin '{0}' tidak ada.": "Machine '{0}' doesn't exist.",
  "Lingkungan '{0}' sudah ada.": "Environment '{0}' already exists.",
  "Lingkungan '{0}' tidak ada.": "Environment '{0}' doesn't exist.",

  // --- galat: pemicu ---
  "Ekspresi cron tidak sah: {0}. Bentuknya lima ruas: menit jam tanggal bulan hari, mis. \"0 7 * * 1-5\" untuk tiap hari kerja pukul 07:00.":
    "Invalid cron expression: {0}. It has five fields: minute hour day-of-month month day-of-week, e.g. \"0 7 * * 1-5\" for every weekday at 07:00.",
  "Selang waktu minimal 1 menit.": "The interval must be at least 1 minute.",
  "Zona waktu tidak dikenal: '{0}'. Pakai nama IANA, mis. \"Asia/Jakarta\".":
    "Unknown time zone: '{0}'. Use an IANA name, e.g. \"Asia/Jakarta\".",
  "Pemicu tidak ada.": "Trigger not found.",

  // --- galat: aset, kredensial, gudang ---
  "Tipe aset tidak dikenal: '{0}'.": "Unknown asset type: '{0}'.",
  "Aset '{0}' tidak ada.": "Asset '{0}' doesn't exist.",
  "Aset tidak ada.": "Asset not found.",
  "Aset bertipe Integer harus berisi bilangan bulat.": "An Integer asset must contain a whole number.",
  "Aset bertipe Bool harus berisi true atau false.": "A Bool asset must contain true or false.",
  "Nama kredensial wajib diisi.": "Credential name is required.",
  "Kredensial tidak ada.": "Credential not found.",
  "Nama '{0}' sudah dipakai aset bertipe {1}.": "The name '{0}' is already used by a {1} asset.",
  "Nama gudang wajib diisi.": "Storage bucket name is required.",
  "Gudang '{0}' sudah ada.": "Storage bucket '{0}' already exists.",
  "Gudang tidak ada.": "Storage bucket not found.",
  "Gudang '{0}' tidak ada.": "Storage bucket '{0}' doesn't exist.",
  "fileName wajib diisi dan sah.": "fileName is required and must be valid.",
  "Berkas terlalu besar. Batasnya {0} MB.": "The file is too large. The limit is {0} MB.",
  "Berkas tidak ada.": "File not found.",

  // --- judul peringatan ---
  "Paket diterbitkan": "Package published",
  "Robot baru terdaftar": "New robot registered",
  "Butir antrean gagal permanen": "Queue item failed permanently",
  "Pemicu dimatikan": "Trigger disabled",
  "Pekerjaan gagal": "Job failed",
  "Pekerjaan terputus": "Job interrupted",
  "Kesalahan pada {0}": "Error on {0}",
  'Kredensial "{0}" dipindah dengan nama baru': 'Credential "{0}" moved under a new name',
  'Kredensial "{0}" tidak bisa dipindah': 'Credential "{0}" couldn\'t be moved',

  // --- isi peringatan ---
  "{0} {1} diterbitkan oleh {2}.": "{0} {1} published by {2}.",
  "Robot '{0}' menyambung untuk pertama kali.": "Robot '{0}' connected for the first time.",
  "Butir '{0}' di antrean {1} gagal setelah {2} percobaan ulang.":
    "Item '{0}' in queue {1} failed after {2} retries.",
  "Pemicu '{0}' menunjuk proses '{1}' yang sudah tidak ada.":
    "Trigger '{0}' points to process '{1}', which no longer exists.",
  "Pemicu '{0}' memakai ekspresi cron yang tidak pernah cocok: {1}":
    "Trigger '{0}' uses a cron expression that never matches: {1}",
  "{0} gagal: {1}": "{0} failed: {1}",
  "tanpa keterangan": "no details",
  "{0} dihentikan karena robot '{1}' tidak lagi terhubung.":
    "{0} was stopped because robot '{1}' is no longer connected.",
  'Nama "{0}" sudah dipakai aset lain, jadi kredensial ini sekarang bernama "{1}". Perbarui activity Get Credential yang memakainya.':
    'The name "{0}" is already used by another asset, so this credential is now called "{1}". Update the Get Credential activities that use it.',
  'Nama "{0}" dan "{1}" sudah dipakai aset lain. Salinannya tetap ada di tabel credentials_lama; buat ulang sebagai aset bertipe Credential.':
    'The names "{0}" and "{1}" are already used by other assets. A copy is still in the credentials_lama table; recreate it as a Credential asset.',

  // --- keterangan pekerjaan ---
  "Menunggu robot yang tersedia.": "Waiting for an available robot.",
  "Dijadwalkan oleh pemicu '{0}'.": "Scheduled by trigger '{0}'.",
  "Sedang dijalankan.": "Running.",
  "Diminta berhenti.": "Stop requested.",
  "Robot {0} berhenti berdenyut saat pekerjaan masih berjalan.":
    "Robot {0} stopped sending heartbeats while the job was still running.",

  // --- keterangan pekerjaan dari JakRunner ---
  Berjalan: "Running",
  Dihentikan: "Stopped",
  "Selesai tanpa kesalahan.": "Finished without errors.",
  "Proses '{0}' tidak ada di komputer ini. Salin proyeknya ke sini, atau jalankan di robot lain.":
    "Process '{0}' isn't on this computer. Copy its project here, or run it on another robot.",
  "Dihentikan dari JakRunner": "Stopped from JakRunner",

  // --- baris catatan dari ForgeHub dan JakRunner ---
  "Pekerjaan dijadwalkan untuk {0}.": "Job scheduled for {0}.",
  "Paket {0} {1} diterbitkan.": "Package {0} {1} published.",
  "Pemicu '{0}' menjadwalkan {1}.": "Trigger '{0}' scheduled {1}.",
  "Mulai menjalankan {0}": "Started running {0}",
  "Selesai: {0}": "Finished: {0}",
  "GAGAL: {0} — {1}": "FAILED: {0} — {1}",
  "Gagal di {0}": "Failed at {0}",
  "Gagal memulai: {0}": "Couldn't start: {0}",
  "Gagal menjeda: {0}": "Couldn't pause: {0}",
  "Gagal menghentikan: {0}": "Couldn't stop: {0}",
  "Selesai dengan kesalahan: {0}": "Finished with errors: {0}",
  "Dihentikan: {0}": "Stopped: {0}",
  "Menghentikan {0}": "Stopping {0}",
  "Dibatalkan.": "Cancelled.",
  "Membaca aset '{0}'.": "Reading asset '{0}'.",
  "Membaca kredensial '{0}'.": "Reading credential '{0}'.",
  "Tingkat log '{0}' tidak dikenal; dicatat sebagai Info.":
    "Log level '{0}' isn't recognised; logged as Info.",

  // --- keterangan data awal ---
  "Akses penuh ke seluruh ForgeHub.": "Full access to all of ForgeHub.",
  "Menerbitkan paket dan proses, menjalankan pekerjaan.": "Publishes packages and processes, runs jobs.",
  "Menjalankan proses yang sudah ada dan membaca hasilnya.": "Runs existing processes and reads their results.",
  "Hanya membaca — untuk pemeriksaan dan pelaporan.": "Read-only — for audits and reporting.",
  "Tempat proses diuji sebelum dipakai sungguhan.": "Where processes are tested before real use.",
  "Salinan mendekati produksi untuk uji terima.": "A near-production copy for acceptance testing.",
  "Proses yang berjalan untuk pekerjaan sebenarnya.": "Processes that do the real work.",
  "Antrean bawaan yang dipakai template ReFramework.": "The default queue used by the ReFramework template.",
  "Berkas yang dipakai bersama oleh proses — masukan, keluaran, lampiran.":
    "Files shared by processes — inputs, outputs, attachments.",
  "Mesin tempat ForgeHub berjalan.": "The machine ForgeHub runs on.",
  "Terdaftar sendiri lewat denyut robot.": "Registered automatically by a robot heartbeat.",
};
