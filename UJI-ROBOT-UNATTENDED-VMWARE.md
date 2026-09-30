# Panduan: menguji Robot Agent unattended di VMware Anda

Catatan dari tim Studio/robot (JakForge) untuk tim Open Orchestrator. Tujuannya:
Anda bisa menyalakan satu VM Windows sendiri, menyambungkannya ke orchestrator
Anda, dan melihat robot **unattended** mengambil lalu menjalankan job — seperti
UiPath. Semua yang di sini sudah kami jalankan di VMware kami memakai kontrak v2
(`/api/agent/*`) yang Anda buat.

Biner robotnya ada di repo Studio: <https://github.com/Fahib16/Studio> (branch
`main`) — proyek `JakForge.Robot.Agent` (service agent) dan `JakRunner` (executor
`--execute`). Orchestrator tidak perlu perubahan untuk ini; kontrak v2 + V9 sudah
cukup.

---

## 0. Yang dibutuhkan

- **Orchestrator Anda jalan** dan bisa dijangkau dari VM (mis. `http://<host>:8080`,
  atau HTTPS lewat proxy — lihat §6).
- **VM Windows** dengan salah satu profil:
  - **Sesi biasa (auto-logon):** Windows 10/11 Pro **atau** Server. Cukup untuk
    menjalankan job di sesi konsol akun robot.
  - **From-scratch (buat sesi dari nol tanpa ada yang login) dan takeover
    multi-sesi:** **Windows Server** (2019/2022), Remote Desktop **aktif**, akun
    robot anggota grup **Remote Desktop Users**. Di OS **klien** hanya ada SATU
    sesi interaktif — sama seperti batasan UiPath — jadi di klien pakai auto-logon.
- **Akun Windows khusus robot** (mis. `robot`).

---

## 1. Bangun biner Robot Agent + JakRunner

Dari repo Studio (`main`):

- **JakForge.Robot.Agent** → `JakForge.Robot.Agent.exe` (+ `.exe.config`),
  `JakForge.Robot.Core.dll`, `Newtonsoft.Json.dll`, dan interop RDP
  `AxInterop.MSTSCLib.dll` + `MSTSCLib.dll` (milik Microsoft, untuk RDP loopback).
  Salin keenamnya ke satu folder di VM (mis. `C:\Program Files\JakForge Robot V2`).
- **JakRunner.exe** (executor) — terpasang lewat installer JakForge Studio di akun
  robot, atau salin dari output build. Ini yang benar-benar menjalankan workflow.

---

## 2. Buat mesin + machine key + robot di dasbor Anda

1. **Tenant › Robots › Machines:** buat mesin. Namanya harus **persis** sama dengan
   `Environment.MachineName` VM (agent mengirim nama itu di denyut; job untuk mesin
   tertentu hanya diklaim mesin yang cocok — V9). Salin **machine key** `oo_mk_...`
   (tampil sekali; kunci baru **membatalkan** yang lama → **satu kunci aktif per
   mesin**).
2. **Tenant › Robots:** buat robot unattended. Isi `windowsUsername` (`.\robot`
   atau `DOMAIN\user`) dan pilih **satu**:
   - `windowsPasswordLocal = true` → robot sudah login sendiri (auto-logon); agent
     **tidak** meminta sandi.
   - `windowsPasswordLocal = false` **+ simpan sandi Windows** (write-only) →
     orchestrator menyerahkan sandi **per job** (`POST /jobs/{id}/windows-credential`),
     dan agent **membuat / membuka / mengambil-alih** sesi lewat RDP loopback.
   - `sessionPolicy`: `KeepLoggedIn` atau `Logoff`.

---

## 3. Pasang agent sebagai Windows service

Di VM, PowerShell **Administrator**:

```powershell
# 1) Konfigurasi (machine key ditempel lewat stdin, tidak jadi argumen)
$key | & "C:\Program Files\JakForge Robot V2\JakForge.Robot.Agent.exe" configure `
  --service --url http://<HOST_ORCHESTRATOR>:8080 --contract-mode v2 `
  --machine-key-stdin --machine-name <NAMA_MESIN> --session-user robot `
  --executor "C:\Users\robot\AppData\Local\Programs\JakForge Studio\JakRunner.exe"
#  (HTTPS self-signed: tambahkan  --server-cert-sha256 <SIDIK_JARI_SHA256_DER>)

# 2) Buat service (LocalSystem, auto-start) + pemulihan
New-Service -Name JakForgeRobotAgent -DisplayName 'JakForge Robot Agent' `
  -BinaryPathName '"C:\Program Files\JakForge Robot V2\JakForge.Robot.Agent.exe" service' `
  -StartupType Automatic
sc.exe failure JakForgeRobotAgent reset= 86400 actions= restart/5000/restart/15000/restart/60000

# 3) Uji sambungan lalu nyalakan
& "C:\Program Files\JakForge Robot V2\JakForge.Robot.Agent.exe" test-connection --service
sc.exe start JakForgeRobotAgent
```

