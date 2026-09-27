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
  Proses: "Processes",
  Paket: "Packages",
  Antrean: "Queues",
  Aset: "Assets",
  Robot: "Robots",
  Mesin: "Machines",
  Lingkungan: "Environments",
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
  "Belum ada paket yang diterbitkan. Terbitkan dari Studio: tab Design → grup OpenOrchestrator → Terbitkan.":
    "No packages published yet. Publish from Studio: Design tab → OpenOrchestrator group → Terbitkan.",

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

  // navigasi Tenant dan Folders
  Tenant: "Tenant",
  Folders: "Folders",
  Folder: "Folder",
  // Tab dan judul halaman pengelolaan di konteks Tenant — jamak dalam bahasa Inggris.
  "Folder|tab": "Folders",
  Automations: "Automations",
  Queues: "Queues",
  Assets: "Assets",
  "Ember Penyimpanan": "Storage Buckets",
  Peringatan: "Alerts",
  Audit: "Audit",
  "Menu utama": "Main menu",
  Bagian: "Section",
  "Lihat semua peringatan": "See all alerts",

  // bilah folder
  "Folder Saya": "My Workspace",
  "Folder baru": "New folder",
  "Cari folder...": "Search folders...",
  "Tutup cabang": "Collapse",
  "Buka cabang": "Expand",
  "Anda tidak ditugaskan ke folder ini.": "You aren't assigned to this folder.",
  "Folder pribadi: hanya Anda yang melihatnya.": "Personal folder: only you can see it.",
  "Daftar folder tidak bisa diambil.": "Couldn't load the folder list.",
  "Tidak ada folder yang cocok.": "No matching folders.",
  "Anda belum ditugaskan ke folder bersama mana pun.": "You aren't assigned to any shared folder yet.",
  "Belum ada folder yang bisa dibuka": "No folder to open yet",
  "Anda belum ditugaskan ke folder bersama mana pun. Minta Administrator menugaskan Anda, atau pakai Folder Saya.": "You aren't assigned to any shared folder yet. Ask an Administrator to assign you, or use My Workspace.",
  "Buka Folder Saya": "Open My Workspace",

  // dasbor
  Processes: "Processes",
  Triggers: "Triggers",
  Users: "Users",
  Machines: "Machines",
  "{0} aktif": "{0} enabled",
  "Robot: {0}": "Robots: {0}",
  "Jobs Pekerjaan": "Jobs",
  "Jobs History": "Jobs History",
  "Pekerjaan per keadaan · {0}": "Jobs by state · {0}",
  "Pekerjaan per proses · {0}": "Jobs by process · {0}",
  pekerjaan: "jobs",
  "Belum ada pekerjaan pada rentang ini.": "No jobs in this range yet.",
  "Tingkat keberhasilan {0}% dari {1} pekerjaan yang selesai.": "Success rate {0}% of {1} finished jobs.",
  "Belum ada pekerjaan yang selesai pada rentang ini.": "No jobs have finished in this range yet.",
  "Lainnya ({0} proses)": "Other ({0} processes)",
  "Robot yang ditugaskan ke folder ini": "Robots assigned to this folder",
  "Seluruh penyewa": "Whole tenant",
  "Belum ada antrean di folder ini.": "No queues in this folder yet.",
  "Belum ada robot yang ditugaskan ke folder ini.": "No robots are assigned to this folder yet.",

  // keadaan
  Menunggu: "Pending",
  Berjalan: "Running",
  Menghentikan: "Stopping",
  Dihentikan: "Stopped",
  Tersedia: "Available",
  Sibuk: "Busy",
  Terputus: "Disconnected",
  "Dicoba ulang": "Retried",
  Nonaktif: "Disabled",

  // proses
  "Tambah proses": "Add process",
  "Ubah proses": "Edit process",
  "Cari proses...": "Search processes...",
  "Belum ada robot yang ditugaskan ke folder ini, jadi pekerjaan yang dijalankan di sini akan menunggu.": "No robots are assigned to this folder yet, so jobs started here will wait.",
  "Tugaskan robot": "Assign robot",
  "Pindahkan proses \"{0}\"": "Move process \"{0}\"",
  "Pemicu dan riwayat pekerjaannya ikut pindah.": "Its triggers and job history move with it.",
  "Pilih paketnya dulu.": "Choose a package first.",
  "Nama proses unik di dalam folder ini. Folder lain boleh punya proses bernama sama.": "Process names are unique within this folder. Other folders may have a process with the same name.",
  "Proses '{0}' sudah ada di folder ini.": "Process '{0}' already exists in this folder.",
  "Belum ada proses di folder ini. Terbitkan dari Studio — proses baru masuk ke folder Shared — atau tambahkan dari paket yang sudah ada.": "No processes in this folder yet. Publish from Studio — new processes land in the Shared folder — or add one from an existing package.",
  Ubah: "Edit",
  "Pindahkan ke folder lain": "Move to another folder",

  // pekerjaan dan pemicu
  "Belum ada pekerjaan di folder ini.": "No jobs in this folder yet.",
  "Belum ada robot yang ditugaskan ke folder ini; pekerjaannya akan menunggu.": "No robots are assigned to this folder yet; the job will wait.",
  "Kosongkan supaya robot mana pun di folder ini yang sedang bebas mengambilnya.": "Leave empty so any free robot in this folder picks it up.",
  "Robot mana pun di folder ini": "Any robot in this folder",
  "Belum ada pemicu di folder ini.": "No triggers in this folder yet.",
  "Pemicu tinggal di folder prosesnya: memindah proses ke folder lain ikut memindah pemicunya.": "Triggers live in their process's folder: moving the process moves its triggers too.",

  // paket
  "Paket yang dipakai proses di folder ini.": "Packages used by processes in this folder.",
  "Lihat seluruh umpan paket": "See the whole package feed",
  "Belum ada paket yang dipakai proses di folder ini.": "No package is used by a process in this folder yet.",
  "Hapus {0} versi {1} dari umpan paket? Proses yang memakai versi ini tidak bisa dijalankan lagi.": "Delete {0} version {1} from the package feed? Processes using this version can no longer run.",
  "Hapus versi ini": "Delete this version",

  // pemantauan
  "Atur robot folder ini": "Manage this folder's robots",
  "Robot yang ditugaskan ke folder ini mengambil pekerjaannya. Robot baru masuk ke folder Shared saat pertama kali tersambung.": "Robots assigned to this folder pick up its jobs. New robots join the Shared folder when they first connect.",
  "Belum ada catatan di folder ini.": "No logs in this folder yet.",
  "Paling banyak 500 baris terbaru dari pekerjaan di folder ini. Untuk catatan satu proses atau satu pekerjaan, buka detailnya dari halaman Proses atau Pekerjaan.": "Shows at most the latest 500 lines from jobs in this folder. For the logs of one process or one job, open its details from the Processes or Jobs page.",

  // antrean
  "Tambah antrean": "Add queue",
  "Lihat butir": "View items",
  "Hapus antrean \"{0}\" beserta {1} butirnya?": "Delete queue \"{0}\" and its {1} items?",
  "Pindahkan antrean \"{0}\"": "Move queue \"{0}\"",
  "Butir-butirnya ikut pindah bersama antreannya.": "Its items move with the queue.",
  "Jumlah percobaan ulang tidak boleh negatif.": "The retry count can't be negative.",
  "Nama antrean unik untuk seluruh penyewa: robot memanggilnya lewat nama.": "Queue names are unique across the tenant: robots call them by name.",
  "Percobaan ulang maksimal": "Maximum retries",
  "Butir yang gagal dicoba lagi sebanyak ini sebelum dianggap gagal permanen.": "A failed item is retried this many times before it counts as failed for good.",
  "Terima rujukan kembar": "Accept duplicate references",

  // aset
  "Belum ada aset di folder ini.": "No assets in this folder yet.",
  "Pindahkan aset \"{0}\"": "Move asset \"{0}\"",
  "Robot tetap membacanya lewat nama yang sama.": "Robots keep reading it by the same name.",

  // ember penyimpanan
  "Tambah ember": "Add bucket",
  "Buka isinya": "Open contents",
  "Hapus ember \"{0}\" beserta {1} berkasnya?": "Delete bucket \"{0}\" and its {1} files?",
  "Pindahkan ember \"{0}\"": "Move bucket \"{0}\"",
  "Berkas-berkasnya ikut pindah bersama embernya.": "Its files move with the bucket.",
  "Nama ember wajib diisi.": "Bucket name is required.",
  "Nama ember unik untuk seluruh penyewa: robot memanggilnya lewat nama.": "Bucket names are unique across the tenant: robots call them by name.",
  "Belum ada ember penyimpanan di folder ini.": "No storage buckets in this folder yet.",
  "Ember ini masih kosong.": "This bucket is empty.",

  // setelan folder
  Umum: "General",
  "Folder ini": "This folder",
  "Subfolder baru": "New subfolder",
  Jenis: "Kind",
  Jalur: "Path",
  "Tentang folder": "About folders",
  "Proses, pemicu, antrean, aset, dan ember penyimpanan tinggal di satu folder.": "Processes, triggers, queues, assets, and storage buckets each live in one folder.",
  "Robot hanya mengambil pekerjaan dari folder tempat ia ditugaskan, kecuali pekerjaan yang menyebut namanya langsung.": "Robots only pick up jobs from folders they're assigned to, unless a job names them directly.",
  "Hapus folder \"{0}\"? Folder harus sudah kosong; riwayat pekerjaannya pindah ke induknya.": "Delete folder \"{0}\"? It must be empty; its job history moves to the parent folder.",
  "Hapus folder": "Delete folder",
  "Pilih robot...": "Choose a robot...",
  "Lepas dari folder ini": "Remove from this folder",
  "Lepas robot \"{0}\" dari folder ini? Robot itu tidak akan mengambil pekerjaan folder ini lagi.": "Remove robot \"{0}\" from this folder? It will stop picking up this folder's jobs.",
  "Robot baru yang tersambung untuk pertama kali selalu masuk ke folder Shared. Subfolder baru mewarisi robot induknya saat dibuat.": "A robot that connects for the first time always joins the Shared folder. A new subfolder inherits its parent's robots when it's created.",
  "Pilih pengguna...": "Choose a user...",
  "Tugaskan pengguna": "Assign user",
  "Lepas \"{0}\" dari folder ini? Ia tidak akan melihat folder ini lagi.": "Remove \"{0}\" from this folder? They won't see it anymore.",
  "Administrator selalu bisa membuka semua folder bersama. Pengguna lain hanya melihat folder tempat ia ditugaskan.": "Administrators can always open every shared folder. Other users only see the folders they're assigned to.",
  Ditugaskan: "Assigned",
  "Belum ada robot yang ditugaskan ke folder ini. Pekerjaannya akan menunggu sampai ada.": "No robots are assigned to this folder yet. Its jobs will wait until there is one.",
  "Belum ada pengguna yang ditugaskan ke folder ini.": "No users are assigned to this folder yet.",
  "Folder bawaan": "Default folder",
  "Folder pribadi": "Personal folder",
  "Folder bersama": "Shared folder",

  // dialog folder dan pindah
  "Nama folder wajib diisi.": "Folder name is required.",
  "Nama folder tidak boleh memuat garis miring.": "Folder names can't contain slashes.",
  "Sunting folder": "Edit folder",
  Induk: "Parent",
  "Folder bawaan harus tetap di akar.": "The default folder must stay at the root.",
  "Folder baru mewarisi pengguna dan robot induknya.": "A new folder inherits its parent's users and robots.",
  "(Akar — tanpa induk)": "(Root — no parent)",
  "Pilih folder tujuannya dulu.": "Choose the target folder first.",
  Pindahkan: "Move",
  "Folder tujuan": "Target folder",
  "Tidak ada folder lain yang bisa Anda buka.": "There's no other folder you can open.",

  // Tenant: folder
  "Folder bawaan tidak bisa dihapus.": "The default folder can't be deleted.",
  "Masih punya subfolder.": "It still has subfolders.",
  "Masih berisi proses, pemicu, antrean, aset, atau ember.": "It still holds processes, triggers, queues, assets, or buckets.",
  bawaan: "default",
  "Buka folder ini": "Open this folder",
  "Hapus folder \"{0}\"? Riwayat pekerjaannya pindah ke induknya.": "Delete folder \"{0}\"? Its job history moves to the parent folder.",
  "Folder Saya milik pengguna": "Users' personal workspaces",
  "Folder pribadi hanya terlihat oleh pemiliknya. Yang kosong ikut terhapus saat penggunanya dihapus.": "A personal folder is only visible to its owner. An empty one is deleted along with its user.",
  "Hapus Folder Saya milik \"{0}\"?": "Delete \"{0}\"'s personal workspace?",
  Pemilik: "Owner",
  "Belum ada yang membuka Folder Saya.": "No one has opened My Workspace yet.",
  "Belum ada folder.": "No folders yet.",

  // Tenant: lainnya
  "Mesin ikut terdaftar sendiri saat robotnya pertama kali berdenyut.": "Machines register themselves on their robot's first heartbeat.",
  "Robot mendaftarkan dirinya sendiri saat JakRunner berdenyut pertama kali, dan langsung ditugaskan ke folder Shared.": "Robots register themselves on JakRunner's first heartbeat and are assigned to the Shared folder right away.",
  "Tidak di folder mana pun": "Not in any folder",
  "Pengguna baru langsung ditugaskan ke folder Shared. Atur folder lainnya di Setelan setiap folder.": "New users are assigned to the Shared folder right away. Manage other folders in each folder's Settings.",
  "Hanya yang belum dibaca": "Unread only",
  "Tandai dibaca": "Mark read",
  Komponen: "Component",
  "Semua komponen": "All components",
  "Cari pengguna atau sasaran...": "Search user or target...",
  "Yang tercatat hanya perubahan yang berhasil dilakukan orang — denyut, catatan, dan laporan robot tidak.": "Only successful changes made by people are recorded — robot heartbeats, logs, and reports are not.",
  Aksi: "Action",
  Sasaran: "Target",
  Rincian: "Details",
  "Belum ada perubahan yang tercatat.": "No changes recorded yet.",

  // audit: komponen dan aksi yang ditulis server
  Profil: "Profile",
  Sesi: "Session",
  Terbitkan: "Publish",
  "Alihkan status": "Toggle",
  Buat: "Create",
  "Hapus butir": "Delete item",
  "Unggah berkas": "Upload file",
  "Hapus berkas": "Delete file",
  "Ganti kata sandi": "Change password",
  "Lepas pengguna": "Remove user",
  "Lepas robot": "Remove robot",
  Bersihkan: "Clear",

  // tema
  Tampilan: "Appearance",
  Terang: "Light",
  Gelap: "Dark",
  "Ikuti sistem": "Use system setting",

  // bilah penyewa dan penjaga halaman
  "Pengelolaan penyewa: folder, pengguna, robot, paket, peringatan, audit, setelan.": "Tenant administration: folders, users, robots, packages, alerts, audit, settings.",
  "Tidak ada akses": "No access",
  "Peran Anda tidak mengizinkan membuka halaman ini. Minta Administrator menambahkan izinnya di Tenant › Pengguna › Peran.": "Your role doesn't allow opening this page. Ask an Administrator to add the permission in Tenant › Users › Roles.",
  "Peran Anda tidak mengizinkan membuka halaman ini.": "Your role doesn't allow opening this page.",

  // proses per folder
  "Nama proses dan pemicu cukup unik di dalam folder. Nama antrean, aset, dan ember tetap unik untuk seluruh penyewa, karena robot memanggilnya lewat nama.": "Process and trigger names only need to be unique within a folder. Queue, asset, and bucket names stay unique across the tenant, because robots call them by name.",

  // peran
  "Izin setiap peran diatur di tab Peran.": "Each role's permissions are set in the Roles tab.",
  "Tambah peran": "Add role",
  "Ubah peran": "Edit role",
  "Setiap pengguna memegang satu peran. Izinnya berlaku di semua folder tempat ia ditugaskan.": "Each user holds one role. Its permissions apply in every folder the user is assigned to.",
  Bawaan: "Built-in",
  "Semua izin": "All permissions",
  "{0} dari {1} izin": "{0} of {1} permissions",
  "Lihat izin": "View permissions",
  "Peran Administrator tidak bisa dihapus.": "The Administrator role can't be deleted.",
  "Masih dipakai {0} pengguna. Ganti peran mereka dulu.": "Still used by {0} users. Change their role first.",
  "Hapus peran \"{0}\"?": "Delete role \"{0}\"?",
  "Akun robot (JakRunner) sebaiknya memakai peran Robot: izinnya persis yang dibutuhkan robot, tidak lebih.": "Robot (JakRunner) accounts should use the Robot role: exactly the permissions a robot needs, nothing more.",
  "Nama peran wajib diisi.": "Role name is required.",
  "Peran bawaan dengan semua izin. Tidak bisa diubah atau dihapus, supaya selalu ada yang bisa mengurus OpenOrchestrator.": "Built-in role with every permission. It can't be edited or deleted, so someone can always manage OpenOrchestrator.",
  "Salin izin dari": "Copy permissions from",
  "Mulai dari izin peran lain, lalu ubah seperlunya.": "Start from another role's permissions, then adjust.",
  "Semua: {0}": "All: {0}",
  Semua: "All",
  "{0} izin dipilih.": "{0} permissions selected.",
  "Kotak yang mati adalah izin yang tidak dimiliki peran Anda sendiri.": "Disabled boxes are permissions your own role doesn't have.",
  "Peran Anda tidak punya izin ini.": "Your role doesn't have this permission.",
  Automasi: "Automation",
  "Pengelolaan penyewa": "Tenant administration",
  Lainnya: "Other",
  Lihat: "View",
  "Ubah: menghentikan pekerjaan; robot memakainya untuk mengambil dan melaporkan pekerjaan.": "Edit: stop jobs; robots use it to take and report jobs.",
  "Ubah: menambah dan memproses butir antrean.": "Edit: add and process queue items.",
  "Lihat: termasuk membuka isi aset rahasia dan kredensial.": "View: includes revealing secret and credential values.",
  "Ubah: denyut robot — akun JakRunner memerlukannya.": "Edit: robot heartbeat — JakRunner accounts need it.",
  "Ubah: menugaskan orang dan robot, sekaligus melihat semua folder.": "Edit: assign people and robots, and see every folder.",
  "Izin yang bisa diberikan hanya yang juga Anda miliki.": "You can only grant permissions you have yourself.",
};

