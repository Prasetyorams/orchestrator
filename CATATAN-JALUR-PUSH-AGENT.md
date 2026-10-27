# Catatan: jalur push ke Robot Agent (job baru & Stop/Kill tanpa menunggu polling)

Dari tim robot (OpenStudio / Robot Agent). Diperiksa di `17903d3` dari dokumen
`ROBOT-API.md` dan kode agent kita. Hanya dokumentasi; tidak ada perubahan kode di
repo ini, dan **tidak mendesak**: kontrak sekarang sudah berjalan.

## Latar

Di matriks perbandingan dengan UiPath (butir U-12b), robot UiPath menerima job dan
perintah dari Orchestrator lewat koneksi tetap (SignalR), jadi job mulai dan Stop
sampai hampir seketika. Di Open Orchestrator semuanya polling:

| Arah | Sekarang | Jeda terburuk |
|---|---|---|
| Job baru → agent | agent `POST /api/agent/jobs/claim` tiap 3 dtk selama ada robot menganggur (setelan agent `ClaimSeconds`, 2–60; 0 = hanya saat denyut) | ±3 dtk |
| Stop/Kill → agent | `commands` di jawaban `POST /api/agent/heartbeat` (`heartbeat.commands`) | `heartbeatBusySeconds` (5 dtk) |
| Setelan baru | `settingsVersion` di jawaban denyut | `heartbeatSeconds` (15 dtk) |

Biaya polling: tiap mesin yang menganggur mengirim ±20 klaim + 4 denyut per menit,
walau tidak ada job. Dengan puluhan mesin, sebagian besar permintaan ke server
berisi jawaban kosong (`204`).

Pada 29 Sep kami menjawab pertanyaan no. 11 di `PERTANYAAN-UNATTENDED.md` bahwa
perintah lewat jawaban denyut sudah cukup, tanpa WebSocket/SignalR. Itu tetap
berlaku sebagai dasar. Catatan ini mengusulkan jalur push sebagai **tambahan
opsional**: lebih cepat dan lebih sedikit permintaan kosong, tanpa mengubah
klaim, state, dan denyut yang sudah ada.

## Usulan (urut dari yang paling ringan)

**A. Klaim long-poll** (disarankan)
`POST /api/agent/jobs/claim` menerima `waitSeconds` (mis. ≤ 25). Kalau belum ada
job, server menahan jawaban sampai job masuk antrean robot itu atau waktunya habis
(`204`). Di Spring: `DeferredResult`, dibangunkan saat job dibuat atau dijadwalkan.
- Tembus NAT, proxy, dan inspeksi TLS di jaringan bank (tetap HTTP biasa).
- Agent lama tidak terpengaruh: tanpa `waitSeconds`, perilaku sama seperti sekarang.
- Penanda kemampuan, mis. `jobs.claim.wait`.

**B. Aliran petunjuk (SSE)**
`GET /api/agent/events` (`text/event-stream`) mengirim **petunjuk** saja:
`jobAvailable {robotId}`, `command {jobId}`, `settingsChanged`. Agent tetap
mengambil job lewat klaim dan perintah lewat denyut, jadi aliran yang putus hanya
memperlambat, tidak menghilangkan apa pun. Penanda kemampuan, mis. `agent.events`.

**C. WebSocket / STOMP**
Paling cepat dua arah, tetapi paling berat: perlu dukungan upgrade di
reverse proxy/load balancer, dan proxy perusahaan kadang memblokirnya. Kami tidak
butuh arah agent → server lewat jalur ini (laporan state dan log tetap HTTP dengan
outbox).

## Yang kami minta

1. Apakah tim orchestrator bersedia menambah A (atau B), dan kira-kira kapan?
2. Batas koneksi yang ditahan per server (mis. ratusan agent × 1 permintaan
   tertahan) — apakah perlu batas `waitSeconds` atau jumlah koneksi?
3. Kalau ada kendala proxy di jaringan tujuan (bank), mohon dikabari, supaya kami
   tidak memilih jalur yang tidak tembus.

Sisi agent menyesuaikan begitu penanda kemampuan muncul di `capabilities` `GET /api/health`:
dengan `jobs.claim.wait`, klaim di sela denyut diganti satu klaim tertahan, dan
polling 3 dtk tetap jadi jalan mundur kalau kemampuan itu tidak ada atau koneksinya
gagal.
