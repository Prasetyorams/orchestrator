# Kontrak API Robot ↔ Open Orchestrator

Status: **DRAF untuk direview sisi robot.** Belum ada yang diimplementasikan
dari bagian v2. Dasar keputusan: jawaban di `PERTANYAAN-UNATTENDED.md`
(repo Studio, cabang `claude/charming-carson-joglwt`, 29 September 2026).

Dokumen ini punya dua bagian:

- **v1** — API yang berlaku sekarang, dipakai JakRunner (attended), Studio,
  dan activities `Custom.Orchestrator`. Tetap didukung selama JakRunner
  attended masih dipakai.
- **v2** — API baru untuk Robot Agent unattended (layanan Windows, satu per
  mesin). Seluruhnya di bawah `/api/agent/`. Tidak mengubah perilaku v1.

Konvensi untuk semua endpoint:

- JSON, UTF-8, nama medan camelCase.
- Waktu dalam ISO-8601 UTC (`2026-09-29T08:15:00Z`), kecuali disebut lain.
- Kesalahan selalu `{"error": "pesan"}` dengan status HTTP 4xx/5xx. Pesannya
  berbahasa Indonesia dan boleh ditampilkan ke pengguna; agent sebaiknya
  bercabang berdasarkan **status HTTP dan `errorCode`**, bukan teks pesan.
- `401` = token tidak ada/kedaluwarsa → login ulang lalu ulangi sekali.
  `403` = token sah tetapi tidak berhak → jangan diulang.

---

# Bagian 1 — v1 (berlaku sekarang)

Semua memakai `Authorization: Bearer <token>` dari login akun pengguna.

### `POST /api/auth/login`

```json
{ "username": "Robot_Pras", "password": "..." }
```
→ `200 {"token": "...", "expiresInMinutes": 480, "userId": "...", ...}`.
Token berlaku 8 jam (setelan `JWT_EXPIRATION_MINUTES`).

### `POST /api/robots/{robot}/heartbeat`

```json
{ "status": "AVAILABLE", "cpuPercent": 3.5, "memoryMb": 210, "machineName": "PC-01" }
```
→ `200 {"ok": true, "serverTime": "..."}`.

- `status`: `AVAILABLE` atau `BUSY`; lainnya dianggap `AVAILABLE`.
- Robot yang belum ada **terdaftar otomatis**, begitu pula mesinnya.
- Robot yang diam lebih dari 45 detik tampil `DISCONNECTED`, dan job
  `RUNNING`-nya ditandai `FAULTED`.

### `GET /api/jobs/next?robot={robot}`

→ `200 {"job": null}` atau
```json
{ "job": { "id": "uuid", "processName": "Tagihan", "robotName": "Robot_Pras",
           "state": "RUNNING", "priority": "Normal", "inputJson": "{\"a\":1}",
           "createdAt": "...", "startedAt": "..." } }
```
Atomik: dua robot yang bertanya bersamaan tidak mendapat job yang sama. Job
langsung menjadi `RUNNING`. Robot hanya mendapat job yang menyebut namanya,
atau job tanpa robot di folder tempat robot itu ditugaskan. Urutan: prioritas
(`High`, `Normal`, `Low`), lalu yang paling lama menunggu.

### `POST /api/jobs/{id}/state`

```json
{ "state": "SUCCESSFUL", "progress": 100, "info": "Selesai.", "outputJson": "{...}" }
```
- `state`: `PENDING`, `RUNNING`, `SUCCESSFUL`, `FAULTED`, `STOPPED`, `STOPPING`.
- Medan yang tidak dikirim tidak menghapus nilai lama.
- State akhir selalu progress 100.
- **Keterbatasan v1:** tidak ada pemeriksaan pemilik job maupun urutan state.

### `POST /api/logs`

```json
{ "lines": [ { "level": "INFO", "message": "...", "robotName": "...", "machineName": "...",
               "processName": "...", "jobId": "uuid", "loggedAt": "2026-09-29T15:15:00+07:00" } ] }
```

### Endpoint lain yang dipakai Studio dan activities

