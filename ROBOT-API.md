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

**Tambahan V11** (`V11__open_assistant.sql`), dua usulan tim robot (PR #3 dan #4):
`GET /api/agent/triggers` — jadwal untuk agent yang masuk dengan machine key (2.10) — dan
**Open Assistant masuk lewat dasbor**, seperti UiPath Assistant (Bagian 5). Juga menambah;
diuji ujung-ke-ujung (108 pemeriksaan) dan dengan regresi v1.

**Tambahan V12** (`V12__mesin_per_folder.sql`): **mesin didaftarkan ke folder**, seperti
Machines di folder UiPath (Bagian 6). Robot — v1 maupun v2, attended maupun unattended — hanya
mengambil job dari folder tempat **mesinnya terdaftar**, selain folder tempat robotnya
ditugaskan. Pendaftaran yang sudah berjalan diisi otomatis saat migrasi, jadi setup yang ada
tetap jalan. **Tidak ada perubahan wajib di sisi robot**; yang baru untuk Studio: galat `409`
dengan `errorCode` di `POST /api/jobs` (6.3). Diuji ujung-ke-ujung (120 pemeriksaan) dan
dengan regresi v1.

**Tambahan V13–V14** (`V13__resolusi_robot.sql`, `V14__sibuk_lokal_dan_pemicu_log.sql`), usulan tim
robot PR #5, #6, dan #7: **resolusi layar robot unattended** per robot (2.2), **"sibuk lokal"** di
denyut v1 dan v2 — robot yang sedang menjalankan automasi lokal Open Assistant tidak diberi job
(Bagian 1, 2.3) — **pemicu baris catatan** (`trigger`), dan **versi paket yang sama ditolak
`409`** seperti UiPath. Juga perbaikan: `/assistant/connect` di alamat API kini dialihkan ke
dasbor (Bagian 5). Satu-satunya perilaku yang berubah: terbit ulang versi yang sama ditolak.
Catatan untuk tim robot: Bagian 4 (6 Okt 2026). Diuji ujung-ke-ujung (55 pemeriksaan), dengan
migrasi salinan data asli V11→V14, dan dengan regresi v1.

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
                   "jobs.pause", "jobs.kill", "agent.triggers", "assistant.signin",
                   "folder.machines", "robots.resolution", "heartbeat.busyLocal", "logs.trigger",
                   "packages.versionConflict"],
  "apiVersions": [1, 2] }
```

`contract` + `capabilities` = kemampuan tambahan v1 (lihat di bawah); `apiVersions` berisi 2
kalau `/api/agent` tersedia. `agent.triggers` = 2.10, `assistant.signin` = Bagian 5,
`folder.machines` = Bagian 6,
`robots.resolution` = 2.2, `heartbeat.busyLocal` = denyut v1 di bawah dan 2.3, `logs.trigger` dan
`packages.versionConflict` = di bawah.

### `POST /api/auth/login`

`{ "username": "...", "password": "..." }` → `200 {"token": "...", "expiresInMinutes": 480, ...}`.

### `POST /api/robots/{robot}/heartbeat`

```json
{ "status": "BUSY", "cpuPercent": 3.5, "memoryMb": 210, "machineName": "PC-01",
  "pausedJobId": "…", "pauseSource": "dashboard",
  "busyLocal": true, "busyLocalName": "Rekap Harian", "busyLocalTrigger": "local-schedule" }
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
- **heartbeat.busyLocal (V14):** `busyLocal: true` = robot sedang menjalankan automasi lokal
  Open Assistant di PC attended (Play, atau jadwal lokal yang tidak disinkronkan), dengan
  `busyLocalName` (nama automasinya, opsional, ≤ 200) dan `busyLocalTrigger` (`manual` atau
  `local-schedule`, opsional). Selama denyut terakhir menyebutnya, `GET /api/jobs/next` menjawab
  `{"job": null}`, status robot `BUSY`, dan dasbor menampilkan **Sibuk (lokal)** beserta nama
  automasinya. Kirim di **setiap** denyut selama jalan lokal berlangsung; denyut tanpa
  `busyLocal` (atau `false`) = tidak sibuk lokal.
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
- Robot yang denyut terakhirnya menyebut `busyLocal: true` (V14) tidak diberi job sampai
  denyutnya berhenti menyebutnya.

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

### `POST /api/packages` (Studio, Terbitkan)

`{ "name": "Tagihan", "version": "1.0.4", "description": "catatan rilis", "entryPoint": "Main.xaml",
"contentBase64": "…" }` → `200 {"ok": true, "name": …, "version": …, "sizeBytes": …}`

- **packages.versionConflict:** versi yang sudah ada **ditolak** `409 {"error": "Paket 'Tagihan' versi
  1.0.3 sudah ada. Naikkan versinya, lalu terbitkan lagi.", "errorCode": "PACKAGE_VERSION_EXISTS"}`
  — seperti UiPath: isi sebuah versi tidak berubah sesudah terbit, karena job dan sidik SHA-256-nya
  menunjuk versi itu. Sebelum ini versi yang sama ditimpa (izin `packages.update`). Versi yang
  lebih rendah tapi belum ada tetap diterima. Izinnya `packages.create`.
- `description` = keterangan **versi itu** (catatan rilis), tersimpan per versi. Deskripsi proses
  hanya diisi saat proses pertama kali dibuat dari paket itu, dan tidak berganti di terbit
  berikutnya.
- "Versi terbaru" di dasbor dibandingkan per angka (`1.0.10` > `1.0.9`); proses memakai versi
  yang **terakhir diterbitkan**.

### `POST /api/logs` — pemicu baris (V14)

