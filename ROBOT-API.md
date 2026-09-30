# Kontrak API Robot ↔ Open Orchestrator

Status: **v2 sudah diimplementasikan di Orchestrator** (migrasi `V8__agent_unattended.sql`),
dengan semua perubahan wajib W1–W6 dan saran dari
`docs/ROBOT-API-tanggapan-robot.md` di repo Studio. Sudah diuji ujung-ke-ujung dengan
agent tiruan melawan PostgreSQL sungguhan (83 pemeriksaan). Belum diuji melawan
Robot Agent sungguhan di VM.

**Tambahan V9** (`V9__runtime_dan_aksi_job.sql`): jeda/lanjut dari dasbor — usulan sisi
robot `docs/usulan-pause-job.md` di repo Studio, dipakai apa adanya untuk v1 — KillJob untuk
job yang diminta **dimatikan paksa**, dan **tipe runtime** mesin yang menentukan robot mana
yang boleh mengambil sebuah job. Semuanya menambah, tidak mengubah yang sudah ada; diuji
ujung-ke-ujung (103 pemeriksaan) dan dengan regresi v1.

Dokumen ini punya dua bagian:

- **v1** — API untuk JakRunner (attended), Studio, activities `Custom.Orchestrator`, dan
  Robot Agent yang masuk dengan akun pengguna. Tetap didukung.
- **v2** — API untuk Robot Agent unattended (layanan Windows, satu per mesin), di bawah
  `/api/agent/`, masuk dengan **machine key**. Tidak mengubah perilaku v1.

Konvensi untuk semua endpoint:

- JSON, UTF-8, nama medan camelCase. Waktu dalam ISO-8601 UTC.
- Kesalahan selalu `{"error": "pesan"}`; untuk yang perlu dibedakan mesin ada juga
  `errorCode` (dan kadang `state` atau `minAgentVersion`). Agent bercabang berdasarkan
  **status HTTP dan `errorCode`**, bukan teks pesan.
- `401` = token tidak ada, kedaluwarsa, atau sudah dicabut → login ulang lalu ulangi sekali.
  `403` = token sah tetapi tidak berhak → jangan diulang.
- Medan yang tidak dikenal di jawaban wajib diabaikan: Orchestrator boleh menambah medan
  tanpa menaikkan versi.
- **Jenis perintah yang tidak dikenal wajib diabaikan** (`commands` di jawaban denyut v1 dan
  v2). Orchestrator boleh mengirim jenis baru; robot yang belum mengenalnya tidak boleh gagal
  karenanya.

---

# Bagian 1 — v1

Semua memakai `Authorization: Bearer <token>` dari login akun pengguna.

### `GET /api/health` (tanpa token)

```json
{ "product": "OpenOrchestrator", "status": "OK", "time": "…",
  "contract": 2,
  "capabilities": ["jobs.next.package", "packages.sha256", "heartbeat.commands", "jobs.state.guard",
                   "jobs.pause", "jobs.kill"],
  "apiVersions": [1, 2] }
```

`contract` + `capabilities` = kemampuan tambahan v1 (lihat di bawah); `apiVersions` berisi 2
kalau `/api/agent` tersedia.

### `POST /api/auth/login`

`{ "username": "...", "password": "..." }` → `200 {"token": "...", "expiresInMinutes": 480, ...}`.

### `POST /api/robots/{robot}/heartbeat`

```json
{ "status": "BUSY", "cpuPercent": 3.5, "memoryMb": 210, "machineName": "PC-01",
  "pausedJobId": "…", "pauseSource": "dashboard" }
```
→ `200 {"ok": true, "serverTime": "…", "commands": [{"type": "StopJob", "jobId": "…"}]}`

- **heartbeat.commands:** perintah untuk job robot ini, DITURUNKAN dari keadaan job di
  setiap denyut — diulang selama syaratnya berlaku, tanpa tanda terima; robot aman
  menerimanya berkali-kali.

  | Keadaan job di server | Dilaporkan robot | Perintah |
  |---|---|---|
  | `STOPPING` | | `StopJob` |
  | `STOPPING`, diminta dimatikan paksa | | `StopJob` **dan** `KillJob` |
  | `RUNNING`, dasbor meminta jeda | `pausedJobId` bukan job ini | `PauseJob` |
  | `RUNNING`, jeda dari dasbor dibatalkan | `pausedJobId` = job ini, `pauseSource` = `dashboard` | `ResumeJob` |

  - **jobs.kill:** `KillJob` = hentikan SEKARANG, tanpa jeda berhenti rapi. Dikirim BERSAMA
    `StopJob`, jadi robot yang belum mengenal `KillJob` tetap berhenti rapi.
  - **jobs.pause:** `pausedJobId` = job yang sedang BENAR-BENAR ditahan robot (tidak ada
    medannya = tidak ada yang dijeda); `pauseSource` = `dashboard` (karena `PauseJob`) atau
    `local` (tombol Jeda di PC robot; nilai lain dianggap `local`). Dasbor menampilkan job itu
    **Dijeda**; job tetap `RUNNING` dan robot tetap `BUSY`. Jeda **lokal** tidak pernah
    dilanjutkan server — yang di depan PC itu yang memutuskan. Stop mengalahkan jeda: job
    `STOPPING` hanya mendapat `StopJob`. Lama jeda dicatat per job.
