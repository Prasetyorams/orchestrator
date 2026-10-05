# Catatan: resolusi layar robot unattended diatur per robot di Orchestrator

> **Status (5 Okt 2026):** sisi Robot Agent sudah ditulis dan lulus uji unit (repo Fahib16/Studio,
> commit a448fe2). Belum diuji di VM, karena Orchestrator belum mengirim nilainya. Menunggu
> tanggapan tim Open Orchestrator.

Ditulis dari sisi robot (OpenStudio / Robot Agent) untuk tim Open Orchestrator.

## Latar belakang

Robot unattended di UiPath punya setelan per robot: **Resolution Width**, **Resolution Height**,
dan **Resolution Depth**. Nilai 0 berarti bawaan. Sesi Windows yang dibuat robot memakai
resolusi itu, sehingga selector, OCR, dan klik berkoordinat konsisten di mesin mana pun robot
berjalan.

Sekarang Robot Agent JakForge selalu membuat sesi unattended (RDP loopback) dengan ukuran
**1024x768** yang tertanam di kode. Banyak aplikasi kantor tidak nyaman di ukuran itu: menu
terpotong, dan elemen yang bergeser membuat selector rapuh.

**Keputusan pemilik produk:** resolusi diatur **di Orchestrator, per robot**, khusus robot
unattended. **Tidak** ada setelan resolusi di Open Assistant.

## Yang sudah dikerjakan di sisi robot

- `AgentRobot` membaca tiga kolom **opsional** dari `robots[]` di jawaban `POST /api/agent/login`:
  `resolutionWidth`, `resolutionHeight`, `resolutionDepth` (bilangan bulat).
  - Kalau kolom tidak ada, nilainya 0. Orchestrator versi sekarang **tidak rusak** dan
    perilakunya tetap 1024x768.
- Validasi di agent (`SessionDisplay`):

  | Kolom | Nilai sah | Kalau tidak sah |
  |---|---|---|
  | `resolutionWidth`, `resolutionHeight` | dua-duanya 0 (bawaan 1024x768), **atau** dua-duanya 200..8192 | kembali ke 1024x768; peringatan di log job |
  | `resolutionDepth` | 0 (bawaan klien RDP), 15, 16, 24, 32 | kedalaman bawaan; ukuran yang sah tetap dipakai; peringatan di log |

  Nilai salah **tidak menggagalkan job**.
- RDP loopback memakai nilai itu (`DesktopWidth`, `DesktopHeight`, `ColorDepth`).
  - Sesudah login, agent mencatat resolusi yang **benar-benar** dilaporkan Windows untuk sesi
    itu (WTS `WTSClientDisplay`). Dengan begitu, beda antara permintaan dan hasil terlihat di
    log.
- **Resolusi diganti di dasbor:** `RobotService.update` sudah menaikkan `settings_version`
  mesin. Agent melihatnya di denyut dan login ulang, lalu memuat robot[] baru.
  - Pada job berikutnya, koneksi loopback lama ditutup dan dibuka lagi dengan resolusi baru.
  - Sesi Windows dan aplikasinya tetap hidup selama jeda itu.
- **Robot `windowsPasswordLocal=true`** (sesi konsol, auto-logon, atau login manual): resolusi
  **tidak diterapkan**, karena sesi itu bukan buatan agent dan resolusinya mengikuti layar
  mesin.
  - Agent mencatat hal itu di log job, supaya admin tidak bingung.
  - Hal yang sama berlaku bila robot kebetulan sudah punya sesi **konsol** aktif.

## Yang diminta dari Orchestrator

1. **Migrasi** (mis. `V10__resolusi_robot.sql`):

   ```sql
   ALTER TABLE robots
       ADD COLUMN resolution_width  INTEGER NOT NULL DEFAULT 0,
       ADD COLUMN resolution_height INTEGER NOT NULL DEFAULT 0,
       ADD COLUMN resolution_depth  INTEGER NOT NULL DEFAULT 0;

   ALTER TABLE robots ADD CONSTRAINT robots_resolution_sah CHECK (
       ((resolution_width = 0 AND resolution_height = 0)
         OR (resolution_width BETWEEN 200 AND 8192 AND resolution_height BETWEEN 200 AND 8192))
       AND resolution_depth IN (0, 15, 16, 24, 32));
   ```

2. **API robot** (`CreateRobotRequest`, `UpdateRobotRequest`, `RobotService`, `RobotRepository`):
   - tambah `resolutionWidth`, `resolutionHeight`, `resolutionDepth` (`Integer`);
   - pada update, `null` berarti tidak diubah, sama seperti `sessionPolicy`;
   - validasi sesuai tabel di atas, dengan 400 dan pesan yang jelas;
   - kembalikan ketiganya di `GET` robot, supaya formulir bisa menampilkan nilai sekarang.
3. **Kontrak agent:**
   - `RobotRepository.findForMachine(machineId)` ikut memilih ketiga kolom.
   - `AgentService` menaruhnya di setiap objek `robots[]` jawaban login:

   ```json
   "robots": [
     {
       "id": "…", "name": "Robot_A", "windowsUsername": ".\\robot",
       "sessionPolicy": "Logoff", "windowsPasswordLocal": false,
       "resolutionWidth": 1920, "resolutionHeight": 1080, "resolutionDepth": 32
     }
   ]
   ```

   - Pastikan setiap perubahan resolusi menaikkan `settings_version` mesin robot. Hari ini ini
     sudah terjadi di `RobotService.update`; mohon dipertahankan. Tanpa itu, agent baru memakai
     nilai baru sesudah service dinyalakan ulang.
   - Klaim job (`POST /api/agent/jobs/next`) **tidak perlu** diubah. Agent membaca resolusi dari
     robot[] login.
4. **Dasbor**, di formulir Robot (`frontend/app/tenant/robots/page.tsx`, dekat pilihan Kebijakan
   sesi), tambahkan bagian **"Resolusi layar (unattended)"**:
   - isian **Lebar** dan **Tinggi** (angka, kosong/0 = bawaan);
   - pilihan **Kedalaman warna**: Bawaan, 32-bit, 24-bit, 16-bit, 15-bit;
   - teks bantuan: *"0 = bawaan agent (1024x768). Hanya berlaku untuk robot yang sesinya dibuat
     agent (sandi Windows disimpan di Orchestrator). Sesi konsol/auto-logon memakai resolusi
     layar mesin."*
   - Tambahkan juga ketiga kolom di tipe `Robot` di `frontend/lib/api.ts`.

## Cara menguji bersama

1. Setel robot `windowsPasswordLocal=false` (misalnya `VM-ROBOT-01`) ke 1920x1080, 32-bit.
2. Jalankan satu job.
3. Log agent (`ProgramData\JakForge\Robot\logs`) harus memuat:

   `Sesi Windows siap lewat RDP loopback … resolusi diminta 1920x1080 32-bit, tampilan sesi menurut Windows: 1920x1080 32-bit.`

4. Ganti ke 1366x768 tanpa menyalakan ulang service, lalu jalankan job lagi. Log harus memuat
   `Resolusi sesi robot berubah (…)`, dan tampilan sesi berganti.
5. Isi nilai salah lewat API, misalnya lebar saja. Job tetap jalan di 1024x768, dengan
   peringatan di log job.