`{ "lines": [ { "level": "Info", "message": "Mulai menjalankan Rekap Harian", "robotName": "PC-01",
"trigger": "local-schedule" } ] }`

- **logs.trigger:** `trigger` per baris, opsional: `manual` (Play di Open Assistant) atau
  `local-schedule` (jadwal lokal); ejaan bebas (`Local_Schedule`) dibakukan, nilai lain
  diabaikan tanpa menggagalkan kiriman. Baris yang menyebut `jobId` otomatis `job` (yang dikirim
  robot tidak ditimpa).
- Dasbor: kolom dan saringan **Pemicu** di halaman Catatan (Job / Manual / Jadwal lokal), juga di
  ekspor CSV. `GET /api/logs?trigger=manual|local-schedule|job`; baris membawa `trigger`.

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
| Resolusi layar (V13) | Lebar × Tinggi: dua-duanya 0 (bawaan agent, 1024x768) atau dua-duanya 200..8192; kedalaman warna 0 (bawaan), 15, 16, 24, 32. Pada ubah, yang tidak dikirim tetap; pasangannya diperiksa sesudah digabung (`400` kalau tidak sah). Hanya untuk sesi yang dibuat agent |

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
                "sessionPolicy": "Logoff", "windowsPasswordLocal": false,
                "resolutionWidth": 1920, "resolutionHeight": 1080, "resolutionDepth": 32 } ],
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

**robots.resolution (V13):** `resolutionWidth`, `resolutionHeight`, `resolutionDepth` selalu ada
(0 = bawaan). Mengubahnya di dasbor menaikkan `settingsVersion` mesin, jadi agent login ulang dan
memakai nilai baru pada job berikutnya.

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
| `busyLocal`, `busyLocalName`, `busyLocalTrigger` | *(V14, opsional)* Robot attended sedang menjalankan automasi lokal Open Assistant (Play atau jadwal lokal): nama automasinya dan pemicunya (`manual` atau `local-schedule`). Selama itu klaim robot ini `204`, statusnya `Busy`, dan dasbor menampilkan **Sibuk (lokal)**. Tidak dikirim = tidak sibuk lokal |

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
- **Sejak V12** ada syarat ketiga: (c) mesin robot itu **terdaftar di folder job** dan
  keadaannya `Active` — lihat Bagian 6.

## 2.10 Jadwal — `GET /api/agent/triggers` (V11)

Usulan tim robot (PR #3, `USULAN-AGENT-TRIGGERS.md`): halaman Jadwal Open Assistant yang
tersambung dengan machine key. Hanya membaca; mengubah pemicu tetap lewat dasbor.
Kemampuan `agent.triggers` di `/api/health`.

```json
{ "triggers": [
    { "id": "…", "name": "Tagihan harian", "folderId": "…", "folderName": "Shared",
      "processName": "Tagihan", "robotId": "…", "robotName": "Robot_A",
      "type": "Cron", "cron": "0 8 * * 1-5", "intervalMinutes": 60, "timezone": "Asia/Jakarta",
      "priority": "Normal", "enabled": true,
      "nextRunAt": "2026-10-02T01:00:00.000000Z", "lastRunAt": null } ] }
```

- **Cakupan:** pemicu yang menargetkan salah satu robot mesin ini (`robotId` sama dengan
  `robots[].id` di jawaban login), ATAU yang tidak menargetkan robot tertentu (`robotId` dan
  `robotName` null) dan foldernya — folder prosesnya — adalah folder salah satu robot mesin
  ini. Pemicu untuk robot lain tidak ikut walau prosesnya di folder yang sama. Penyewa lain
  tidak pernah. **Sejak V12** keduanya hanya kalau mesin ini terdaftar di folder pemicunya —
  di folder lain job-nya tidak akan diambil mesin ini.
- Pemicu yang **dimatikan** ikut, dengan `enabled: false`. Urutannya: yang aktif dulu, lalu
  menurut `nextRunAt`.
- `type`: `Time` (berkala tiap `intervalMinutes`) atau `Cron` — `cron` **lima ruas**: menit jam
  tanggal bulan hari (`0 8 * * 1-5` = hari kerja pukul 08:00), bukan enam ruas seperti contoh
  di usulan.
- `nextRunAt` dihitung **server**, di zona waktu pemicunya (`timezone`, bawaan `UTC`), dan
  ditulis dalam UTC seperti waktu lain. Agent tidak perlu menghitung cron sendiri.
- Tidak ada → `200 {"triggers": []}`. `401` = token kedaluwarsa atau kunci diganti (login
  ulang); token selain agent → `403`.
- Beban: baca saat halaman Jadwal dibuka, paling sering tiap 60 detik. `triggersVersion` di
  denyut belum ada.

Jawaban pertanyaan di PR #3: (1) pemicu boleh menargetkan satu robot ATAU tidak (robot mana
pun yang ditugaskan ke foldernya) — cakupan di atas mengikuti keduanya; (2) zona waktu **per
pemicu**; (3) `nextRunAt` dihitung server.

---

# Bagian 3 — Status

Sudah ada di Orchestrator:

- v1: `contract`/`capabilities`, `jobs.next.package`, `packages.sha256`, `heartbeat.commands`,
  `jobs.state.guard`, `jobs.pause`, `jobs.kill`, izin peran Robot.
- v2: semua endpoint `/api/agent/*`, machine key, rekonsiliasi, lease, UNRESPONSIVE/AgentLost,
  percobaan ulang, stop/kill, jeda (`pausedJobs`, PauseJob/ResumeJob), akun Windows per job,
  token executor, log, lampiran.