- Robot yang belum ada terdaftar otomatis, dengan akun yang dipakainya masuk sebagai
  pemiliknya (dipakai Start Job untuk "jalankan sebagai diri sendiri"). Robot yang diam lebih
  dari 45 detik tampil `DISCONNECTED`; job `RUNNING`-nya ditandai `FAULTED` sesudah 90 detik,
  dan job `STOPPING`-nya `STOPPED` — keduanya **kesimpulan** server, yang masih bisa
  digantikan laporan akhir robot itu.

### `GET /api/jobs/next?robot={robot}`

→ `200 {"job": null}` atau
```json
{ "job": { "id": "…", "processName": "Tagihan", "robotName": "Robot_Pras", "state": "RUNNING",
           "priority": "Normal", "inputJson": "{\"a\":1}", "createdAt": "…", "startedAt": "…",
           "folderId": "…", "packageName": "Tagihan", "packageVersion": "1.0.3",
           "entryPoint": "Main.xaml", "packageSha256": "9f2c…" } }
```

- **jobs.next.package:** paket, versi, titik masuk, dan folder langsung disebut — robot tidak
  perlu membaca daftar proses dan paket.
- Atomik (`FOR UPDATE SKIP LOCKED`). Job hanya diambil robot yang disebutnya, atau robot yang
  ditugaskan ke folder job itu. Urutan: prioritas, lalu umur.
- Sejak V9, job yang dibuat dari Start Job bisa meminta **mesin** dan **tipe runtime**
  (lihat 2.9). Robot hanya mengambil job untuk mesinnya sendiri (mesin dari denyutnya), dan
  job bertipe runtime hanya kalau mesinnya punya runtime tipe itu. Job tanpa keduanya —
  dari Studio, pemicu, API lama — diambil seperti sebelumnya.

### `POST /api/jobs/{id}/state`

`{ "state": "SUCCESSFUL", "progress": 100, "info": "…", "outputJson": "{…}" }` → `200 {"ok": true}`

- `state`: `PENDING`, `RUNNING`, `SUCCESSFUL`, `FAULTED`, `STOPPING`, `STOPPED`.
- **jobs.state.guard:** `409 {"errorCode": "InvalidTransition", "state": "…"}` untuk RUNNING
  sesudah STOPPING, dan untuk laporan apa pun atas job yang sudah selesai — KECUALI
  kegagalannya kesimpulan server (robot sempat diam); itu boleh digantikan laporan robot.
  Laporan akhir yang ditolak tetap dicatat di log job.
- `409 {"errorCode": "AgentJob"}` untuk job yang dijalankan Robot Agent v2.

### `GET /api/packages`, `GET /api/packages/{nama}/{versi}/content`

**packages.sha256:** daftar paket menyebut `sha256` (heksa kecil) isi paketnya.

### Endpoint lain

| Endpoint | Dipakai oleh |
|---|---|
| `POST /api/logs` | JakRunner, agent v1 |
| `POST /api/packages`, `GET /api/processes`, `POST /api/jobs` | Studio (Terbitkan, Start Job) |
| `GET /api/jobs/{id}`, `GET /api/jobs?state=` | Activity Start Job, rekonsiliasi agent v1 |
| `GET /api/assets/{nama}/value`, `GET /api/credentials/{nama}/value` | Activity Get Asset / Credential |
| `POST /api/queues/{q}/items`, `…/next`, `POST /api/queues/items/{id}/result` | Activity antrean |

### Izin akun robot v1

Peran bawaan **Robot**: `robots.update`, `jobs.read`, `jobs.create`, `jobs.update`,
`logs.create`, `assets.read`, `queues.read`, `queues.update`, `buckets.read`,
`buckets.update`, dan sejak V8 juga `processes.read` + `packages.read`.

---

# Bagian 2 — v2 (Robot Agent unattended)

## 2.1 Gambaran

