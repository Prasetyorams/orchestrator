# Pertanyaan untuk Robot Agent Unattended

> **Dijawab dari sisi Robot Agent (JakForge) — 29 Sep 2026.**
> Status ringkas: klien **v1** (attended, login akun, heartbeat per-robot) sudah jadi dan teruji di VM; klien **v2** (`/api/agent/*`) **belum dibangun** — jawaban ini menjadi dasar membangunnya. Kami setuju dengan kontrak `ROBOT-API.md` yang sudah kalian implementasikan: W1–W6 semuanya terpenuhi. Terima kasih.

Daftar ini dari sisi **Open Orchestrator**. Jawabannya dipakai untuk
merancang API dan fitur Orchestrator yang akan dipanggil Robot Agent
unattended — supaya Studio, robot, dan Orchestrator bisa bekerja sama tanpa
API ganda atau asumsi yang berbeda.

Cara mengisi: tulis jawaban singkat di bawah setiap pertanyaan, pada baris
**Jawaban:**. "Belum tahu" juga jawaban yang berguna.

Kontrak yang berlaku sekarang (dipakai JakRunner, Studio, dan activities
`Custom.Orchestrator`) tetap didukung; semua yang baru akan berupa tambahan.

---

## A. Prioritas utama

Lima pertanyaan ini paling menentukan desain Orchestrator — mohon dijawab
lebih dulu.

**1.** Agent unattended-nya dibangun dari JakRunner (`ForgeHubClient`) atau
aplikasi baru? Di repo mana, dan kapan bisa di-push supaya bisa dibaca?

Jawaban: Aplikasi baru: **`JakForge.Robot.Agent`** (layanan Windows, LocalSystem) + **`JakForge.Robot.Core`** (klien orchestrator, state machine, JobRunner, rekonsiliasi). BUKAN dari `ForgeHubClient` JakRunner. Runtime/Executor tetap `JakRunner.exe --execute` (HeadlessExecutor), dipakai bersama Studio (Run) dan Assistant (Play). Kode sudah ada di solusi Studio (`OpenRPA.sln`), repo publik yang sama tempat `docs/ROBOT-API-tanggapan-robot.md` berada — bisa dibaca sekarang. Catatan penting: **klien v1** (login akun, heartbeat per-robot) sudah jadi dan teruji di VM; **klien v2** (`/api/agent/*`) BELUM dibangun — itu pekerjaan kami berikutnya, mengikuti kontrak ini.

**2.** Siapa yang login ke Windows? Agent memakai akun Windows yang disimpan
di **Orchestrator** (dikirim ke agent saat job mulai), atau akunnya disimpan
**lokal** di mesin (Credential Manager/DPAPI)?

Jawaban: Dua-duanya, sesuai W5. Bawaan: sandi disimpan di Orchestrator (SecretBox), diambil agent **per job** saat `ASSIGNED`/`PREPARING_SESSION` lewat `/windows-credential`. Alternatif: sandi lokal di mesin (DPAPI LocalMachine) dengan `windowsPasswordLocal=true`, Orchestrator hanya menyimpan nama akun. Agent kami sekarang menyimpan sandi lokal terenkripsi DPAPI; kedua mode akan didukung.

**3.** Mekanisme sesi apa yang dipakai: `OpenRPA.RDService` (RDP loopback),
`CreateProcessAsUser` ke sesi yang sudah ada, autologon, atau lainnya?

Jawaban: Sekarang: **`CreateProcessAsUser` ke sesi yang sudah ada** (auto-logon manual saat boot) lewat `SessionExecutorLauncher` (WTS + token sesi konsol). Agent belum membuat/membuka sesi sendiri. Rencana menghapus ketergantungan auto-logon: **RDP loopback** dengan klien RDP bawaan Windows (basis `OpenRPA.RDService`) — masih prototipe, belum jadi. Jadi uji v2 pertama: auto-logon + CreateProcessAsUser.

**4.** Apakah satu mesin harus bisa menjalankan beberapa robot sekaligus
(misalnya Windows Server + RDS)? Kalau ya, target maksimalnya berapa?