| Endpoint | Dipakai oleh |
|---|---|
| `GET /api/packages`, `POST /api/packages`, `GET /api/processes`, `POST /api/jobs` | Studio (Terbitkan, Start Job) |
| `GET /api/jobs/{id}` | Activity Start Job (menunggu hasil) |
| `GET /api/assets/{nama}/value`, `GET /api/credentials/{nama}/value` | Activity Get Asset / Get Credential |
| `POST /api/queues/{q}/items`, `POST /api/queues/{q}/next`, `POST /api/queues/items/{id}/result` | Activity antrean |
| `GET /api/packages/{nama}/{versi}/content` | Unduh paket (belum dipakai robot) |

---

# Bagian 2 — v2 (usulan, untuk Robot Agent unattended)

## 2.1 Gambaran

```
Admin (dasbor)                          Robot Agent (layanan Windows, LocalSystem)
  │ buat Mesin + machine key  ─────────►  dipasang sekali saat instalasi
  │ buat Robot unattended                  (DPAPI LocalMachine, %ProgramData%\JakForge\)
  │   + akun Windows (DOMAIN\user)
  ▼                                        │
Open Orchestrator ◄──── login (machine key) ┤
                  ◄──── heartbeat 15 s / 5 s ┤  ──► jawaban: perintah Stop/Kill, setelan
                  ◄──── klaim job per robot  ┤  ──► job + paket + lease + executorToken
                  ◄──── akun Windows (per job)┤     (hanya untuk job yang sedang disiapkan)
                  ◄──── laporan state (idempoten, dari outbox)
                  ◄──── log (seq), screenshot
                                           │
                                           ▼
                                  Sesi Windows (RDP loopback)
                                           │
                                  Executor (1 per sesi/job) ──► activities memakai executorToken
```

Pembagian tanggung jawab:

- **Orchestrator** memutuskan *apa* dan *kapan*: job mana untuk robot mana,
  batas waktu, retry, dan kapan job dianggap hilang atau gagal.
- **Agent** memutuskan *bagaimana*: menyiapkan sesi, menjalankan dan
  menghentikan Executor, menyimpan laporan sampai terkirim.

## 2.2 Identitas dan autentikasi

### Mesin dan machine key

1. Admin membuat **Mesin** di dasbor (Tenant → Robots → Machines), lalu
   menekan **Buat kunci**. Kunci ditampilkan **sekali**:
   `oo_mk_` + 43 karakter acak (256 bit, base64url).
2. Orchestrator hanya menyimpan **hash SHA-256** kunci itu. Kunci yang hilang
   tidak bisa ditampilkan lagi; buat kunci baru (kunci lama langsung mati).
3. Kunci dimasukkan saat instalasi agent (UI installer atau parameter baris
   perintah untuk pemasangan massal).

### Robot unattended

Admin membuat robot bertipe **Unattended**, terikat ke **satu mesin**, dengan:

| Medan | Keterangan |
|---|---|
| `name` | Nama tampilan, unik per tenant |
| `machine` | Mesin tempat robot berjalan |
| `windowsUsername` | `DOMAIN\user` atau `.\user` (akun lokal) |
| `windowsPassword` | **Hanya bisa ditulis.** Disandikan dengan SecretBox; tidak pernah ditampilkan di dasbor, log, audit, maupun API lain |
| `sessionPolicy` | Setelah job: `Logoff` (bawaan) atau `KeepLoggedIn` |
| folder | Seperti robot v1: robot hanya mengambil job tanpa robot dari folder tempat ia ditugaskan |

Satu akun Windows hanya boleh dipakai satu robot di satu mesin.

### `POST /api/agent/login`

Tanpa header Authorization.

```json
{ "machineKey": "oo_mk_…", "machineName": "VM-ROBOT-01", "agentVersion": "1.0.0",
  "os": "Windows Server 2022 21H2" }
```