```
Admin (dasbor)                          Robot Agent (layanan Windows, LocalSystem)
  │ Tenant › Robots › Machines:
  │   buat mesin + machine key ────────► dipasang sekali saat instalasi
  │ Tenant › Robots: robot unattended      (DPAPI LocalMachine, %ProgramData%\JakForge\)
  │   = mesin + akun Windows
  ▼                                        │
Open Orchestrator ◄──── login (machine key) ┤
                  ◄──── heartbeat 15 s / 5 s ┤ ──► StopJob / KillJob, settingsVersion
                  ◄──── klaim job per robot  ┤ ──► job + paket + lease + executorToken
                  ◄──── akun Windows (per job)┤    (hanya selama penyiapan)
                  ◄──── laporan state (seq, idempoten, dari outbox)
                  ◄──── log (seq), screenshot
                                           ▼
                                  Sesi Windows → Executor (1 per job) ──► activities pakai executorToken
```

Orchestrator memutuskan *apa* dan *kapan*; agent memutuskan *bagaimana*.

## 2.2 Identitas

### Mesin dan machine key

- Dasbor: **Tenant › Robots › Machines** → *Tambah mesin*, lalu ikon kunci → *Buat machine key*.
  Kunci ditampilkan **sekali**: `oo_mk_` + 43 karakter (256 bit acak, base64url).
- Orchestrator hanya menyimpan **hash SHA-256**-nya. Kunci baru langsung membatalkan kunci lama
  — termasuk token agent yang dibuat dengan kunci lama (`401 MachineKeyRevoked` pada permintaan
  berikutnya, bukan menunggu tokennya kedaluwarsa). *Cabut machine key* ada di dialog *Ubah mesin*.
- Setelan mesin: **slot** (job bersamaan, 1–50) dan **lease penyiapan** (30–3600 detik, bawaan
  180). Slot yang berlaku = min(slot, `maxInteractiveSessions` yang dilaporkan agent).
- Izin dasbor: `machines.update` (baru) untuk kunci, slot, dan lease.

### Robot unattended

Dasbor: **Tenant › Robots** → *Tambah robot* / ikon ubah (izin `robots.create` — bukan
`robots.update`, karena itu izin denyut yang dipegang akun robot).

| Medan | Keterangan |
|---|---|
| Mesin | Robot dilayani agent mesin itu. Robot `Attended` tidak pernah diikat ke mesin |
| Akun Windows | `DOMAIN\user`, atau `.\user` untuk akun lokal |
| Sandi Windows | **Hanya bisa ditulis**, disimpan tersandi (SecretBox). Kosong saat mengubah = tetap |
| Sandi disimpan di mesin robot | W5: Orchestrator hanya menyimpan nama akun; agent memakai sandi lokalnya |
| Sesudah job selesai | `Logoff` (bawaan) atau `KeepLoggedIn` |

Mengubah robot atau mesin menaikkan `settingsVersion` mesin lama dan mesin baru.

### `POST /api/agent/login` (tanpa token)

```json
{ "machineKey": "oo_mk_…", "machineName": "VM-ROBOT-01", "agentVersion": "1.0.0",
  "os": "Windows 11 Pro 25H2", "maxInteractiveSessions": 1 }
```

→ `200` (`Cache-Control: no-store`)
```json
{
  "token": "eyJ…", "expiresAt": "…",
  "machine": { "id": "…", "name": "VM-ROBOT-01", "slots": 1, "leaseSeconds": 180 },
  "robots": [ { "id": "…", "name": "Robot_A", "windowsUsername": ".\\robot",
                "sessionPolicy": "Logoff", "windowsPasswordLocal": false } ],
  "settings": { "heartbeatSeconds": 15, "heartbeatBusySeconds": 5, "leaseSeconds": 180,
                "minAgentVersion": "1.0.0", "settingsVersion": 3, "maxLogLinesPerRequest": 500,
                "maxAttachmentBytes": 2097152, "maxAttachmentsPerJob": 5, "maxOutputBytes": 1048576 }
}
```

| Jawaban | Arti |
|---|---|
| `401 InvalidMachineKey` | Kunci salah, dicabut, atau tidak pernah ada. Jangan diulang cepat — jeda 5 menit |
| `426 AgentTooOld` + `minAgentVersion` | Versi agent di bawah minimum (`OPENORCHESTRATOR_AGENT_MIN_VERSION`) |
| `400 AgentVersionMissing` | `agentVersion` tidak dikirim |

Token berlaku **1 jam**, hanya di `/api/agent/**` dan untuk unduh paket job-nya. Kalau
`machineName` berbeda dari nama mesin dan dari komputer terakhir, dasbor mendapat peringatan
"Machine key dipakai dari komputer lain".

## 2.3 Heartbeat — `POST /api/agent/heartbeat`

Satu per agent. Jeda `heartbeatSeconds` saat idle, `heartbeatBusySeconds` selama ada job.