- Tipe runtime mesin dan sasaran job (2.9).
- V13–V14: resolusi layar robot unattended (2.2), sibuk lokal di denyut v1/v2, pemicu baris
  catatan, versi paket yang sama ditolak `409`, dan `/assistant/connect` di alamat API dialihkan
  ke dasbor.
- Jeda/lanjut v2 di sisi robot **lulus uji VM** Windows Server pada 4 Okt (Studio
  `docs/rencana-klien-v2-fase3f.md`, Fase 3h).
- V12: mesin per folder (Bagian 6): tab **Mesin** di Setelan folder (tambah satu atau
  beberapa, lihat, hapus dari folder), keadaan mesin Active/Maintenance/Disabled, Start Job
  hanya menawarkan mesin folder prosesnya beserta statusnya.
- V11: jadwal untuk agent (2.10) dan Open Assistant masuk lewat dasbor (Bagian 5): halaman
  `/assistant/connect`, kode + PKCE, token yang bisa dicabut, daftar "Open Assistant
  tersambung" di menu profil. Halaman Machines menandai agent online tanpa robot dan nama
  komputer agent yang berbeda dari nama mesin.
- Dasbor: Machines (kunci, runtime per tipe, lease, status agent), Robots (mesin, akun Windows,
  kebijakan sesi, status sesi dan alasan, job yang sedang dipegang), Jobs (state baru, kode
  galat, percobaan, konteks eksekusi, rekaman; menu Hentikan/Matikan/Jeda/Lanjutkan/Jalankan
  Ulang/log), Start Job (tipe runtime, akun, mesin, jumlah jalan, prioritas), setelan proses
  (batas waktu, jeda stop, ulang, prioritas bawaan).

Diuji: 268 uji unit; uji ujung-ke-ujung 83 (agent) + 103 (Start Job, aksi job, jeda, runtime)
+ 108 (jadwal agent, masuk lewat dasbor) + 120 (mesin per folder) + 55 (V13–V14) pemeriksaan
melawan PostgreSQL, ditambah migrasi salinan data asli V11→V14; regresi v1 253 panggilan (hanya
tambahan medan dan perubahan yang disengaja). Klien v2 tim robot sudah diuji
melawan Orchestrator ini di VM (Windows 11 Pro dan Windows Server 2022) — lihat
`UJI-ROBOT-UNATTENDED-VMWARE.md`.

Belum:

1. Masuk lewat dasbor belum diuji dengan Open Assistant sungguhan (sisi robot sudah di Studio
   `main` sejak 4 Okt).
2. Beberapa robot per mesin baru bisa diuji di Windows Server + RDS.
3. Mesin per folder (V12), resolusi sesi, dan sibuk lokal (V13–V14) belum diuji dengan robot
   sungguhan di VM — baru dengan agent dan robot v1 tiruan melawan PostgreSQL.
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

## Dasbor bisa dibuka dari VM atau mesin lain (30 Sep 2026)

**Sebelumnya:** alamat API ditanam ke dasbor saat build (`NEXT_PUBLIC_API_URL`, bawaannya
`http://localhost:8080`). Dasbor yang dibuka dari mesin lain — mis. VM uji di
`http://<IP-host>:3000` — gagal login, karena peramban di VM memanggil `localhost:8080` milik
VM itu sendiri.

**Sekarang:** peramban memanggil `/api/*` di alamat dasbor itu sendiri, dan server dasbor
meneruskannya ke backend lewat jaringan Docker (`API_INTERNAL_URL`, di Docker Compose
`http://backend:8080`). Dasbor bisa dibuka dari alamat mana pun — localhost, IP LAN, VM,
domain — tanpa build ulang dan tanpa setelan CORS.

Untuk sisi robot:

- **Tidak ada yang berubah.** Studio, JakRunner, dan Robot Agent tetap memanggil backend
  langsung: `http://<IP-host>:8080` saat uji, `https://api.<domain>` di server. Jangan
  arahkan mereka ke port 3000 (dasbor).
- Menjalankan Docker Orchestrator sendiri untuk uji: `NEXT_PUBLIC_API_URL` di `.env` boleh
  kosong — nilai `localhost` diabaikan. `CORS_ORIGINS` hanya berpengaruh kalau
  `NEXT_PUBLIC_API_URL` diisi.
- Uji di VM kini bisa sekalian memantau dari dasbor di dalam VM: buka `http://<IP-host>:3000`
  (Ctrl+F5 kalau halaman lama masih tersimpan di cache).

Kodenya: `frontend/app/api/[...jalur]/route.ts` (penerus), `frontend/lib/api.ts`
(`alamatApi`), dan `API_INTERNAL_URL` di `docker-compose.yml`. Penyebaran di server:
`DEPLOY.md` langkah `.env`.

## Saringan halaman Catatan: Mesin, Proses, Host Identity (30 Sep 2026, V10)

Halaman Catatan di dasbor kini bisa disaring per Waktu, Tingkat, Mesin, Proses, dan
**Host Identity** — akun Windows tempat robot berjalan, mis. `VM-01\robot` — ditambah
pencarian teks pesan dan ekspor CSV.

Untuk sisi robot:

- **Tidak ada yang wajib diubah.** Mesin dan Host Identity diisi server sendiri saat baris
  ditulis: dari job-nya (mesin dan akun Windows job itu), atau dari robotnya (mesin yang
  terikat atau mesin dari denyut, dan akun Windows yang diatur di dasbor). Nilai yang dikirim
  robot tidak pernah ditimpa.