→ `200`
```json
{
  "token": "eyJ…",
  "expiresAt": "2026-09-29T09:15:00Z",
  "machine": { "id": "uuid", "name": "VM-ROBOT-01", "slots": 2 },
  "robots": [
    { "id": "uuid", "name": "Robot_A", "windowsUsername": "CORP\\robot.a",
      "sessionPolicy": "Logoff" }
  ],
  "settings": {
    "heartbeatSeconds": 15, "heartbeatBusySeconds": 5,
    "leaseSeconds": 180, "minAgentVersion": "1.0.0"
  }
}
```

- Token berlaku **1 jam**. Agent login ulang saat menerima `401` atau
  sebelum `expiresAt`.
- `machineName` dicatat dan dibandingkan dengan nama mesin; kalau berbeda,
  login tetap berhasil tetapi dasbor menampilkan peringatan (kunci mungkin
  disalin ke mesin lain).
- `401 {"error": "...", "errorCode": "InvalidMachineKey"}` untuk kunci salah
  atau dicabut. Agent **tidak boleh** mencoba ulang dalam putaran cepat:
  jeda 5 menit, lalu tampilkan di log layanan.
- `426 {"errorCode": "AgentTooOld", "minAgentVersion": "1.2.0"}` kalau versi
  agent di bawah minimum.

Token agent **hanya berlaku di `/api/agent/**`** dan untuk mengunduh paket.
Ia tidak bisa memanggil endpoint dasbor.

## 2.3 Heartbeat

### `POST /api/agent/heartbeat`

Satu per agent, mencakup semua robot di mesin. Jeda `heartbeatSeconds` (15)
saat idle, `heartbeatBusySeconds` (5) selama ada job yang berjalan. Heartbeat
pertama dikirim segera setelah login.

```json
{
  "agentVersion": "1.0.0",
  "machine": { "cpuPercent": 12.5, "memoryUsedMb": 5120, "memoryTotalMb": 16384 },
  "robots": [
    {
      "robotId": "uuid",
      "state": "Busy",
      "session": { "id": 3, "state": "Active", "windowsUser": "CORP\\robot.a" },
      "executor": { "state": "Running", "pid": 8124 },
      "runningJobIds": ["uuid"]
    },
    {
      "robotId": "uuid",
      "state": "Idle",
      "session": { "id": null, "state": "None" },
      "executor": { "state": "Stopped", "pid": null },
      "runningJobIds": []
    }
  ]
}
```

| Medan | Nilai |
|---|---|
| `robots[].state` | `Idle`, `Busy`, `Error` (agent tidak bisa melayani robot ini, misalnya akun Windows ditolak) |
| `session.state` | `Active`, `Locked`, `Disconnected`, `None` (dari WTS API) |
| `executor.state` | `Starting`, `Running`, `Stopping`, `Stopped` |
| `runningJobIds` | Job yang **benar-benar** masih dijalankan agent untuk robot itu, dari catatan lokal agent |

→ `200`
```json
{
  "serverTime": "2026-09-29T08:15:05Z",
  "commands": [
    { "type": "StopJob", "jobId": "uuid", "graceSeconds": 30 },
    { "type": "KillJob", "jobId": "uuid" }
  ],
  "settingsVersion": 7
}
```

Aturan:

- **Perintah diturunkan dari keadaan job, bukan antrean pesan.** Selama job
  masih `STOPPING`, `StopJob` dikirim di setiap heartbeat; sesudah
  `graceSeconds` lewat, yang dikirim `KillJob`. Karena itu perintah tidak
  perlu dikonfirmasi (ack), dan perintah yang hilang di jalan terkirim lagi
  di heartbeat berikutnya. Agent harus memperlakukan perintah yang sama
  berulang sebagai satu perintah.
- `settingsVersion` naik setiap admin mengubah mesin atau robotnya. Agent
  yang melihat angka berbeda memanggil `POST /api/agent/login` lagi untuk
  mengambil setelan baru.
- Robot yang tidak ada di laporan, atau bukan milik mesin ini, diabaikan.
- **Rekonsiliasi:** job yang di Orchestrator tercatat `RUNNING`,
  `PREPARING_SESSION`, atau `UNRESPONSIVE` untuk robot di mesin ini tetapi
  **tidak** ada di `runningJobIds` ditandai `FAULTED` dengan kode
  `AgentRestarted`. Ini berlaku sejak heartbeat pertama setelah login, jadi
  agent yang baru menyala wajib mengisi `runningJobIds` dengan benar
  (kosong kalau memang tidak ada).