```json
{
  "agentVersion": "1.0.0",
  "machine": { "cpuPercent": 12.5, "memoryUsedMb": 5120, "memoryTotalMb": 16384 },
  "maxInteractiveSessions": 1,
  "robots": [ {
    "robotId": "…",
    "state": "Busy",
    "session": { "id": 3, "state": "Active", "windowsUser": "VM\\robot", "ready": true },
    "reason": { "code": "SessionLocked", "text": "Sesi terkunci" },
    "executor": { "state": "Running", "pid": 8124 },
    "activeJobIds": ["…"],
    "pausedJobs": [ { "jobId": "…", "source": "dashboard" } ]
  } ]
}
```

| Medan | Arti |
|---|---|
| `state` | `Idle`, `Busy`, `Error` (agent tidak bisa melayani robot ini) |
| `session.ready` | **W6.** Menurut agent: bisakah job dijalankan sekarang. Sesi terkunci tetap `Active` bagi WTS, jadi siap-tidaknya ditentukan agent. `false` = klaim tidak diberi job |
| `reason` | Kode + teks singkat, tampil di dasbor (mis. `SessionLocked`, `NoSession`, `LogonFailed`, `RemoteDesktopDisabled`) |
| `activeJobIds` | **W1.** Job yang belum selesai DILAPORKAN: masih berjalan **atau** laporan akhirnya masih di outbox. `runningJobIds` diterima dengan arti sama. Tanpa medan ini, rekonsiliasi tidak dijalankan |
| `pausedJobs` | *(V9, opsional)* Job robot ini yang sedang BENAR-BENAR ditahan, dengan `source` `dashboard` (karena `PauseJob`) atau `local` (dijeda di PC robot). Daftar kosong = tidak ada yang ditahan. Agent yang belum mendukung jeda tidak mengirim medan ini — ketiadaannya tidak dibaca sebagai "semua sudah dilanjutkan" |

→ `200`
```json
{ "serverTime": "…",
  "commands": [ { "type": "StopJob", "jobId": "…", "graceSeconds": 30 },
                { "type": "KillJob", "jobId": "…" } ],
  "settingsVersion": 3, "heartbeatSeconds": 15, "heartbeatBusySeconds": 5 }
```

Aturan:

- Denyut juga dihitung sebagai denyut setiap robot yang dilaporkan (status Online/Offline).
  Robot yang bukan milik mesin ini dilewati tanpa galat.
- **Perpanjangan lease (W4):** job yang disebut di `activeJobIds` dan belum RUNNING
  lease-nya diperpanjang, sejak ASSIGNED.
- **Rekonsiliasi (W1):** job robot itu yang dipegang menurut Orchestrator tapi tidak disebut →
  `FAULTED AgentRestarted` (atau `STOPPED` kalau sudah diminta berhenti), sebagai kesimpulan.
  Job yang diambil kurang dari **30 detik** lalu dilewati — denyut yang berangkat sebelum
  klaimnya selesai memang belum menyebutnya.
- Job yang disebut padahal sudah selesai di Orchestrator (mis. sudah diulang di robot lain) →
  `StopJob`: dua eksekusi dari pekerjaan yang sama tidak boleh berlanjut.
- **Perintah diturunkan dari keadaan job:** selama job `STOPPING`, `StopJob` dikirim di setiap
  denyut; sesudah `graceSeconds` lewat, `KillJob` (agent mematikan seluruh pohon proses job itu).
  Job yang diminta **Matikan** dari dasbor langsung mendapat `KillJob`, tanpa menunggu
  `graceSeconds`. Tidak perlu ack; perintah yang sama berulang = satu perintah.
- **Jeda (V9):** job `RUNNING` yang diminta dijeda mendapat `PauseJob` sampai agent
  menyebutnya di `pausedJobs`; jeda dari dasbor yang dibatalkan mendapat `ResumeJob` selama
  agent masih menyebutnya dengan `source: "dashboard"`. Jeda `local` tidak pernah dilanjutkan
  server. Job tetap `RUNNING`, robot tetap `Busy`, dan waktu dijeda **tidak** dihitung jaring
  pengaman batas waktu. Agent yang belum mendukung jeda mengabaikan `PauseJob`/`ResumeJob`
  (jenis perintah tak dikenal), dan dasbor menampilkan "menunggu robot menjeda".
- `settingsVersion` berubah → agent login ulang untuk mengambil setelan (robot, slot, lease).

## 2.4 Job

### State

```
PENDING ──klaim──► ASSIGNED ──► PREPARING_SESSION ──► RUNNING ──► SUCCESSFUL / FAULTED / STOPPED
RUNNING/ASSIGNED/PREPARING_SESSION/UNRESPONSIVE ──Stop──► STOPPING ──► STOPPED (atau selesai lebih dulu)
RUNNING/STOPPING ──diam 60 s──► UNRESPONSIVE ──disebut lagi──► RUNNING (STOPPING kalau sempat distop)
                                     └──5 menit──► FAULTED AgentLost (STOPPED kalau sempat distop)
ASSIGNED/PREPARING_SESSION ──lease habis──► FAULTED LeaseExpired
```