Jawaban: Sekarang **satu robot per mesin** (Windows 10/11 client hanya 1 sesi interaktif); agent melaporkan `maxInteractiveSessions=1`. Beberapa robot per mesin butuh Windows Server + RDS (banyak sesi) — target masa depan, belum diuji. `slots`/`maxInteractiveSessions` di kontrak sudah mengakomodasi; slot efektif = min(keduanya) sudah tepat.

**5.** Satu agent melayani berapa robot? Satu agent per mesin yang mengurus
banyak robot/sesi, atau satu agent per robot?

Jawaban: **Satu agent per mesin**, mengurus semua robot di mesin itu (di client praktisnya 1). Sesuai desain v2 (heartbeat per-agent dengan `robots[]`). Bukan satu agent per robot.

---

## B. Identitas dan autentikasi

**6.** Setuju agent memakai **machine key** (dibuat di Orchestrator,
dipasang sekali di mesin), bukan username/password akun pengguna?

Jawaban: Setuju penuh. Lebih aman daripada username/password akun pengguna, dan bisa dicabut per mesin. Kami login lewat `/api/agent/login` dengan `oo_mk_…`.

**7.** Nama robot sekarang otomatis `NAMAMESIN-namauser`. Tetap begitu,
atau robot didaftarkan dulu di Orchestrator lalu agent memakai nama/ID dari
sana?

Jawaban: Untuk unattended: **didaftarkan dulu di dasbor** (Tenant › Robots), agent memakai `robotId`/nama dari balikan login. Format `NAMAMESIN-namauser` tetap untuk v1/attended (auto-register). Setuju pendekatan v2.

**8.** Di mana agent menyimpan token/kunci di mesin, dan apakah dilindungi
(DPAPI/Credential Manager)?

Jawaban: Machine key + URL orchestrator di `%ProgramData%\JakForge\agent.json`, dilindungi **DPAPI scope LocalMachine** (layanan LocalSystem). Token agent (1 jam) hanya di memori proses, tidak ditulis ke disk. Sudah ada `SecretProtector` DPAPI machine-scope untuk agent.json.

---

## C. Heartbeat dan status

**9.** Heartbeat dikirim **per agent** (satu laporan berisi semua
robot/sesi) atau **per robot**? Dengan jeda berapa detik?

Jawaban: **Per agent** (satu laporan berisi semua robot mesin), sesuai v2. Jeda mengikuti setelan server: 15 dtk idle, 5 dtk saat ada job. Setuju.

**10.** Status apa saja yang bisa dilaporkan agent? Contoh: Online, Idle,
Busy, sesi Active/Locked/Disconnected/None, Executor Running/Stopped, versi
agent.

Jawaban: Cocok dengan kontrak: `state` Idle/Busy/Error; `session` {id, state Active/Locked/Disconnected/None, windowsUser, `ready`}; `executor` {state Running/Stopped, pid}; `reason` {code,text} (mis. SessionLocked, NoSession, LogonFailed, RemoteDesktopDisabled); `agentVersion`; `activeJobIds`. `session.ready` kami tentukan sendiri (sesi terkunci tetap Active bagi WTS) — sesuai W6.

**11.** Apakah agent bisa menerima **perintah balik lewat jawaban
heartbeat** (Stop, Kill, muat ulang setelan)? Atau lebih suka jalur lain
(polling endpoint tersendiri, WebSocket/SignalR)?

Jawaban: **Ya, lewat jawaban heartbeat** (StopJob/KillJob). Tidak perlu WebSocket/SignalR — polling denyut cukup dan lebih sederhana untuk layanan Windows di belakang NAT. Setuju perintah diturunkan dari keadaan job tanpa perlu ack.

---

## D. Siklus hidup job

**12.** Setuju dengan state berikut?
`Pending → Assigned (lease) → PreparingSession → Running → Successful / Faulted / Stopped`,
ditambah `Stopping`. Ada state lain yang dibutuhkan agent?