Status yang tampil di dasbor:

| Dasbor | Syarat |
|---|---|
| Online / Offline | Heartbeat terakhir ≤ 60 detik / lebih |
| Idle / Busy | `robots[].state` |
| Session Ready | `session.state` = `Active`, atau `None` (sesi akan dibuat saat job datang) |
| Executor Running | `executor.state` = `Running` |

## 2.4 Siklus hidup job

### State

```
PENDING ──klaim──► ASSIGNED ──► PREPARING_SESSION ──► RUNNING ──► SUCCESSFUL
   │                  │                │                 │   └──► FAULTED
   │                  │                │                 └──────► STOPPED
   │                  └────────────────┴───── gagal ───────────► FAULTED
   └── stop sebelum diambil ──► STOPPED

RUNNING / ASSIGNED / PREPARING_SESSION ──stop──► STOPPING ──► STOPPED (atau SUCCESSFUL/FAULTED
                                                                        kalau selesai duluan)
RUNNING ──heartbeat hilang 60 s──► UNRESPONSIVE ──agent kembali, job disebut──► RUNNING
                                        │──agent kembali, job tak disebut──► FAULTED (AgentRestarted)
                                        └──5 menit──────────────────────────► FAULTED (AgentLost)
```

| State | Artinya | Siapa yang mengubah |
|---|---|---|
| `PENDING` | Menunggu robot | Orchestrator |
| `ASSIGNED` | Diambil agent, lease berjalan | Orchestrator (saat klaim) |
| `PREPARING_SESSION` | Agent sedang login Windows / menyalakan Executor | Agent |
| `RUNNING` | Workflow berjalan | Agent |
| `STOPPING` | Diminta berhenti, menunggu agent | Orchestrator (tombol Stop) |
| `UNRESPONSIVE` | Hilang kontak; belum dianggap gagal | Orchestrator |
| `SUCCESSFUL`, `FAULTED`, `STOPPED` | **Akhir.** Tidak bisa diubah lagi | Agent, atau Orchestrator untuk kegagalan yang ia simpulkan |

State v1 (`PENDING`, `RUNNING`, `SUCCESSFUL`, `FAULTED`, `STOPPED`,
`STOPPING`) tidak berubah artinya; JakRunner v1 tidak akan pernah melihat
state baru.

### `POST /api/agent/jobs/claim`

```json
{ "robotId": "uuid" }
```

→ `204` kalau tidak ada job, atau `200`:
```json
{
  "job": {
    "id": "uuid",
    "attempt": 1,
    "processName": "Tagihan",
    "package": { "name": "Tagihan", "version": "1.0.3",
                 "sha256": "9f2c…", "sizeBytes": 48213,
                 "url": "/api/packages/Tagihan/1.0.3/content" },
    "entryPoint": "Main.xaml",
    "inputJson": "{\"bulan\":\"09\"}",
    "leaseExpiresAt": "2026-09-29T08:18:05Z",
    "timeoutSeconds": 3600,
    "stopGraceSeconds": 30,
    "sessionPolicy": "Logoff",
    "executorToken": "eyJ…"
  }
}
```

- Atomik seperti v1. Aturan pemilihan job sama dengan v1 (nama robot atau
  folder, prioritas, umur), ditambah: robot harus milik mesin agent, mesin
  tidak sedang memakai semua `slots`-nya, dan robot sedang `Idle`.
- `timeoutSeconds` dan `stopGraceSeconds` diambil dari setelan proses;
  `null` = tanpa batas waktu.
- **`executorToken`** diberikan ke Executor (lewat pipa bernama atau berkas
  sementara ber-ACL, **bukan** argumen baris perintah atau variabel
  lingkungan). Activities memakainya untuk endpoint v1 (aset, kredensial,
  antrean, Start Job) atas nama robot itu. Token ini berlaku sampai job
  mencapai state akhir, maksimal `timeoutSeconds` + 1 jam.