`SUCCESSFUL`, `FAULTED`, `STOPPED` = akhir. Kegagalan yang **disimpulkan** Orchestrator
(`AgentRestarted`, `AgentLost`, `LeaseExpired`) ditandai `failureInferred` dan bisa digantikan
laporan asli agent (W2), selama belum ada percobaan ulang. Klien v1 tidak pernah melihat state
baru.

### `POST /api/agent/jobs/claim` — `{ "robotId": "…" }`

→ `204` kalau tidak ada job, atau `200`:
```json
{ "job": {
    "id": "…", "attempt": 1, "processName": "Tagihan", "folderId": "…", "priority": "Normal",
    "runtimeType": "Production",
    "package": { "name": "Tagihan", "version": "1.0.3", "sha256": "9f2c…", "sizeBytes": 48213,
                 "url": "/api/packages/Tagihan/1.0.3/content" },
    "entryPoint": "Main.xaml", "inputJson": "{\"bulan\":\"09\"}",
    "leaseExpiresAt": "…", "timeoutSeconds": 3600, "stopGraceSeconds": 30,
    "sessionPolicy": "Logoff", "windowsPasswordLocal": false, "executorToken": "eyJ…" } }
```

`204` juga kalau: robot sedang memegang job lain (satu robot = satu akun Windows = satu job),
`session.ready` terakhir `false`, `state` `Error`, robot ditandai **Perlu perhatian**
(`LogonFailed`, sampai sandinya diganti), atau slot mesin penuh — per tipe runtime, lihat
2.9. Alasannya terlihat di dasbor. `403 NotYourRobot` kalau robot bukan milik mesin ini.

`runtimeType` (V9) = tipe runtime yang dipakai job ini di mesin ini; informasi saja, agent
tidak perlu berbuat apa-apa dengannya.

Percobaan ulang yang baru dibuat tidak diambil robot yang baru gagal selama **60 detik** —
robot lain didahulukan; sesudah itu siapa pun boleh.

### `POST /api/agent/jobs/{id}/state`

```json
{ "seq": 3, "state": "RUNNING", "progress": 10, "info": "Workflow dimulai.",
  "context": { "sessionId": 3, "windowsUser": "VM\\robot", "executorPid": 8124 } }
```
Laporan akhir: `{ "seq": 9, "state": "FAULTED", "errorCode": "WorkflowFailed", "info": "…",
"outputJson": {…} }` → `200 {"state": "FAULTED", "stopRequested": false}`.

- `state`: `PREPARING_SESSION`, `RUNNING`, `SUCCESSFUL`, `FAULTED`, `STOPPED`.
- **Idempoten:** `seq` naik per job mulai 1. `seq` yang **sama atau lebih kecil** dari yang sudah
  diterima → `200` tanpa efek.
- Laporan akhir diterima dari keadaan mana pun yang belum selesai (laporan RUNNING bisa hilang).
  Laporan antara hanya boleh maju; selama `STOPPING`, laporan antara diterima tapi keadaan
  tetap `STOPPING` dan jawabannya `stopRequested: true`.
- `409 InvalidTransition` + `state` untuk yang tidak sah — agent membuangnya dari outbox.
  Laporan akhir yang terlambat (job sudah selesai dan sudah diulang) tetap **dicatat di log job**.
- `errorCode` disimpan untuk `FAULTED` dan `STOPPED` (mis. `AgentShutdown`).
- `outputJson` (untai atau objek) hanya bersama laporan akhir, maks **1 MB**; yang lebih besar
  dibuang dengan catatan di log, laporannya tetap diterima.
- Sesudah laporan akhir robot langsung **Idle** di server, tanpa menunggu denyut.
- `403 NotYourJob` kalau job bukan milik robot di mesin ini.

### Kode kegagalan

| Kode | Arti | Diulang otomatis |
|---|---|---|
| `SessionPreparationFailed` | Sesi Windows tidak bisa disiapkan | Ya* |
| `LogonFailed` | Login Windows ditolak — robot ditandai **Perlu perhatian** | Tidak |
| `ExecutorStartFailed` | Executor tidak mau menyala | Ya* |
| `ExecutorCrashed` | Executor mati | Ya* |
| `PackageNotFound` | Paket/versi tidak ada | Tidak |
| `PackageDownloadFailed` | Unduhan gagal | Ya* |
| `PackageIntegrityFailed` | Hash tidak cocok | Ya* |
| `WorkflowLoadFailed` | XAML rusak / tipe tidak dikenal | Tidak |
| `WorkflowFailed` | Workflow melempar kesalahan | Tidak |
| `Timeout` | Melewati `timeoutSeconds` | Tidak |
| `AgentShutdown` | Layanan dihentikan saat job berjalan (dengan `STOPPED`) | Tidak |
| `AgentRestarted` | *(Orchestrator)* Agent tidak lagi menyebut job ini | Ya* |
| `AgentLost` | *(Orchestrator)* Tidak ada kabar 5 menit | Ya* |
| `LeaseExpired` | *(Orchestrator)* Tidak ada kabar selama penyiapan | Ya* |