Jawaban: Setuju: PENDING→ASSIGNED→PREPARING_SESSION→RUNNING→SUCCESSFUL/FAULTED/STOPPED + STOPPING, plus UNRESPONSIVE (sisi server). Tidak butuh state lain dari sisi agent. Internal robot punya fase lebih rinci (ResolvingPackage, Downloading, Preparing, Starting) yang kami petakan ke PREPARING_SESSION/RUNNING.

**13.** Berapa lama paling lama agent butuh untuk **menyiapkan sesi** (login
Windows + start Executor)? Dipakai sebagai batas waktu lease sebelum job
dianggap gagal.

Jawaban: Dengan auto-logon yang sudah aktif: start Executor biasanya **< 30 dtk**. Kalau nanti RDP loopback membuat sesi dari nol: perkiraan 60–120 dtk. Lease bawaan **180 dtk** sudah lapang; cukup, bisa dinaikkan per mesin bila perlu.

**14.** Kode kegagalan apa yang ingin dilaporkan agent? Contoh:
`SessionPreparationFailed`, `ExecutorStartFailed`, `ExecutorCrashed`,
`WorkflowFailed`, `Timeout`.

Jawaban: Setuju daftar di kontrak: SessionPreparationFailed, LogonFailed, ExecutorStartFailed, ExecutorCrashed, PackageNotFound, PackageDownloadFailed, PackageIntegrityFailed, WorkflowLoadFailed, WorkflowFailed, Timeout, AgentShutdown — semua bisa kami bedakan. Sesi terkunci/tak ada kami laporkan sebagai `reason` di denyut (bukan kegagalan job), agar job tetap PENDING untuk robot lain, bukan diambil lalu digagalkan.

**15.** Data konteks eksekusi apa yang bisa dikirim agent: Windows Session
ID, Executor PID, nama user Windows, nomor percobaan?

Jawaban: Windows Session ID, Executor PID, nama user Windows, dan nomor percobaan (`attempt`) — semua bisa kami kirim di `context`. Sudah kami rekam di status.json dan buku job.

**16.** Stop: apakah agent mampu stop halus (minta workflow berhenti), lalu
kill paksa setelah jeda? Berapa jeda yang wajar?

Jawaban: Ya: stop halus (minta workflow Cancel rapi) → setelah `stopGraceSeconds` paksa (Abort) → +jeda kill pohon proses. **30 dtk** wajar untuk grace. Sudah teruji di v1 (Stop → STOPPED ±6 dtk untuk workflow yang menurut).

**17.** Kalau job gagal, perlu **retry otomatis** di Orchestrator? Kalau ya,
berapa kali dan untuk kode gagal apa saja? Atau selalu manual?

Jawaban: Ya, tapi **hanya untuk job yang belum pernah RUNNING** (W3) — workflow yang sudah jalan bisa sudah kirim email/mengisi data. `maxRetries` bawaan 1 (maks 2). Aman diulang: SessionPreparationFailed, ExecutorStartFailed, ExecutorCrashed, PackageDownloadFailed, PackageIntegrityFailed, + kesimpulan server (AgentRestarted/AgentLost/LeaseExpired). Tidak diulang: WorkflowFailed, WorkflowLoadFailed, LogonFailed, PackageNotFound, Timeout. Persis tabel kontrak.

**18.** Perlu batas waktu maksimal per job (job timeout) yang disetel di
Orchestrator per proses?

Jawaban: Ya. `timeoutSeconds` dari klaim dijalankan **agent** → lapor `FAULTED Timeout`. Jaring pengaman server (RUNNING + batas + jeda + 5 mnt) bagus sebagai cadangan. Setuju disetel per proses di dasbor.

---

## E. Sesi Windows dan kredensial

**19.** Kalau akun Windows disimpan di Orchestrator: formatnya `DOMAIN\user`
atau `user@domain`? Satu akun per robot, atau satu akun boleh dipakai
beberapa robot?

Jawaban: `DOMAIN\user` untuk domain, `.\user` untuk akun lokal (sesuai v2). **Satu akun Windows per robot** (1 robot = 1 akun = 1 sesi = 1 job bersamaan). Satu akun untuk beberapa robot hanya kalau tak pernah jalan bersamaan di mesin sama — tidak kami sarankan; bawaan 1:1.