- **Disarankan untuk JakRunner dan agent v1:** kirim `hostIdentity` per baris di
  `POST /api/logs`, berisi akun Windows yang menjalankan robot (`DOMAIN\user`, mis.
  `Environment.UserDomainName + "\\" + Environment.UserName`). Tanpa itu, baris robot
  attended hanya punya Host Identity kalau akun Windows robotnya diisi di dasbor.

  ```json
  { "lines": [ { "level": "Info", "message": "…", "robotName": "ROBOT-PC", "machineName": "PC-01",
                 "processName": "Tagihan", "jobId": "…", "hostIdentity": "PC-01\\budi" } ] }
  ```

  Paling panjang 200 karakter; yang lebih panjang diabaikan (lalu diisi server seperti di
  atas), bukan menggagalkan kiriman.
- **Robot Agent v2 (`POST /api/agent/logs`):** tidak perlu apa-apa — Host Identity diambil
  dari akun Windows job-nya.
- Endpoint baca yang baru (`GET /api/logs/filters`, `GET /api/logs/export`, dan parameter
  `machine`, `host`, `time`, `from`, `to`, `q` di `GET /api/logs`) hanya dipakai dasbor dan
  butuh izin `logs.read`. Akun robot tidak memilikinya, jadi tidak berpengaruh.

## Open Assistant lewat dasbor dan mesin per folder (1 Okt 2026, V11–V12)

Untuk tim Studio/robot. Rinciannya di 2.10, Bagian 5, dan Bagian 6; di sini yang perlu
diketahui dan cara mengujinya.

**Usulan kalian sudah jalan di Orchestrator.** PR #3 (`GET /api/agent/triggers`) → 2.10;
PR #4 (masuk lewat dasbor) → Bagian 5, dengan jawaban pertanyaan PR #4 dan
`CATATAN-IDENTITAS-OPEN-ASSISTANT.md` di 5.6–5.7. Kemampuannya `agent.triggers` dan
`assistant.signin` di `/api/health`. Satu beda dari usulan: cron **lima ruas**, bukan enam.

**Yang WAJIB diubah: tidak ada.** Semuanya menambah; JakRunner, Robot Agent (v1 maupun v2),
dan Studio yang sekarang tetap bekerja.

**Yang berubah perilakunya — mesin per folder (V12, Bagian 6):**

- Robot hanya mengambil job dari folder tempat **mesinnya terdaftar**, selain robotnya
  ditugaskan ke folder itu. Berlaku untuk semua robot, attended juga (JakRunner, Open
  Assistant). Mesin baru — dibuat di dasbor, lahir dari denyut pertama, atau dari masuk Open
  Assistant — otomatis terdaftar di **Shared**, jadi uji di Shared tidak berubah. Untuk
  folder lain: dasbor › Setelan folder › tab **Mesin** › Tambah Mesin, atau tombol
  **Daftarkan mesin** di tab Robot.
- Mesin berkeadaan **Pemeliharaan** atau **Nonaktif** (Tenant › Robot › Mesin › Ubah) tidak
  mengambil job baru; job yang sedang berjalan dibiarkan selesai.
- `POST /api/jobs` (activity Start Job) bisa menjawab `409` dengan `errorCode`
  `MACHINE_NOT_ASSIGNED_TO_FOLDER` atau `MACHINE_NOT_AVAILABLE` (6.3) — juga untuk
  permintaan yang hanya menyebut `robotName`, kalau mesin robot itu tidak terdaftar di folder
  prosesnya.
- `GET /api/agent/triggers` hanya menyebut pemicu di folder tempat mesin agent terdaftar.
- Orchestrator di Docker tidak lagi membuat mesin bernama host container setiap kali naik. Di
  Docker uji yang baru, halaman Mesin kosong sampai robot pertama tersambung.

**Yang disarankan, per komponen:**

- **Open Assistant:** alur Bagian 5 — cek `assistant.signin`, buka `/assistant/connect` di
  peramban, tangkap `openassistant://signin`, tukar kodenya di
  `POST /api/auth/assistant/token`, simpan refresh token dengan DPAPI, dan perbarui sebelum
  `expiresAt`. Robotnya (`robotName` di jawaban) dibuat server; tidak perlu mendaftarkan
  robot sendiri. Tanpa `assistant.signin`, tetap pakai cara masuk yang sekarang.
- **Studio — activity Start Job:** tampilkan `error` dari jawaban `409` apa adanya. Kalau
  nanti ada pilihan mesin, ambil dari `GET /api/processes/{processId}/available-machines`,
  bukan `GET /api/machines`.
- **JakRunner dan Robot Agent:** tidak ada. Kalau job di folder selain Shared tidak pernah
  diambil, periksa dulu apakah mesinnya terdaftar di folder itu.

**Cara menguji mesin per folder (VM):**

1. Dasbor: buat folder uji di akar (bukan subfolder — subfolder mewarisi mesin induknya),
   pindahkan satu proses ke sana (Proses › **Pindahkan ke folder lain**), lalu tugaskan robot
   VM ke folder itu (Setelan › Robot). Jangan daftarkan mesinnya dulu: tab Robot menandai
   "Mesin ini belum terdaftar di folder ini", dan Start Job di dasbor belum bisa dijalankan
   ("Tidak ada runtime yang tersedia … daftarkan mesin ke folder ini").
2. Jalankan proses itu dari activity Start Job Studio tanpa robot → job dibuat tetapi
   menunggu; robot VM tidak mengambilnya. Dengan `robotName` robot VM → `409`
   `MACHINE_NOT_ASSIGNED_TO_FOLDER`.
3. **Daftarkan mesin** di tab Robot → job tadi diambil pada klaim berikutnya (v1
   `jobs/next`, v2 `jobs/claim`).