- Paket diunduh dengan token agent. Agent **wajib** memeriksa `sha256`
  sebelum memakainya dan menyimpannya di cache `packages\{nama}\{versi}\`.
- Selama `ASSIGNED` dan `PREPARING_SESSION`, lease diperpanjang setiap kali
  agent mengirim laporan state (atau heartbeat yang menyebut job itu).
  Lease yang habis → `FAULTED` dengan kode `LeaseExpired`.

### `POST /api/agent/jobs/{id}/state`

```json
{
  "seq": 3,
  "state": "RUNNING",
  "at": "2026-09-29T08:16:10Z",
  "progress": 10,
  "info": "Workflow dimulai.",
  "context": { "sessionId": 3, "windowsUser": "CORP\\robot.a", "executorPid": 8124 }
}
```

Laporan akhir:
```json
{
  "seq": 9,
  "state": "FAULTED",
  "at": "2026-09-29T08:40:02Z",
  "errorCode": "WorkflowFailed",
  "info": "Elemen 'Simpan' tidak ditemukan dalam 30 detik.",
  "outputJson": null
}
```

→ `200 {"state": "RUNNING", "stopRequested": false}`, berisi keadaan job
**setelah** laporan diterapkan.

Aturan:

- **Idempoten.** `seq` naik per job, mulai 1. Laporan dengan `seq` yang sudah
  diterima dijawab `200` tanpa efek. Ini yang membuat outbox agent aman
  mengirim ulang.
- **Urutan dijaga.** Transisi yang tidak ada di diagram dijawab
  `409 {"errorCode": "InvalidTransition", "state": "<state sekarang>"}`.
  Agent membuang laporan itu dari outbox (jangan diulang) dan menyesuaikan
  diri dengan `state` di jawaban.
- **State akhir menang pertama.** Setelah `SUCCESSFUL`, `FAULTED`, atau
  `STOPPED`, laporan apa pun dijawab `409`.
- Kalau job sedang `STOPPING`, agent tetap boleh melaporkan `RUNNING`
  (misalnya laporan lama dari outbox) tanpa membatalkan permintaan stop;
  jawabannya `stopRequested: true`.
- `outputJson`: objek nama argumen Out/InOut → nilai, maksimal **1 MB**,
  hanya bersama state akhir.
- `403` kalau job bukan milik robot di mesin ini.

### Kode kegagalan (`errorCode`)

| Kode | Arti | Retry otomatis |
|---|---|---|
| `SessionPreparationFailed` | Sesi Windows tidak bisa disiapkan | Ya |
| `LogonFailed` | Login Windows ditolak (sandi salah, akun terkunci/kedaluwarsa) | **Tidak** — mengulang hanya mengunci akun |
| `ExecutorStartFailed` | Executor tidak mau menyala | Ya |
| `ExecutorCrashed` | Executor mati di tengah jalan | Ya |
| `PackageNotFound` | Paket/versi tidak ada | Tidak |
| `PackageDownloadFailed` | Unduhan gagal atau hash tidak cocok | Ya |
| `WorkflowLoadFailed` | XAML rusak / tipe activity tidak dikenal | Tidak |
| `WorkflowFailed` | Workflow melempar kesalahan | Tidak |
| `Timeout` | Melewati `timeoutSeconds` | Tidak |
| `AgentRestarted` | *(Orchestrator)* Agent kembali tanpa menyebut job ini | Ya |
| `AgentLost` | *(Orchestrator)* Tidak ada kabar 5 menit | Ya |
| `LeaseExpired` | *(Orchestrator)* Tidak ada kabar selama penyiapan | Ya |

Job yang dihentikan dengan Stop berakhir `STOPPED` tanpa `errorCode`.

### Retry otomatis

- Hanya untuk kode bertanda "Ya", dan hanya kalau proses mengizinkannya
  (`maxRetries`, bawaan **1**, maksimal 2; 0 = mati).
- Retry = **job baru** dengan `attempt` + 1 dan `retryOf` menunjuk job lama.
  Job lama tetap `FAULTED`, supaya riwayatnya jujur.
- Kalau job asli tidak menyebut robot, retry menghindari robot yang baru
  gagal selama masih ada robot lain di folder itu.
- Orchestrator yang membuat retry; agent **tidak pernah** membuat job.

### Stop, kill, dan timeout

1. Tombol Stop di dasbor: job → `STOPPING`, mulai hitung `stopGraceSeconds`.
2. Heartbeat berikutnya membawa `StopJob`: agent memanggil Cancel, lalu
   Terminate.
3. Setelah jeda lewat dan job belum berakhir: `KillJob` → agent mematikan
   proses Executor dan melaporkan `STOPPED`.
4. Timeout dijalankan **oleh agent** (ia yang tahu kapan workflow mulai):
   urutan yang sama, lalu lapor `FAULTED` + `Timeout`. Orchestrator hanya
   menjadi jaring pengaman: job `RUNNING` yang melewati
   `timeoutSeconds + stopGraceSeconds + 5 menit` diberi `StopJob`.

## 2.5 Akun Windows

### `POST /api/agent/jobs/{id}/windows-credential`

Tanpa badan. `POST`, bukan `GET`, supaya tidak tersimpan di cache atau log
proxy.

→ `200`
```json
{ "username": "CORP\\robot.a", "password": "…" }
```

- Hanya dijawab kalau job milik robot di mesin ini **dan** sedang `ASSIGNED`
  atau `PREPARING_SESSION`. Selain itu `403`.
- Setiap pemanggilan dicatat di audit (mesin, robot, job, waktu) — **tanpa**
  sandinya.
- Jawaban memakai `Cache-Control: no-store`.
- Agent membuang sandi dari memori segera setelah login Windows selesai.
- Kalau login Windows gagal karena sandi, agent melaporkan `LogonFailed`;
  dasbor menandai robot itu **Perlu perhatian** sampai admin mengganti
  sandinya.

## 2.6 Log dan lampiran

### `POST /api/agent/logs`

```json
{
  "lines": [
    { "jobId": "uuid", "robotId": "uuid", "seq": 41, "level": "INFO",
      "source": "Executor/Click", "sessionId": 3,
      "message": "Klik 'Simpan'.", "loggedAt": "2026-09-29T15:16:11+07:00" }
  ]
}
```

- `level`: `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE`.
- `source`: `Agent` atau `Executor/<nama activity>`.
- `seq` naik per job; baris dengan `(jobId, seq)` yang sudah ada diabaikan,
  jadi pengiriman ulang dari outbox aman. Baris agent yang tidak terkait
  job memakai `jobId: null` dan tidak dicek duplikatnya.
- Maksimal 500 baris per permintaan, 8 KB per pesan (lebih panjang
  dipotong).
- **Tidak boleh ada sandi atau token di pesan.** Orchestrator juga menyensor
  nilai yang tampak seperti token (`eyJ…`, `oo_mk_…`), tetapi itu jaring
  pengaman, bukan izin.

### `POST /api/agent/jobs/{id}/attachments`

`multipart/form-data`, medan `file`, dengan `kind=Screenshot`.
Maksimal **2 MB** per berkas dan **5** berkas per job; kelebihannya `413`.
Hanya PNG/JPEG. Tampil di rincian job di dasbor.

## 2.7 Waktu dan batas

| Nilai | Bawaan | Disetel di |
|---|---|---|
| Heartbeat idle / sibuk | 15 s / 5 s | Global |
| Robot dianggap Offline | 60 s tanpa heartbeat | Global |
| Job → `UNRESPONSIVE` | 60 s tanpa heartbeat | Global |
| `UNRESPONSIVE` → `FAULTED AgentLost` | 5 menit | Global |
| Lease penyiapan | 180 s | Per mesin |
| `stopGraceSeconds` | 30 s | Per proses |
| `timeoutSeconds` | kosong (tanpa batas) | Per proses |
| `maxRetries` | 1 | Per proses |
| Token agent | 1 jam | — |
| Output / screenshot / log | 1 MB / 2 MB × 5 / 500 baris × 8 KB | — |

## 2.8 Versi

- Setiap permintaan agent membawa `X-Agent-Version: 1.0.0`.
- `GET /api/health` menambah `"apiVersions": [1, 2]`, supaya agent bisa
  memeriksa kecocokan sebelum login.
- Perubahan yang **menambah** medan tidak menaikkan versi; agent wajib
  mengabaikan medan yang tidak ia kenal. Perubahan yang mematahkan kontrak
  pindah ke `/api/agent/v3/…`.

## 2.9 Keamanan — ringkasan

| Rahasia | Di Orchestrator | Di mesin robot |
|---|---|---|
| Machine key | Hash SHA-256 saja | DPAPI LocalMachine, `%ProgramData%\JakForge\`, ACL SYSTEM + Administrators |
| Token agent / executorToken | Tidak disimpan (JWT) | Memori saja; executorToken ke Executor lewat pipa ber-ACL |
| Sandi Windows robot | SecretBox (kunci `signing.key`), hanya-tulis di dasbor | Memori, hanya selama login Windows |

Orchestrator harus dijalankan di belakang **HTTPS** (lihat `DEPLOY.md`);
tanpa itu machine key dan sandi Windows melintas sebagai teks biasa.

## 2.10 Contoh uji dengan curl

```bash
# login
curl -s -X POST https://api.contoh/api/agent/login -H "Content-Type: application/json" \
  -d '{"machineKey":"oo_mk_…","machineName":"VM-ROBOT-01","agentVersion":"1.0.0"}'