**20.** Kapan agent mengambil sandi Windows: sekali saat start, atau setiap
job mulai? (Makin singkat tinggal di memori, makin aman.)

Jawaban: **Setiap job mulai** (saat ASSIGNED/PREPARING); tidak disimpan setelah sesi siap — makin singkat di memori makin aman. Kalau `windowsPasswordLocal`, dibaca dari DPAPI lokal saat perlu. Setuju per-job.

**21.** Setelah job selesai, sesinya diapakan: dibiarkan login, di-lock,
di-disconnect, atau di-logoff? Perlu bisa disetel per robot dari
Orchestrator?

Jawaban: Bisa disetel per robot: **Logoff** (bawaan, bersih/aman) atau **KeepLoggedIn** (lebih cepat untuk job beruntun). Dua opsi ini cukup untuk sekarang. Lock/Disconnect bisa ditambah nanti bila perlu — tidak mendesak.

---

## F. Log, hasil, dan berkas

**22.** Format log sekarang (`lines[]` tiap 3 detik: level, message,
robotName, machineName, processName, jobId, loggedAt) cukup, atau perlu
tambahan (nomor urut, sumber Agent/Executor, Session ID)?

Jawaban: Perlu tambahan yang sudah ada di v2: **`seq`** (idempoten kiriman ulang), **`source`** (Agent/Executor + activity), dan **Session ID**. Dengan itu cukup.

**23.** Apakah agent perlu mengunggah **screenshot** atau berkas saat job
gagal? Kalau ya, ukuran maksimalnya berapa?

Jawaban: Ya, screenshot saat gagal sangat berguna (dan bisa diminta operator). PNG/JPEG, maks **2 MB/berkas, 5/job** seperti di kontrak — cukup.

**24.** Output workflow dikirim sebagai JSON di `outputJson` — cukup begitu?

Jawaban: Cukup (argumen keluaran workflow sebagai JSON). Batas **1 MB** cukup untuk hampir semua kasus; kalau lebih besar kami unggah sebagai lampiran/berkas, bukan di `outputJson`.

---

## G. Pemulihan

**25.** Setelah agent restart, bisakah agent melaporkan job mana yang
**masih benar-benar berjalan**, supaya Orchestrator bisa membereskan job
"hantu"?

Jawaban: Ya. Agent membaca `state\current-job.json` saat mulai: runtime masih hidup → job disambung & disebut di `activeJobIds`; sudah selesai → hasil dari `result.json`; tak ada hasil → FAULTED jujur. `activeJobIds` inilah dasar rekonsiliasi "job hantu" (W1). Sudah teruji di v1 (agent dimatikan paksa lalu mulai lagi).

**26.** Kalau jaringan putus saat job berjalan: workflow tetap jalan dan
laporannya dikirim ulang setelah tersambung? Berapa lama agent sanggup
menahan log dan status?

Jawaban: Workflow **tetap jalan**; laporan akhir & log disimpan di `state\outbox` dan dikirim ulang sampai diterima (idempoten via `seq`), log selama putus ikut terkirim. Tahan selama disk cukup (praktis tak terbatas). Sudah teruji v1 (putus ±100 dtk, tanpa baris ganda).

**27.** Batas putus sekarang 45 detik (setelah itu job RUNNING dianggap
gagal). Terlalu pendek? Berapa yang realistis?

Jawaban: Untuk v1 yang keras (RUNNING langsung FAULTED) — ya, agak pendek; kami pernah alami putus lab ±104 dtk. Pendekatan v2 jauh lebih baik: offline→UNRESPONSIVE **60 dtk**, →AgentLost **5 mnt**, dan kegagalan itu **disimpulkan** (bisa digantikan laporan asli). 60 dtk / 5 mnt realistis. Setuju.

---

## H. Paket, versi, dan instalasi