`test-connection --service` melakukan health + login v2. Kalau gagal, periksa
URL/port, machine key, dan (untuk HTTPS) pin sertifikat.

---

## 4. Amati di dasbor

- Mesin **agentOnline = true**; robot **AVAILABLE/Idle**, sesi **Active**, ready.
- Buat job (Start Job atau dari dasbor). Ikuti keadaan:
  `PENDING → ASSIGNED → PREPARING_SESSION → RUNNING → SUCCESSFUL`.
  `PREPARING_SESSION` yang agak lama = agent sedang membuat/membuka sesi lewat RDP.
- Log job masuk lewat `POST /api/agent/logs`. (Contoh paket uji: workflow yang
  membuka Notepad, mengetik, membaca balik — untuk membuktikan otomasi UI nyata.)

---

## 5. Skenario yang layak diuji (semua sudah LULUS di VMware kami)

| Skenario | Prasyarat | Yang terjadi |
|---|---|---|
| **Auto-logon (konsol)** | `windowsPasswordLocal=true`, akun robot auto-logon | Job jalan di sesi konsol. Paling sederhana. |
| **Sesi terkunci** | `pwLocal=false`, sandi tersimpan | Layar dikunci (Win+L) → agent ambil sandi → RDP loopback **membuka** sesi → job jalan. |
| **From-scratch** | **Windows Server**, `pwLocal=false` | Tidak ada yang login → agent **membuat sesi baru** dari sandi via RDP loopback → job jalan. |
| **Takeover ala UiPath** | **Windows Server**, `pwLocal=false` | Seseorang RDP masuk sebagai akun robot (mis. login manual aplikasi bertoken), lalu job dijalankan → penonton RDP **diputus** (`WTSDisconnectSession`) untuk kendali eksklusif, **sesi + aplikasinya tetap hidup** (bukan logoff). |
| **PRESHUTDOWN** | apa saja | Restart Windows saat job berjalan → job **STOPPED** rapi (bukan menggantung), service **auto-start** pulih setelah boot. |

---

## 6. HTTPS (opsional)

Agent bisa memin sidik jari SHA-256 sertifikat (DER) server, dengan verifikasi
TETAP menyala (rantai CA **atau** pin). Berguna kalau Anda menaruh reverse-proxy
TLS di depan `:8080`. Tambahkan `--server-cert-sha256 <hex 64 char>` di `configure`.
Agent **menolak** mengambil sandi Windows lewat `http://` ke mesin lain (hanya
`https://` atau loopback).

---

## 7. Fitur V9 di sisi robot

- **Prioritas Start Job:** `Inherited` / `Low` / `Normal` / `High` (tak peka huruf).
  Activity Start Job di Studio kini mengirim hanya nilai ini (`Inherited`/kosong =
  tak dikirim → server pakai bawaan proses).
- **Aksi job dari dasbor:**
  - **Matikan** (`StopJob` + `KillJob`): sudah ditangani agent v2 dan **teruji VM**.
    `KillJob` mematikan seketika tanpa menunggu `graceSeconds`.
  - **Jeda / Lanjutkan** (`PauseJob` / `ResumeJob`): **baru** diimplementasikan di
    sisi agent **v2** (Studio `main`) — **belum kami uji di VM**. Mohon diuji:
    - Denyut agent menyebut `pausedJobs: [{ "jobId": "...", "source": "dashboard" }]`
      saat job **benar-benar** ditahan (bukan sekadar diminta). Medan ini **tidak
      dikirim** kalau tak ada yang dijeda.
    - `PauseJob` menahan **sebelum activity berikutnya** (activity yang sedang
      berjalan diselesaikan dulu — Windows Workflow tak bisa menahan di tengah).
    - `ResumeJob` melanjutkan. Robot tetap **Busy** selama dijeda (masih memegang
      sesi), denyut tetap 5 detik jadi pemantau "robot diam" tidak terpicu.
    - `KillJob`/`StopJob` tetap bekerja selama dijeda (Stop mengalahkan jeda).

---

## 8. Gotcha yang kami temui

- **Satu machine key aktif per mesin.** Membuat key baru **membatalkan** yang lama
  → agent `401` → offline. Jangan mint key baru kalau sudah ada di dasbor.
- **from-scratch & multi-sesi butuh Windows Server + RDS.** OS klien hanya satu
  sesi interaktif (sama seperti UiPath) → pakai **auto-logon** di klien.
- **Cold-start JakRunner pertama ~1–3 menit** (kompilasi Roslyn ekspresi VB) —
  terlihat seperti hang, padahal bukan.
- **LogonFailed** → orchestrator menandai robot `needsAttention` dan berhenti
  meng-assign (sesuai kontrak). Bersihkan dengan **menyimpan ulang sandi Windows**
  robot di dasbor.
- **`agentOnline`** memakai jendela lease (~180 dtk); jangan dipakai sebagai bukti
  "baru saja boot" — pakai `lastAgentLoginAt`.

Ada pertanyaan atau butuh paket uji contoh (RobotSmoke/RobotUI) dan skrip
pemasangan service kami? Kabari — dengan senang hati kami bagikan.
