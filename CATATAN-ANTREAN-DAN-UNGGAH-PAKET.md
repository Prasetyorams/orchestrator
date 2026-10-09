# Catatan dari tim robot: item antrean yang tertahan, dan unggah paket

Dua temuan dari audit arsitektur JakForge (OpenStudio / Robot Agent), sebagai
bahan diskusi. Berkas ini hanya dokumentasi; tidak ada perubahan kode.

Keduanya diperiksa di `8754cc4`. Statusnya **dari kode**: belum dicoba
melawan server yang berjalan. Nomor baris mengacu ke
`backend/src/main/java/id/jakforge/openorchestrator/`.

| # | Temuan | Berat | Butuh migrasi |
|---|---|---|---|
| 1 | Item antrean IN_PROGRESS tidak pernah dilepas saat job-nya berakhir | Sedang (data) | ya (`queue_items.job_id`) |
| 2 | Paket diunggah sebagai base64 di dalam JSON | Rendah–sedang (skala) | tidak |

---

## 1. Item antrean IN_PROGRESS tertahan selamanya saat job-nya gagal

**Bukti.**
- `QueueRepository.claimNextItem` (`repository/QueueRepository.java:202`)
  hanya menulis `status = 'IN_PROGRESS', robot_name, started_at`. Tabel
  `queue_items` tidak mencatat job mana yang memegang item itu.
- `JobOutcomeService.afterFinished` (`service/JobOutcomeService.java:43`)
  adalah satu-satunya jalan menuju "selesai": laporan agent, rekonsiliasi
  denyut, lease habis, dan agent hilang. Fungsi ini tidak menyentuh antrean.
- Tidak ada kode yang menetapkan `ABANDONED`. Status itu hanya bisa
  *dilaporkan* robot (`QueueItemStatus.isReportable`).
- `hasPendingDuplicate` (`repository/QueueRepository.java:184`) ikut
  menghitung IN_PROGRESS. Selama `accept_duplicates = false`, referensi yang
  tertahan juga menolak item pengganti.

**Dampak.** Ambil contoh workflow yang mengambil item, lalu robotnya jatuh: job
menjadi FAULTED/STOPPED atau agent hilang. Item itu tetap IN_PROGRESS tanpa
batas waktu. Item tidak diproses ulang dan tidak terlihat sebagai masalah di
dasbor. Sebagai pembanding, UiPath mengubah item yang In Progress lebih dari
24 jam menjadi Abandoned.

**Usulan.**
1. Migrasi: tambahkan `queue_items.job_id UUID NULL` dan indeks
   `(tenant_id, job_id) WHERE status = 'IN_PROGRESS'`.
2. `claimNextItem`: isi `job_id` dari prinsipal Executor
   (`principal.jobId()`, token job v2). Untuk prinsipal pengguna atau v1,
   biarkan NULL.
3. `JobOutcomeService.afterFinished`, untuk job FAULTED atau STOPPED: item
   `job_id = job` yang masih IN_PROGRESS dikembalikan lewat `requeueForRetry`
   kalau jatah `max_retries` antreannya masih ada. Isi `exception` dengan
   "Job <id> berakhir <state> sebelum item dilaporkan". Kalau jatahnya habis,
   tandai item `ABANDONED`. Cukup satu peringatan per job, bukan satu per item.
4. Penyapu berkala, misalnya bersama `AgentMonitor` yang sudah ada: item
   IN_PROGRESS tanpa `job_id` yang lebih tua dari batas tertentu (bisa disetel,
   bawaan 24 jam) menjadi `ABANDONED`.

**Uji yang diusulkan.**
- Job v2 mengambil item, lalu agent melapor FAULTED: item kembali NEW dengan
  `retries + 1`.
- Jatah percobaan habis: item menjadi ABANDONED, dengan tepat satu peringatan.
- Laporan SUCCESSFUL untuk item yang sudah dikembalikan ditolak (409).
- Item IN_PROGRESS tanpa `job_id` yang lebih tua dari batas menjadi ABANDONED
  pada putaran penyapu berikutnya.

**Sisi robot.** Tidak perlu perubahan selama job v2 berbagi sesi
(`shareSessionWithWorkflow`). Dalam keadaan itu agent meneruskan token Executor
ke runtime, dan activity Get Queue Item memakainya, jadi `job_id` terisi
sendiri. Tanpa berbagi sesi, activity memakai login lain: `job_id` tetap NULL,
dan hanya penyapu (butir 4) yang menolong.

---

## 2. Paket diunggah sebagai base64 di dalam JSON

**Bukti.**
- `PublishPackageRequest.contentBase64`. `PackageService` men-dekode seluruh
  isinya sekaligus (`service/PackageService.java:82`).
- Batas `max-package-size: 64MB` (`application.yml`). Paket 64 MB berarti
  badan JSON sekitar 86 MB yang ditampung utuh sebagai `String`, ditambah
  64 MB `byte[]`, untuk setiap unggahan.
- Penyimpanannya sudah benar: `packages.content BYTEA` (V1).

**Dampak.**
- Memori puncak sekitar 2,3× ukuran paket untuk setiap unggahan yang berjalan
  bersamaan.
- Batas 64 MB sulit dinaikkan.
- Belum ada jalan untuk feed dependency (NuGet) bagi activity.

**Usulan.** Klien lama tidak terputus.
1. Tambahkan unggahan biner di `POST /api/packages`, berupa
   `multipart/form-data` (berkas + metadata) atau `application/octet-stream`
   + header. Jalur JSON base64 tetap ada untuk Studio lama. Umumkan
   kemampuannya di `HealthResponse` capabilities, misalnya
   `packages.upload.binary`.
2. Unduhan agent sudah biner, jadi tidak berubah.
3. Feed dependency (NuGet v3 read-only per tenant) sebaiknya dibahas terpisah.
   Rancangannya perlu dibuat bersama, karena sisi robot belum punya pemecah
   dependency.

**Sisi robot.** Studio Publish akan memakai jalur biner bila capability-nya
ada, dan tetap memakai base64 bila tidak. Belum dikerjakan; menunggu keputusan
tim.
