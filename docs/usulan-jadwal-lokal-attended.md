# Catatan: jadwal lokal Open Assistant (robot attended) dan dampaknya ke Orchestrator

> **Status (5 Okt 2026):** sisi Open Assistant sudah terkompilasi dan teruji di PC Fajar, yaitu
> jadwal lokal, Riwayat Jalan, dan penjaga bentrok dengan robot attended v2. **Tidak ada endpoint
> baru yang wajib**; tiga usulan di bawah bersifat opsional.

Untuk tim Open Orchestrator. Ditulis dari sisi robot (OpenStudio / Open Assistant).

## Apa yang berubah di Open Assistant

Open Assistant kini punya **jadwal lokal** untuk PC **attended**, yaitu PC tanpa service
Robot Agent. Di installer, ini PC yang memilih mode Attended.

- Orang di depan PC membuat jadwal sendiri dari halaman Jadwal, untuk satu automasi (proyek
  lokal, atau paket `.nupkg` dari folder lokal), dengan pilihan:
  - Sekali;
  - Tiap N menit (jam aktif opsional);
  - Harian;
  - Mingguan.
- Jadwal disimpan **hanya di PC itu**: `%LOCALAPPDATA%\JakForge\Assistant\schedules.json`.
- **Sengaja tidak disinkronkan ke Orchestrator.** Ini keputusan pemilik produk. Jadwal attended
  milik pengguna, harus tetap jalan walau Orchestrator mati atau tidak pernah disetel, dan
  tidak boleh muncul sebagai pemicu (trigger) di dasbor.
- Di PC **unattended** (service terpasang), jadwal lokal dimatikan. Halaman Jadwal hanya
  membaca pemicu Orchestrator seperti sebelumnya (`GET /api/triggers`, baca-saja).
- Setiap jalan, entah manual, dari jadwal lokal, atau job Orchestrator, dicatat di **Riwayat Jalan**
  lokal: `%LOCALAPPDATA%\JakForge\Assistant\history.json`.

## Yang mungkin terlihat di Orchestrator

1. **Robot sibuk tanpa job Orchestrator.** Selama automasi dari jadwal lokal berjalan:
   - Open Assistant yang memakai jalur v1 (akun pengguna, `jakrunner.json`) melaporkan
     `status: BUSY` di denyutnya, dengan log `Mulai menjalankan <nama>` … `Selesai: <nama>`,
     **tanpa** job id;
   - robot attended v2 (machine key) tetap melapor siap.
   Hal ini juga sudah terjadi untuk tombol Play manual sejak dulu.
2. **Job v2 bisa ditolak sementara.** Kalau Orchestrator mengirim job ke robot attended v2
   **selagi** jendela Open Assistant menjalankan automasi lokal, robot menolak meluncurkannya
   dengan pesan:

   `Open Assistant sedang menjalankan '<nama>' (manual/jadwal lokal) di PC ini.`

   Penolakan ini memakai jalur yang sama dengan penolakan saat sesi Windows terkunci, dan job
   dilaporkan gagal. Sebelumnya dua robot bisa jalan bersamaan dan berebut mouse.
   Sebaliknya, jadwal lokal **menunggu** selama robot v2 mengerjakan job Orchestrator.

## Usulan (opsional)

1. **Keadaan "sibuk lokal" di kontrak v2.** Contohnya `busyLocal: true` (dengan nama automasi)
   di denyut `/api/agent/heartbeat`, supaya:
   - Orchestrator tidak menugaskan job ke robot attended yang sedang dipakai lokal;
   - dasbor bisa menampilkan "Sibuk (lokal)", bukan menunggu lalu gagal.

   Sisi robot siap mengirimnya begitu nama kolomnya disepakati.
2. **Penanda pemicu di log v1.** Log jalan lokal bisa membawa `trigger: "local-schedule"` atau
   `"manual"`, supaya dasbor membedakannya dari job. Saat ini pembedanya hanya ketiadaan job id.
3. **Penolakan job yang bisa diulang.** Penolakan karena "sibuk lokal" atau "sesi terkunci"
   sebaiknya diperlakukan sebagai *bisa diulang* (job kembali ke antrean), bukan FAULTED final.
   Kalau Orchestrator punya kode status untuk ini, robot akan memakainya.

Tidak satu pun usulan ini menghalangi fitur: jadwal lokal bekerja penuh tanpa Orchestrator.