**28.** Apakah agent **mengunduh paket** dari Orchestrator
(`/api/packages/{nama}/{versi}/content`) sesuai versi di job? Sekarang
JakRunner hanya mencocokkan nama folder lokal.

Jawaban: Ya. Klaim v2 membawa `package.url` + `sha256` + `sizeBytes`; agent akan **unduh dari `/api/packages/{nama}/{versi}/content`, verifikasi SHA-256, cache lokal** (lewati unduh kalau hash cocok). Beda dari JakRunner v1 yang hanya mencocokkan nama folder lokal. Setuju.

**29.** Agent mengirim versinya ke Orchestrator? Perlu aturan "versi agent
minimal" dari sisi Orchestrator?

Jawaban: Ya, `agentVersion` di login & heartbeat. Aturan `minAgentVersion` server (426 AgentTooOld) berguna untuk memaksa upgrade — setuju. Versi agent awal **1.0.0**.

**30.** Bagaimana mesin didaftarkan: admin membuat mesin + key di dasbor lalu
memasangnya di installer, atau agent mendaftar sendiri saat pertama menyala?

Jawaban: **Admin membuat mesin + machine key di dasbor**, lalu machine key dipasang sekali saat instalasi (installer meminta URL orchestrator + machine key, simpan DPAPI). BUKAN self-register — lebih aman, admin memegang kendali. Sesuai v2.

---

## I. Pengujian dan koordinasi

**31.** Kapan lingkungan VMware siap diuji melawan Orchestrator? Apakah VM
bisa mengakses Orchestrator (laptop atau server) lewat jaringan?

Jawaban: VM sudah siap dan **sudah teruji melawan Orchestrator v1** (VMware NAT, orchestrator berjalan di host, file server paket terpisah); VM bisa mengakses orchestrator di host lewat jaringan NAT. Untuk v2: **begitu klien v2 sisi robot jadi**, kami uji di VM yang sama, bertahap per fase (login → heartbeat → klaim → laporan → sandi → paket).

**32.** Setuju sepakat kontrak dulu di dokumen `ROBOT-API.md` (ditulis dari
sisi Orchestrator, direview dari sisi robot) sebelum kedua sisi mulai
ngoding?

Jawaban: Setuju penuh. `ROBOT-API.md` = sumber kebenaran. Jawaban ini mengunci kesepahaman; perubahan berikutnya lewat `ROBOT-API.md` dulu sebelum kedua sisi mengubah kode.

**33.** Endpoint lama JakRunner harus tetap didukung sampai kapan?

Jawaban: **Tanpa batas waktu.** v1 tetap dipakai attended (Studio Run, Assistant Play, robot yang login dengan akun pengguna). Tidak ada rencana menghapusnya; v1 & v2 berdampingan, agent memilih dari `apiVersions` di `/api/health`. Terima kasih sudah menjaga v1 tetap kompatibel.

---

## Lampiran — API yang dipakai sekarang

Untuk rujukan saat menjawab. Semua endpoint ini sudah ada di Open
Orchestrator dan akan tetap jalan.

| Pemanggil | Endpoint | Isi |
|---|---|---|
| JakRunner | `POST /api/auth/login` | `username`, `password` → `token` |
| JakRunner | `POST /api/robots/{robot}/heartbeat` (tiap 15 detik) | `status`, `cpuPercent`, `memoryMb`, `machineName` |
| JakRunner | `GET /api/jobs/next?robot={robot}` | → `job.id`, `job.processName`, `job.inputJson` |
| JakRunner | `POST /api/jobs/{id}/state` | `state`, `progress`, `info` |
| JakRunner | `POST /api/logs` (tiap 3 detik) | `lines[]` |
| Studio | `POST /api/auth/login`, `GET/POST /api/packages`, `GET /api/processes`, `POST /api/jobs` | |
| Activities | `GET /api/assets/{nama}/value`, `GET /api/credentials/{nama}/value` | |
| Activities | `POST /api/queues/{q}/items`, `POST /api/queues/{q}/next`, `POST /api/queues/items/{id}/result` | |
| Activities | `POST /api/jobs` (Start Job), `GET /api/jobs/{id}` | |
