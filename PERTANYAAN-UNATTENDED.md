# Pertanyaan untuk Robot Agent Unattended

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

Jawaban:

**2.** Siapa yang login ke Windows? Agent memakai akun Windows yang disimpan
di **Orchestrator** (dikirim ke agent saat job mulai), atau akunnya disimpan
**lokal** di mesin (Credential Manager/DPAPI)?

Jawaban:

**3.** Mekanisme sesi apa yang dipakai: `OpenRPA.RDService` (RDP loopback),
`CreateProcessAsUser` ke sesi yang sudah ada, autologon, atau lainnya?

Jawaban:

**4.** Apakah satu mesin harus bisa menjalankan beberapa robot sekaligus
(misalnya Windows Server + RDS)? Kalau ya, target maksimalnya berapa?

Jawaban:

**5.** Satu agent melayani berapa robot? Satu agent per mesin yang mengurus
banyak robot/sesi, atau satu agent per robot?

Jawaban:

---

## B. Identitas dan autentikasi

**6.** Setuju agent memakai **machine key** (dibuat di Orchestrator,
dipasang sekali di mesin), bukan username/password akun pengguna?

Jawaban:

**7.** Nama robot sekarang otomatis `NAMAMESIN-namauser`. Tetap begitu,
atau robot didaftarkan dulu di Orchestrator lalu agent memakai nama/ID dari
sana?

Jawaban:

**8.** Di mana agent menyimpan token/kunci di mesin, dan apakah dilindungi
(DPAPI/Credential Manager)?

Jawaban:

---

## C. Heartbeat dan status

**9.** Heartbeat dikirim **per agent** (satu laporan berisi semua
robot/sesi) atau **per robot**? Dengan jeda berapa detik?

Jawaban:

**10.** Status apa saja yang bisa dilaporkan agent? Contoh: Online, Idle,
Busy, sesi Active/Locked/Disconnected/None, Executor Running/Stopped, versi
agent.

Jawaban:

**11.** Apakah agent bisa menerima **perintah balik lewat jawaban
heartbeat** (Stop, Kill, muat ulang setelan)? Atau lebih suka jalur lain
(polling endpoint tersendiri, WebSocket/SignalR)?

Jawaban:

---

## D. Siklus hidup job

**12.** Setuju dengan state berikut?
`Pending → Assigned (lease) → PreparingSession → Running → Successful / Faulted / Stopped`,
ditambah `Stopping`. Ada state lain yang dibutuhkan agent?

Jawaban:

**13.** Berapa lama paling lama agent butuh untuk **menyiapkan sesi** (login
Windows + start Executor)? Dipakai sebagai batas waktu lease sebelum job
dianggap gagal.

Jawaban:

**14.** Kode kegagalan apa yang ingin dilaporkan agent? Contoh:
`SessionPreparationFailed`, `ExecutorStartFailed`, `ExecutorCrashed`,
`WorkflowFailed`, `Timeout`.

Jawaban:

**15.** Data konteks eksekusi apa yang bisa dikirim agent: Windows Session
ID, Executor PID, nama user Windows, nomor percobaan?

Jawaban:

**16.** Stop: apakah agent mampu stop halus (minta workflow berhenti), lalu
kill paksa setelah jeda? Berapa jeda yang wajar?

Jawaban:

**17.** Kalau job gagal, perlu **retry otomatis** di Orchestrator? Kalau ya,
berapa kali dan untuk kode gagal apa saja? Atau selalu manual?

Jawaban:

**18.** Perlu batas waktu maksimal per job (job timeout) yang disetel di
Orchestrator per proses?

Jawaban:

---

## E. Sesi Windows dan kredensial

**19.** Kalau akun Windows disimpan di Orchestrator: formatnya `DOMAIN\user`
atau `user@domain`? Satu akun per robot, atau satu akun boleh dipakai
beberapa robot?

Jawaban:

**20.** Kapan agent mengambil sandi Windows: sekali saat start, atau setiap
job mulai? (Makin singkat tinggal di memori, makin aman.)

Jawaban:

**21.** Setelah job selesai, sesinya diapakan: dibiarkan login, di-lock,
di-disconnect, atau di-logoff? Perlu bisa disetel per robot dari
Orchestrator?

Jawaban:

---

## F. Log, hasil, dan berkas

**22.** Format log sekarang (`lines[]` tiap 3 detik: level, message,
robotName, machineName, processName, jobId, loggedAt) cukup, atau perlu
tambahan (nomor urut, sumber Agent/Executor, Session ID)?

Jawaban:

**23.** Apakah agent perlu mengunggah **screenshot** atau berkas saat job
gagal? Kalau ya, ukuran maksimalnya berapa?

Jawaban:

**24.** Output workflow dikirim sebagai JSON di `outputJson` — cukup begitu?

Jawaban:

---

## G. Pemulihan

**25.** Setelah agent restart, bisakah agent melaporkan job mana yang
**masih benar-benar berjalan**, supaya Orchestrator bisa membereskan job
"hantu"?

Jawaban:

**26.** Kalau jaringan putus saat job berjalan: workflow tetap jalan dan
laporannya dikirim ulang setelah tersambung? Berapa lama agent sanggup
menahan log dan status?

Jawaban:

**27.** Batas putus sekarang 45 detik (setelah itu job RUNNING dianggap
gagal). Terlalu pendek? Berapa yang realistis?

Jawaban:

---

## H. Paket, versi, dan instalasi

**28.** Apakah agent **mengunduh paket** dari Orchestrator
(`/api/packages/{nama}/{versi}/content`) sesuai versi di job? Sekarang
JakRunner hanya mencocokkan nama folder lokal.

Jawaban:

**29.** Agent mengirim versinya ke Orchestrator? Perlu aturan "versi agent
minimal" dari sisi Orchestrator?

Jawaban:

**30.** Bagaimana mesin didaftarkan: admin membuat mesin + key di dasbor lalu
memasangnya di installer, atau agent mendaftar sendiri saat pertama menyala?

Jawaban:

---

## I. Pengujian dan koordinasi

**31.** Kapan lingkungan VMware siap diuji melawan Orchestrator? Apakah VM
bisa mengakses Orchestrator (laptop atau server) lewat jaringan?

Jawaban:

**32.** Setuju sepakat kontrak dulu di dokumen `ROBOT-API.md` (ditulis dari
sisi Orchestrator, direview dari sisi robot) sebelum kedua sisi mulai
ngoding?

Jawaban:

**33.** Endpoint lama JakRunner harus tetap didukung sampai kapan?

Jawaban:

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