# heartbeat tanpa job
curl -s -X POST https://api.contoh/api/agent/heartbeat -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json" -H "X-Agent-Version: 1.0.0" \
  -d '{"agentVersion":"1.0.0","robots":[{"robotId":"'$R'","state":"Idle","session":{"state":"None"},"executor":{"state":"Stopped"},"runningJobIds":[]}]}'

# klaim, lalu lapor
curl -s -X POST https://api.contoh/api/agent/jobs/claim -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json" -d '{"robotId":"'$R'"}'
curl -s -X POST https://api.contoh/api/agent/jobs/$J/state -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json" -d '{"seq":1,"state":"PREPARING_SESSION","at":"2026-09-29T08:16:00Z"}'
```

---

# Bagian 3 — Yang dikerjakan di Orchestrator

Urutan implementasi (masing-masing diuji sebelum lanjut):

1. **Mesin, machine key, robot unattended**: kolom baru, dasbor Machines dan
   Robots (buat kunci, akun Windows hanya-tulis, sessionPolicy),
   `POST /api/agent/login`, token agent terbatas.
2. **Heartbeat v2**: status per robot/sesi/executor, Online/Offline,
   rekonsiliasi `runningJobIds`, perintah Stop/Kill.
3. **Job v2**: state baru, klaim + lease, laporan idempoten dengan `seq`,
   aturan transisi, konteks eksekusi, kode gagal, `UNRESPONSIVE`.
4. **Setelan proses**: timeout, stopGrace, maxRetries; retry otomatis.
5. **Akun Windows per job** + audit.
6. **Log v2** (seq, source, sessionId, sensor rahasia) dan lampiran.
7. **executorToken** untuk activities.
8. **Dasbor**: status mesin/sesi/executor, rincian job (konteks, kode gagal,
   percobaan, screenshot), tombol Stop yang benar-benar menghentikan.

Yang **tidak** berubah: semua endpoint v1 dan perilakunya untuk JakRunner.

# Bagian 4 — Masih terbuka

1. Nama state `UNRESPONSIVE` — atau cukup ditampilkan sebagai `RUNNING`
   dengan tanda "hilang kontak"? (Usulan: state sendiri, supaya bisa
   difilter.)
2. `slots` per mesin: diisi admin, atau dilaporkan agent dari kemampuan
   Windows (RDS)? (Usulan: diisi admin, dibatasi oleh jumlah robot di mesin
   itu.)
3. Apakah JakRunner attended nanti ikut pindah ke v2 dengan jenis kunci lain
   (misalnya "kunci pengguna"), atau tetap v1 selamanya?
4. Jadwal dan alamat jaringan untuk uji VMware.