4. Start Job di dasbor: pilihan Mesin hanya berisi mesin folder itu; mesin yang tidak online
   tampil ○ dan tidak bisa dipilih. Hentikan agent → statusnya **Terputus** sesudah 60 detik
   (robot v1: 45 detik).
5. Mesin › Ubah › Keadaan **Pemeliharaan** → tidak ada job baru yang diambil; kembalikan ke
   **Aktif** → diambil lagi.
6. Setelan › Mesin › ⋮ › **Hapus dari Folder** → job baru folder itu tidak diambil lagi; job
   yang sedang berjalan tidak terganggu.

## Usulan PR #5, #6, #7 dan perbaikan masuk lewat dasbor (6 Okt 2026, V13–V14)

Untuk tim Studio/robot. Dibaca dari Studio `main` (`e7f64a2`) dan PR orchestrator #5–#7 per
6 Okt 2026. Rinciannya di Bagian 1, 2.2, 2.3, dan 5.1.

**Yang WAJIB diubah: tidak ada.** Semuanya menambah; klien yang sekarang tetap jalan. Satu
perilaku berubah: **terbit ulang versi paket yang sama kini ditolak** (PR #5 di bawah).

**Perbaikan — "Masuk lewat dasbor" dari Open Assistant.** Open Assistant membuka
`<URL Orchestrator>/assistant/connect` dengan alamat API (mis. `http://localhost:8080`), padahal
halaman itu ada di dasbor (`:3000`) — peramban mendarat di halaman galat. Sekarang backend
mengalihkannya ke halaman dasbor dengan parameter yang sama persis; **klien tidak perlu diubah**.
Dari VM pun benar: `http://192.168.231.1:8080/assistant/connect` → `http://192.168.231.1:3000/…`.

**PR #6 — resolusi layar robot unattended: dikerjakan seperti diminta.**

- Migrasi `V13__resolusi_robot.sql` (nomor V10 sudah terpakai); kolom dan CHECK sama dengan usulan.
- API robot: `resolutionWidth`, `resolutionHeight`, `resolutionDepth` di `POST /api/robots`,
  `PUT /api/robots/{name}` (`null` = tidak diubah; pasangan diperiksa sesudah digabung dengan nilai
  tersimpan), dan `GET`. Nilai tidak sah → `400` dengan pesan yang menyebut aturannya.
- `robots[]` di jawaban `POST /api/agent/login` selalu membawa ketiganya (0 = bawaan). Mengubahnya
  menaikkan `settingsVersion` mesin — dipertahankan, seperti diminta.
- Dasbor: Tenant › Robot › Ubah › **Resolusi layar (unattended)** — Lebar, Tinggi, Kedalaman warna
  (Bawaan, 32/24/16/15-bit) — dengan teks bantuan dari usulan; kolom Sesi Windows menyebut
  resolusinya. Kemampuan `robots.resolution`.
- Klaim job v2 tetap `POST /api/agent/jobs/claim` (usulan menyebut `jobs/next`); tidak berubah.

**PR #5 — Publish OpenStudio: jawaban.**

1. **Versi yang sudah ada: ditolak** `409`, `errorCode: PACKAGE_VERSION_EXISTS`, `error`: "Paket
   'Tagihan' versi 1.0.3 sudah ada. Naikkan versinya, lalu terbitkan lagi." — seperti UiPath. Dulu
   ditimpa. Versi lebih rendah yang belum ada tetap diterima. Kemampuan `packages.versionConflict`.
2. **`releaseNotes` terpisah: tidak perlu.** Di Orchestrator `description` disimpan per versi
   paket — itulah catatan rilis versi itu — dan deskripsi proses hanya diisi saat proses pertama
   kali dibuat, tidak berganti di terbit berikutnya. Kirim catatan rilis di `description` seperti
   sekarang.
3. **Urutan versi:** dasbor membandingkan per angka (`1.0.10` > `1.0.9`). Proses memakai versi
   yang terakhir diterbitkan.

**PR #7 — jadwal lokal attended: usulan 1 dan 2 dikerjakan.**

1. **Sibuk lokal** — nama kolomnya, sama di v1 dan v2:
   - v1 `POST /api/robots/{robot}/heartbeat` dan v2 `POST /api/agent/heartbeat` (di tiap objek
     `robots[]`): `busyLocal` (boolean), `busyLocalName` (nama automasi, opsional),
     `busyLocalTrigger` (`manual` | `local-schedule`, opsional).
   - Selama denyut terakhir menyebut `busyLocal: true`: robot itu **tidak diberi job**
     (`jobs/next` → `{"job": null}`, `jobs/claim` → `204`), statusnya `BUSY`, dan dasbor
     (Pemantauan › Robot, Tenant › Robot) menampilkan **Sibuk (lokal)** + nama automasi + pemicu.
   - Kirim di **setiap** denyut selama jalan lokal berlangsung; denyut tanpa `busyLocal` = selesai.
     Penolakan "sibuk lokal" di sisi robot tetap berguna untuk jeda antara klaim dan denyut
     berikutnya. Kemampuan `heartbeat.busyLocal`.
2. **Pemicu di log v1:** `POST /api/logs` menerima `trigger` per baris — `manual` atau
   `local-schedule`; nilai lain diabaikan. Baris yang menyebut `jobId` otomatis `job`. Dasbor:
   kolom dan saringan **Pemicu** di halaman Catatan (Job / Manual / Jadwal lokal), juga di ekspor
   CSV. Kemampuan `logs.trigger`.
3. **Penolakan yang bisa diulang:** belum ada kode baru. Yang sudah berlaku: penolakan **sebelum**
   job RUNNING dengan `SessionPreparationFailed` (jalur sesi terkunci) atau `ExecutorStartFailed`
   diulang otomatis sesuai "percobaan ulang" proses (bawaan 1), dan robot itu dihindari 60 detik.
   Dengan `busyLocal`, klaim memang tidak terjadi selama jalan lokal.

**Cara menguji (VM):**

1. Resolusi: Tenant › Robot › ubah robot unattended yang sandinya disimpan di Orchestrator →
   1920×1080, 32-bit → jalankan job → log agent menyebut resolusi itu. Ganti ke 1366×768 tanpa
   menyalakan ulang service → job berikutnya memakai resolusi baru.
2. Sibuk lokal: di PC attended, jalankan automasi dari jadwal lokal; selama berjalan Pemantauan ›
   Robot menampilkan **Sibuk (lokal)** dan job dari dasbor menunggu; sesudah selesai job diambil.
3. Pemicu: halaman Catatan › saringan **Pemicu** = Jadwal lokal.
4. Publish: terbitkan versi yang sama dua kali dari OpenStudio → dialog menampilkan pesan `409`.
5. Masuk lewat dasbor: Open Assistant › Hubungkan › **Masuk lewat dasbor** dengan URL
   `http://<IP-host>:8080` → peramban terbuka di halaman persetujuan dasbor.

---

# Bagian 5 — Open Assistant: masuk lewat dasbor (V11)

Usulan tim robot (PR #4, `USULAN-ASSISTANT-SIGNIN.md`), diimplementasikan seperti
diusulkan: OAuth 2.0 untuk aplikasi desktop (RFC 8252) dengan PKCE S256 (RFC 7636).
Kemampuan `assistant.signin` di `/api/health`. Tidak ada sandi yang diketik di Open
Assistant, dan tidak ada token di alamat.

```
Open Assistant                    Peramban / dasbor                       Orchestrator API
  state, code_verifier (acak, di memori)
  buka /assistant/connect?… ─────► belum masuk? → layar masuk, lalu kembali ke sini
                                   "Sambungkan Open Assistant di PC-X sebagai Fajar?"
                                   [Buka Open Assistant] ── POST /api/auth/assistant/code ─►
  ◄── openassistant://signin?code=…&state=…
  cocokkan state
  POST /api/auth/assistant/token {code, codeVerifier, machineName, clientVersion} ─────────►
  ◄──────────────────────────────── {token, expiresAt, refreshToken, user, robotName}
  denyut / klaim job v1 sebagai robotName, dengan token itu
```

## 5.1 Halaman dasbor `GET /assistant/connect`

| Parameter | Isi |
|---|---|
| `client` | `open-assistant` (hanya ini) |
| `state` | penanda acak url-safe, 8–512 karakter (`[A-Za-z0-9._~=-]`) — dikembalikan apa adanya |
| `code_challenge` | `BASE64URL(SHA256(code_verifier))`, 43 karakter |
| `code_challenge_method` | `S256` (hanya ini) |
| `redirect_uri` | `openassistant://signin` (hanya ini) |
| `machine` | nama komputer, hanya untuk ditampilkan (opsional) |

- Parameter yang salah → "Tautan sambungan tidak sah"; halaman tidak pernah meneruskan ke mana
  pun.
- **Di alamat API juga:** `GET <API>/assistant/connect?…` (mis. `http://localhost:8080`) dialihkan
  `302` ke halaman ini di dasbor dengan parameter yang sama persis — Open Assistant cukup tahu
  satu alamat. Alamat dasbor = asal pertama `CORS_ORIGINS`; `localhost` di sana berarti
  "komputer server ini", jadi peramban di VM yang membuka `http://192.168.231.1:8080/assistant/connect`
  diarahkan ke `http://192.168.231.1:3000/assistant/connect`.
- Belum masuk → layar masuk dasbor, lalu kembali ke halaman ini dengan parameter yang sama.
- Sudah masuk → kartu "Sambungkan Open Assistant? Open Assistant di **{machine}** akan
  tersambung … sebagai **{nama}**" dengan **Buka Open Assistant** dan **Batal**, serta "Masuk
  dengan akun lain". **Tidak pernah diteruskan otomatis tanpa klik.**
- **Buka Open Assistant** → `openassistant://signin?code={kode}&state={state}`. Kodenya sekali
  pakai, 256 bit, berlaku **60 detik**, terikat ke pengguna dan `code_challenge`. Halaman lalu
  menampilkan "Kalau peramban bertanya, pilih Buka … tab ini boleh ditutup", dan tautan "Coba
  buka lagi" selama kodenya belum dipakai.
- **Batal** → `openassistant://signin?error=access_denied&state={state}`.
- Peran tanpa izin `robots.update` (mis. Auditor) mendapat penjelasan, dan tombolnya mati.

## 5.2 `POST /api/auth/assistant/token` (tanpa token)

```json
{ "code": "…", "codeVerifier": "…", "machineName": "DESKTOP-ILR0BGM", "clientVersion": "1.0.0.0" }
```
→ `200` (`Cache-Control: no-store`)
```json
{ "token": "eyJ…", "expiresAt": "2026-10-01T04:00:00.000000Z", "refreshToken": "…",
  "user": { "username": "fajar", "displayName": "Fajar" },
  "robotName": "fajar-DESKTOP-ILR0BGM" }
```

| Galat | Arti |
|---|---|
| `400` `errorCode: invalid_request` | `code` atau `codeVerifier` kosong |
| `400` `errorCode: invalid_grant` | kode tidak dikenal, kedaluwarsa, atau sudah dipakai; atau `codeVerifier` salah |

- Setiap percobaan **menghabiskan** kodenya — juga yang gagal karena verifier salah.
- Kode yang dipakai **kedua kali** mencabut sambungan yang sudah terbit darinya.
- `machineName` kosong → dipakai `machine` yang disebut di peramban.

## 5.3 `POST /api/auth/assistant/refresh` dan `/logout` (tanpa token)

- `refresh` `{ "refreshToken": "…" }` → bentuk yang sama dengan 5.2: token **dan** refresh
  token baru (rotasi; yang lama langsung tidak berlaku). Refresh token LAMA yang dipakai lagi
  dianggap disalin orang lain: **seluruh sambungan dicabut**. `401 invalid_grant` = sambungan
  berakhir atau dicabut → tampilkan "Sesi berakhir" dan halaman Hubungkan. `400
  invalid_request` kalau kosong.
- `logout` `{ "refreshToken": "…" }` → `200 {"ok": true}`, idempoten. Untuk tombol Putuskan.

## 5.4 Token akses

- Token pengguna biasa (`Authorization: Bearer`), berlaku **1 jam**, membawa id sambungannya.
  Sambungan yang dicabut memutusnya **seketika**: `401 errorCode: AssistantSessionRevoked`.
- Izinnya = izin peran pengguna yang juga termasuk pekerjaan robot attended: `robots.read`,
  `robots.update` (denyut), `jobs.read`/`create`/`update`, `processes.read`, `packages.read`,
  `triggers.read` (halaman Jadwal), `logs.create`, `assets.read`, `queues.read`/`update`,
  `buckets.read`/`update`. Administrator pun tidak mendapat izin mengelola penyewa lewat token
  ini. Endpoint untuk orang hanya bisa dibaca (`GET /api/auth/me`, folder); mengubah profil
  atau sandi → `403`.
- Dipakai seperti akun robot v1: `POST /api/robots/{robotName}/heartbeat`,
  `GET /api/jobs/next?robot={robotName}`, laporan state, `POST /api/logs`, jadwal
  `GET /api/triggers?folderId=`, dan `session.json` job (activity).

## 5.5 Dasbor

- Menu profil › **Open Assistant tersambung**: mesin, robot, versi, terakhir aktif, dengan
  tombol **Cabut**.
- Semua sambungan seseorang dicabut otomatis saat ia mengganti sandi, sandinya direset admin,
  atau akunnya dinonaktifkan atau dihapus.

## 5.6 Jawaban pertanyaan PR #4

1. **Robot attended dibuat otomatis** saat masuk pertama: tipe `Attended`, pemilik = pengguna
   itu, masuk folder bawaan seperti robot baru lain. Namanya `{username}-{komputer}` (selain
   huruf, angka, titik, garis bawah, dan tanda hubung menjadi `-`), mis.
   `fajar-DESKTOP-ILR0BGM`. Kalau nama itu sudah dipakai robot lain (pemilik lain atau robot
   unattended) → `-2`, `-3`, dan seterusnya.
2. **Satu robot per pengguna + mesin**, bukan per pengguna: robot yang berdenyut dari dua
   komputer sekaligus berganti mesin setiap denyut, dan job untuknya bisa diambil komputer
   yang salah. Masuk lagi dari komputer yang sama memakai robot yang sama.
3. **Umur:** token akses 1 jam; refresh token 30 hari, bergeser (setiap pembaruan
   memperpanjangnya); kode 60 detik. **Tidak ada izin baru:** yang boleh menyambungkan adalah
   peran yang punya `robots.update` — izin denyut robot (Administrator, Automation Developer,
   Automation User, Robot). Token Open Assistant sendiri tidak bisa menyetujui sambungan baru.

## 5.7 Jawaban `CATATAN-IDENTITAS-OPEN-ASSISTANT.md`

1. **PC attended:** tonjolkan **Masuk lewat dasbor** — identitasnya pengguna, robotnya robot
   attended milik pengguna itu, seperti UiPath. Machine key untuk mesin unattended (robot
   diikat ke mesin, akun Windows robot). Machine key di PC attended tetap bisa (robot tipe
   unattended dengan akun Windows = orang yang memakai PC), tapi bukan jalur utamanya.
2. **Mesin tanpa robot:** halaman Machines kini menandai mesin yang agent-nya online tetapi
   belum melayani robot unattended mana pun ("Belum ada robot").
3. **Nama komputer:** halaman Machines menampilkan nama komputer dari login agent
   (`agentHostName`) di bawah nama mesin kalau keduanya berbeda.

---

# Bagian 6 — Mesin per folder (V12)

Seperti Machines di folder UiPath: mesin **didaftarkan** ke folder, terpisah dari penugasan
robot. Kemampuan `folder.machines` di `/api/health`.

## 6.1 Aturan

- Robot mengambil job sebuah folder hanya kalau (1) robotnya ditugaskan ke folder itu — atau
  job-nya menyebut robot itu langsung — **dan** (2) **mesinnya terdaftar di folder itu** dan
  keadaannya `Active`. Berlaku untuk `GET /api/jobs/next` (v1: JakRunner, Open Assistant)
  dan `POST /api/agent/jobs/claim` (v2). Yang tidak memenuhi: tidak mendapat job (seperti
  antrean kosong), bukan galat.
- Mesin robot v1 = mesin bernama `machineName` di denyutnya; robot v2 = mesin yang diikat.
- **Migrasi:** setiap mesin didaftarkan ke folder tempat robotnya bekerja; mesin tanpa robot
  ke folder bawaan (Shared). Setup yang ada tetap jalan.
- **Mesin baru** — dibuat di dasbor, lahir dari denyut robot, atau dari masuk Open Assistant
  — otomatis terdaftar di **Shared**, seperti robot baru. Untuk folder lain, admin
  mendaftarkannya: Setelan folder › tab **Mesin** › Tambah Mesin, atau **Daftarkan mesin** di
  tab Robot (robot yang mesinnya belum terdaftar diberi tanda). Subfolder baru mewarisi mesin
  induknya.
- **Attended juga:** PC orang yang memakai Open Assistant/JakRunner harus terdaftar di folder
  proses yang dijalankannya. Kalau job di folder selain Shared tidak pernah diambil, periksa
  ini dulu.
- Mesin yang ditanam server saat naik (nama host container) tidak didaftarkan ke folder mana
  pun.
- Mesin yang dikeluarkan dari folder tetap ada; job yang sedang berjalan di sana tidak
  dihentikan.

## 6.2 Keadaan dan status mesin

- `state` (Tenant › Robot › Mesin › Ubah): `Active` | `Maintenance` (sementara tidak
  mengambil job baru) | `Disabled` (tidak dipakai; juga tidak bisa didaftarkan ke folder).
  Job yang sedang berjalan dibiarkan selesai.
- `status` (dihitung server): `DISABLED` / `MAINTENANCE` dari keadaannya; `ONLINE` kalau
  Robot Agent-nya, atau robot v1 di sana, baru berdenyut; `DISCONNECTED` kalau pernah
  tersambung lalu hilang; selebihnya `OFFLINE`.

## 6.3 `POST /api/jobs` (Start Job, termasuk activity Studio)

Medan lama tetap. Tambahan opsional: `processId` (menentukan proses dan foldernya) dan
`machineId` (sama dengan `machineName`, lewat id). Penolakan baru:

| Kasus | Jawaban |
|---|---|
| Mesin yang diminta tidak terdaftar di folder proses (atau tidak dikenal) | `409`, `errorCode: MACHINE_NOT_ASSIGNED_TO_FOLDER` |
| Hanya `robotName`, dan mesin robot itu (yang dikenal) tidak terdaftar di folder proses | `409`, `MACHINE_NOT_ASSIGNED_TO_FOLDER` |
| Mesin terdaftar tapi tidak `ONLINE` | `409`, `errorCode: MACHINE_NOT_AVAILABLE`, `state` = statusnya |
| `processId` tidak dikenal | `404` |

"Mesin mana pun" (tanpa mesin) tetap diterima walau belum ada mesin online — job menunggu,
dan hanya diambil mesin folder itu. Jalankan Ulang job yang meminta mesin atau robot yang
mesinnya sudah dikeluarkan dari folder juga `409 MACHINE_NOT_ASSIGNED_TO_FOLDER`. Pemicu tidak
diperiksa saat membuat job (job-nya menunggu sampai mesinnya terdaftar).

```json
{ "error": "Mesin 'RPA-PROD-03' sedang offline, jadi tidak bisa dipilih. Pilih mesin yang online, atau Mesin mana pun.",
  "errorCode": "MACHINE_NOT_AVAILABLE", "state": "OFFLINE" }
```

## 6.4 Endpoint (token pengguna)

| Endpoint | Siapa / jawaban |
|---|---|
| `GET /api/folders/{folderId}/machines` | yang boleh membuka folder itu |
| `GET /api/folders/{folderId}/available-machines` | pengelola folder (`folders.update`); pemilik Folder Saya hanya melihat mesin tempat robotnya sendiri bekerja |
| `POST /api/folders/{folderId}/machines` `{"machineId"}` | sama; sudah terdaftar → `409 MACHINE_ALREADY_ASSIGNED`; Disabled → `400` |
| `POST /api/folders/{folderId}/machines/bulk` `{"machineIds": [...]}` | sama; satu transaksi (satu ditolak = semua batal), paling banyak 100 → `{"added", "skipped"}` |
| `DELETE /api/folders/{folderId}/machines/{machineId}` | sama; tidak terdaftar → `404` |
| `GET /api/processes/{processId}/available-machines?runtimeType=` | `jobs.create` + akses ke folder proses |

```json
{ "processId": "…", "processName": "Tagihan", "folderId": "…",
  "machines": [
    { "id": "…", "name": "RPA-PROD-01", "hostname": "rpa-prod-01", "type": "Standard",
      "state": "Active", "status": "ONLINE", "available": true, "slots": 2,
      "runtimes": { "Production": 2 }, "folderRobots": 1, "assignedAt": "…", "assignedBy": "admin" },
    { "id": "…", "name": "RPA-PROD-03", "hostname": null, "status": "OFFLINE", "available": false } ] }
```

`available` = bisa dipilih di Start Job (ONLINE dan punya runtime); yang lain dikirim supaya
bisa ditampilkan tidak aktif beserta statusnya. Tambah/hapus tercatat di Audit (komponen
Folder: "Tambah mesin" / "Keluarkan mesin").

## 6.5 Untuk sisi robot

- **Wajib: tidak ada.** Klien yang tidak mengenal medan atau endpoint baru tetap jalan.
- **Studio (activity / tombol Start Job):** tampilkan `error` dari jawaban `409` apa adanya;
  bercabang dengan `errorCode` kalau perlu. Kalau ada pilihan mesin, ambil dari
  `GET /api/processes/{id}/available-machines`, bukan `GET /api/machines`.
- **Open Assistant / JakRunner:** tidak ada perubahan; kalau job di folder selain Shared tidak
  pernah diambil, mesinnya belum terdaftar di folder itu (6.1).
- **Robot Agent:** tidak ada perubahan. `GET /api/agent/triggers` kini hanya menyebut pemicu
  di folder tempat mesinnya terdaftar (2.10).