/**
 * Teks yang datang dari SERVER: pesan galat, judul dan isi peringatan,
 * keterangan pekerjaan, baris catatan OpenOrchestrator dan JakRunner, serta
 * keterangan data awal. Dipakai lewat tp().
 *
 * Terpisah dari teks antarmuka dengan sengaja. Pola seperti "{0} menunggu"
 * boleh dipakai antarmuka, tapi tidak boleh ikut mencocokkan baris catatan
 * robot yang kebetulan berakhiran " menunggu".
 *
 * Teks yang tidak ada di sini tampil apa adanya. Itu yang terjadi pada pesan
 * yang ditulis workflow sendiri — isinya milik pembuat workflow, bukan
 * OpenOrchestrator.
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
  "Proses '{0}' belum diterbitkan ke OpenOrchestrator.": "Process '{0}' hasn't been published to OpenOrchestrator.",
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

  // --- baris catatan dari OpenOrchestrator dan JakRunner ---
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
  "Akses penuh ke seluruh OpenOrchestrator.": "Full access to all of OpenOrchestrator.",
  "Menerbitkan paket dan proses, menjalankan pekerjaan.": "Publishes packages and processes, runs jobs.",
  "Menjalankan proses yang sudah ada dan membaca hasilnya.": "Runs existing processes and reads their results.",
  "Hanya membaca — untuk pemeriksaan dan pelaporan.": "Read-only — for audits and reporting.",
  "Tempat proses diuji sebelum dipakai sungguhan.": "Where processes are tested before real use.",
  "Salinan mendekati produksi untuk uji terima.": "A near-production copy for acceptance testing.",
  "Proses yang berjalan untuk pekerjaan sebenarnya.": "Processes that do the real work.",
  "Antrean bawaan yang dipakai template ReFramework.": "The default queue used by the ReFramework template.",
  "Berkas yang dipakai bersama oleh proses — masukan, keluaran, lampiran.":
    "Files shared by processes — inputs, outputs, attachments.",
  "Mesin tempat OpenOrchestrator berjalan.": "The machine OpenOrchestrator runs on.",
  "Terdaftar sendiri lewat denyut robot.": "Registered automatically by a robot heartbeat.",

  // --- folder ---
  "Folder tidak ada.": "Folder not found.",
  "Anda tidak ditugaskan ke folder ini.": "You aren't assigned to this folder.",
  "Folder '{0}' sudah ada di tempat itu.": "Folder '{0}' already exists there.",
  "Folder Saya tidak bisa diganti nama atau dipindah.": "My Workspace can't be renamed or moved.",
  "Folder bawaan harus tetap di akar.": "The default folder must stay at the root.",
  "Folder tidak bisa dipindah ke dalam dirinya sendiri.": "A folder can't be moved into itself.",
  "Folder bawaan tidak bisa dihapus.": "The default folder can't be deleted.",
  "Folder '{0}' masih punya subfolder. Pindahkan atau hapus subfoldernya dulu.": "Folder '{0}' still has subfolders. Move or delete them first.",
  "Folder '{0}' masih berisi proses, pemicu, antrean, aset, atau ember penyimpanan. Pindahkan atau hapus isinya dulu.": "Folder '{0}' still holds processes, triggers, queues, assets, or storage buckets. Move or delete them first.",
  "Folder Saya milik '{0}' masih berisi proses, pemicu, antrean, aset, atau ember penyimpanan. Pindahkan atau hapus isinya dulu.": "{0}'s personal workspace still holds processes, triggers, queues, assets, or storage buckets. Move or delete them first.",
  "Pengguna itu tidak ditugaskan ke folder ini.": "That user isn't assigned to this folder.",
  "Robot itu tidak ditugaskan ke folder ini.": "That robot isn't assigned to this folder.",
  "Folder Saya hanya milik pemiliknya; pengguna lain tidak bisa ditugaskan ke sana.": "A personal workspace belongs to its owner alone; other users can't be assigned to it.",
  "Folder induk tidak ada.": "Parent folder not found.",
  "Folder Saya tidak bisa punya subfolder.": "A personal workspace can't have subfolders.",
  "Nama folder wajib diisi.": "Folder name is required.",
  "Nama folder paling panjang {0} karakter.": "A folder name can be at most {0} characters.",
  "Nama folder tidak boleh memuat garis miring.": "Folder names can't contain slashes.",
  "Keterangan paling panjang {0} karakter.": "The description can be at most {0} characters.",
  "{0} wajib diisi.": "{0} is required.",

  // --- isi folder ---
  "Proses '{0}' tidak ada di folder ini.": "Process '{0}' doesn't exist in this folder.",
  "Proses '{0}' ada di beberapa folder. Sebutkan foldernya.": "Process '{0}' exists in several folders. Specify the folder.",
  "Pemicu '{0}' ada di beberapa folder. Sebutkan foldernya.": "Trigger '{0}' exists in several folders. Specify the folder.",
  "Folder tujuan sudah punya proses bernama '{0}'.": "The destination folder already has a process named '{0}'.",
  "Folder tujuan sudah punya pemicu bernama '{0}'.": "The destination folder already has a trigger named '{0}'.",
  "Paket '{0}' versi {1} tidak ada.": "Package '{0}' version {1} doesn't exist.",
  "Nama aset '{0}' sudah dipakai di folder lain.": "The asset name '{0}' is already used in another folder.",
  "Jumlah percobaan ulang tidak boleh negatif.": "The retry count can't be negative.",
  "Nama ember wajib diisi.": "Bucket name is required.",
  "Ember '{0}' sudah ada.": "Storage bucket '{0}' already exists.",
  "Ember tidak ada.": "Storage bucket not found.",
  "Ember '{0}' tidak ada.": "Storage bucket '{0}' doesn't exist.",

  // --- keterangan pekerjaan dan data awal ---
  "Belum ada robot yang ditugaskan ke folder proses ini. Tugaskan robot lewat Setelan folder.": "No robot is assigned to this process's folder yet. Assign one in the folder's Settings.",
  "Folder bawaan. Semua yang dibuat tanpa menyebut folder masuk ke sini.": "The default folder. Anything created without naming a folder lands here.",

  // proses dan pemicu per folder

  // izin dan peran
  "Peran Anda tidak punya izin '{0}'.": "Your role doesn't have the '{0}' permission.",
  "Endpoint ini belum punya aturan izin.": "This endpoint has no permission rule yet.",
  "Peran '{0}' tidak ada.": "Role '{0}' doesn't exist.",
  "Peran '{0}' memuat izin '{1}' yang tidak dimiliki peran Anda.": "Role '{0}' includes the '{1}' permission, which your role doesn't have.",
  "Anda tidak bisa memberi izin '{0}' yang tidak dimiliki peran Anda sendiri.": "You can't grant the '{0}' permission, which your own role doesn't have.",
  "Nama peran wajib diisi.": "Role name is required.",
  "Nama peran paling panjang {0} karakter.": "A role name can be at most {0} characters.",
  "Peran '{0}' sudah ada.": "Role '{0}' already exists.",
  "Peran tidak ada.": "Role not found.",
  "Peran Administrator tidak bisa diubah.": "The Administrator role can't be edited.",
  "Peran Administrator tidak bisa dihapus.": "The Administrator role can't be deleted.",
  "Peran '{0}' masih dipakai {1} pengguna. Ganti peran mereka dulu.": "Role '{0}' is still used by {1} users. Change their role first.",
  "Izin tidak dikenal: '{0}'.": "Unknown permission: '{0}'.",
  "Menerbitkan paket, mengelola isi folder, menjalankan pekerjaan.": "Publishes packages, manages folder contents, runs jobs.",
  "Akun JakRunner: denyut, mengambil dan melaporkan pekerjaan, catatan, aset, antrean.": "JakRunner accounts: heartbeat, taking and reporting jobs, logs, assets, queues.",
};