\* **W3:** hanya kalau job **belum pernah RUNNING** — workflow yang sudah berjalan bisa saja
sudah mengirim email atau mengisi data. Jumlah per proses (`maxRetries`, bawaan 1, maks 2, 0 =
mati). Percobaan ulang = job baru dengan `attempt + 1` dan `retryOf`; job lama tetap `FAULTED`.
Robot sasarannya sama dengan permintaan ASLI.

### Stop, kill, batas waktu

1. Dasbor → Hentikan: job `STOPPING`; denyut membawa `StopJob` (Cancel → Terminate di agent).
2. Sesudah `stopGraceSeconds` (setelan proses, bawaan 30): `KillJob`.
3. Dasbor → Matikan (V9): job `STOPPING` dengan permintaan mati paksa; denyut langsung membawa
   `KillJob`.
4. Batas waktu (`timeoutSeconds`, setelan proses) dijalankan **agent** → lapor `FAULTED Timeout`.
   Jaring pengaman: job RUNNING melewati batas + jeda berhenti + 5 menit — tidak termasuk
   waktu dijeda — diminta berhenti Orchestrator.

## 2.5 Akun Windows — `POST /api/agent/jobs/{id}/windows-credential`

→ `200 {"username": "…", "password": "…"}` (`Cache-Control: no-store`), hanya selama job
`ASSIGNED` atau `PREPARING_SESSION`. Setiap pengambilan dicatat di jejak audit (tanpa sandinya).

| Galat | Arti |
|---|---|
| `403 CredentialNotAvailable` | Job sudah lewat penyiapan |
| `409 PasswordStoredLocally` | W5: sandi robot ini disimpan di mesin robot |
| `409 NoWindowsAccount` / `NoWindowsPassword` | Belum diisi, atau `signing.key` Orchestrator berbeda |

HTTPS adalah tanggung jawab penyebaran (`DEPLOY.md`); agent menolak mengambil sandi lewat
`http://` ke mesin lain kecuali setelan lab dinyalakan.

## 2.6 Token executor

Diberikan di jawaban klaim; diteruskan agent ke Executor (berkas ber-ACL atau pipa, **bukan**
argumen atau variabel lingkungan). Dipakai activities untuk endpoint v1 atas nama robotnya:

- izin: `assets.read`, `queues.read`, `queues.update`, `buckets.read`, `buckets.update`,
  `jobs.read`, `jobs.create`, `processes.read`, `logs.create` — tidak lebih;
- folder: folder tempat robotnya ditugaskan, ditambah folder job-nya;
- **berhenti berlaku begitu job selesai** (`401 ExecutorTokenExpired`, paling lambat 5 detik).

## 2.7 Log dan lampiran

### `POST /api/agent/logs`

```json
{ "lines": [ { "jobId": "…", "robotId": "…", "seq": 41, "level": "INFO",
               "source": "Executor/Click", "sessionId": 3, "message": "Klik 'Simpan'.",
               "loggedAt": "2026-09-29T15:16:11+07:00" } ] }
```
→ `200 {"ok": true, "written": 40, "skipped": 1}`

- `(jobId, seq)` yang sudah ada dilewati (kiriman ulang). Baris robot/job mesin lain, `DEBUG`,
  dan `TRACE` dilewati. Maks **500 baris** (`413` kalau lebih), 8 KB per pesan.
- JWT dan machine key di pesan **disensor** Orchestrator — jaring pengaman, bukan izin.

### `POST /api/agent/jobs/{id}/attachments`

`multipart/form-data`: `file` (PNG/JPEG, dikenali dari isinya), `kind` (bawaan `Screenshot`).
Maks **2 MB** per berkas, **5** per job (`413`). Tampil di rincian job di dasbor.

## 2.8 Waktu dan batas

| Nilai | Bawaan | Setelan |
|---|---|---|
| Denyut idle / sibuk | 15 s / 5 s | `openorchestrator.agent.heartbeat-seconds`, `heartbeat-busy-seconds` |
| Offline, job → UNRESPONSIVE | 60 s | `agent.offline-after` |
| UNRESPONSIVE → AgentLost | 5 menit | `agent.lost-after` |
| Tenggang rekonsiliasi | 30 s | `agent.reconcile-grace` |
| Lease penyiapan | 180 s | per mesin (dasbor) |
| Jeda stop / batas waktu / percobaan ulang | 30 s / – / 1 | per proses (dasbor) |
| Jendela hindar percobaan ulang | 60 s | `agent.retry-avoid-window` |
| Token agent | 1 jam | `agent.token-ttl` |
| Versi agent minimal | 1.0.0 | `OPENORCHESTRATOR_AGENT_MIN_VERSION` |

## 2.9 Tipe runtime dan sasaran job (V9)

Berlaku untuk v1 dan v2; agent tidak perlu mengirim apa pun yang baru.

- **Tipe runtime** — katalog tetap: `Production`, `Testing`, `Development`. Setiap mesin punya
  jumlah runtime per tipe (dasbor: Tenant › Robot › Mesin). Jumlah semuanya = `slots` mesin di
  jawaban login agent, maknanya tidak berubah. Mesin yang sudah ada sebelum V9, dan mesin baru
  tanpa setelan, punya runtime `Production` sebanyak slotnya.
- **Start Job** di dasbor meminta satu tipe runtime, dan boleh juga meminta satu **robot**
  (Akun) dan satu **mesin**. Server menolak kombinasi yang tidak dimiliki folder itu.
- **Klaim:** robot hanya mengambil job yang (a) tidak meminta mesin atau meminta mesinnya
  sendiri, dan (b) tidak meminta tipe runtime atau meminta tipe yang dimiliki mesinnya — v2:
  yang masih punya tempat kosong untuk tipe itu. Job tanpa tipe mendapat tipe pertama mesinnya
  yang masih kosong (urutan katalog), dan tipe itu yang dicatat di job.
- Job dari Studio, pemicu, dan API lama tidak meminta tipe maupun mesin: perilakunya sama
  dengan sebelum V9.

---

# Bagian 3 — Status

Sudah ada di Orchestrator:

- v1: `contract`/`capabilities`, `jobs.next.package`, `packages.sha256`, `heartbeat.commands`,
  `jobs.state.guard`, `jobs.pause`, `jobs.kill`, izin peran Robot.
- v2: semua endpoint `/api/agent/*`, machine key, rekonsiliasi, lease, UNRESPONSIVE/AgentLost,
  percobaan ulang, stop/kill, jeda (`pausedJobs`, PauseJob/ResumeJob), akun Windows per job,
  token executor, log, lampiran.
- Tipe runtime mesin dan sasaran job (2.9).
- Dasbor: Machines (kunci, runtime per tipe, lease, status agent), Robots (mesin, akun Windows,
  kebijakan sesi, status sesi dan alasan, job yang sedang dipegang), Jobs (state baru, kode
  galat, percobaan, konteks eksekusi, rekaman; menu Hentikan/Matikan/Jeda/Lanjutkan/Jalankan
  Ulang/log), Start Job (tipe runtime, akun, mesin, jumlah jalan, prioritas), setelan proses
  (batas waktu, jeda stop, ulang, prioritas bawaan).

Diuji: 206 uji unit; uji ujung-ke-ujung 83 (agent) + 103 (Start Job, aksi job, jeda, runtime)
pemeriksaan melawan PostgreSQL; regresi v1 253 panggilan (hanya tambahan medan dan perubahan
yang disengaja).

Belum:

1. Uji dengan Robot Agent sungguhan di VM (menunggu klien v2 di sisi robot).
2. Jeda dan matikan paksa di sisi robot — lihat Bagian 4.
3. Beberapa robot per mesin baru bisa diuji di Windows Server + RDS.
4. Tidak ada pembatasan laju untuk `/api/agent/login` (kunci 256 bit tidak bisa ditebak, tapi
   percobaan berulang tetap memakai sumber daya).

---

# Bagian 4 — Catatan integrasi untuk sisi robot (V9)

Untuk tim Studio/robot. Dibaca dari kode Studio `main` (`f5e075b`) dan cabang JakRunner
`claude/interesting-sanderson-e62fa8-zvdgmb` (`a43c4f3`) per 30 Sep 2026.

## Yang WAJIB diubah: tidak ada

Semua perubahan V9 menambah. Robot, JakRunner, dan Studio yang sekarang tetap bekerja tanpa
diubah. Yang perlu diketahui karena perilakunya berubah:

- **Prioritas di Start Job (activity Studio dan `POST /api/jobs`)** hanya `Low`, `Normal`,
  `High`, atau `Inherited`, tanpa membedakan huruf besar. Nilai lain sekarang ditolak `400`
  ("Prioritas tidak dikenal: …"); dulu diterima diam-diam lalu diurutkan seperti `Low`.
  Tanpa prioritas = `Inherited` = prioritas bawaan proses (Normal selama belum diubah).
- **Job yang meminta mesin** (dari Start Job dasbor) hanya diambil robot yang `machineName`
  denyutnya sama persis dengan nama mesin itu. JakRunner dan agent mengirim
  `Environment.MachineName`, jadi sudah cocok. Job dari Studio dan pemicu tidak meminta mesin
  maupun tipe runtime — tidak berubah.
- **Job v1 `STOPPING` yang robotnya diam 90 detik** kini menjadi `STOPPED` (kesimpulan server);
  laporan akhir robot yang terlambat tetap diterima.
- **Akun yang dipakai robot v1 untuk denyut** dicatat sekali sebagai pemilik robotnya. Dasbor
  memakainya untuk "jalankan sebagai diri sendiri" di Start Job.

## Yang disarankan, per komponen

**JakRunner (cabang di atas).** Denyutnya (`pausedJobId`, `pauseSource`, 5 detik selama sibuk)
dan penanganan `StopJob`/`PauseJob`/`ResumeJob` sudah persis sesuai server. Dua hal:

1. `KillJob` belum ditangani (diabaikan — sesuai kontrak, tidak rusak). Server mengirimnya
   BERSAMA `StopJob` saat orang memilih **Matikan** di dasbor. Saran: saat `KillJob` datang,
   hentikan paksa seketika, tanpa menunggu 30 detik jeda `LocalExecution`.
2. **Lanjutkan di PC robot untuk jeda dari dasbor.** Selama permintaan jeda dari dasbor masih
   berlaku, server terus mengirim `PauseJob` (tabel di usulan), jadi job yang dilanjutkan di
   PC akan dijeda lagi dalam ±5 detik. Saran: matikan tombol Lanjutkan JakRunner selama
   `pauseSource = dashboard`, dengan keterangan "Dijeda dari dasbor". Kalau yang diinginkan
   justru Lanjutkan lokal membatalkan permintaan dasbor, kabari kami — server perlu tanda
   baru untuk itu.

**Robot Agent mode v1 (`JakForge.Robot.Core`).** `RobotAgent` hanya bertindak atas `StopJob`
dan mengabaikan jenis lain — benar menurut kontrak. Untuk mendukung aksi dasbor yang baru:

- `KillJob` → jalur paksa `JobRunner`, bukan berhenti rapi.
- `PauseJob`/`ResumeJob` → tahan runtime sebelum activity berikutnya (mekanisme yang sama
  dengan perintah debug `pause` di cabang JakRunner), lalu sebut `pausedJobId` +
  `pauseSource` di denyut.
- Tambahkan `jobs.pause` dan `jobs.kill` ke `OrchestratorCapabilities`.

**Robot Agent v2 (`/api/agent`),** kalau sudah pindah: `pausedJobs: [{jobId, source}]` per
robot di denyut (jangan kirim medannya kalau belum mendukung jeda); `PauseJob`/`ResumeJob` di
`commands`; `KillJob` bisa datang seketika tanpa menunggu `graceSeconds`. `runtimeType` di
jawaban klaim hanya informasi; kapasitas per tipe runtime diurus server.

**Studio — activity Start Job (`Custom.Orchestrator`).**

- Ganti isian prioritas bebas dengan pilihan `Inherited`/`Low`/`Normal`/`High` (bawaan
  `Inherited`), karena nilai lain kini ditolak.
- Opsional: `POST /api/jobs` kini menerima `runtimeType`, `machineName` (mesin sasaran), dan
  `count` (1–100). Jawabannya membawa `ids` (semua job yang dibuat); `id` tetap job pertama,
  jadi kode yang sekarang tidak terpengaruh. Kombinasi yang tidak dimiliki folder proses
  ditolak `400` dengan pesan yang menyebut sebabnya.

## Cara menguji jeda dengan dasbor

1. Jalankan workflow panjang (mis. beberapa Delay) di JakRunner lewat dasbor.
2. Pekerjaan › ⋮ › **Jeda** → baris menampilkan "Menunggu robot menjeda…", lalu **Dijeda**
   begitu JakRunner melaporkannya (≤ 5 detik).
3. ⋮ › **Lanjutkan** → "Menunggu robot melanjutkan…", lalu kembali Berjalan.
4. Tombol Jeda di JakRunner → dasbor menampilkan "Dijeda di PC robot", dan Lanjutkan di dasbor
   nonaktif (server juga menolaknya `409`): jeda itu hanya dilanjutkan di PC robot.
5. ⋮ › **Matikan** → `StopJob` + `KillJob`; log job mencatat siapa yang memintanya.

Setiap perubahan jeda juga tercatat di log job ("Robot menjeda pekerjaan…", "Robot melanjutkan
pekerjaan.").
