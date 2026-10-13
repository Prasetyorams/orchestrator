# Memasang OpenOrchestrator di Windows Server (tanpa internet, tanpa Docker)

Untuk server Windows Server 2022 yang tidak punya internet dan tidak boleh
memakai Docker/WSL. Backend, dasbor, dan PostgreSQL berjalan langsung di
Windows; menyala sendiri saat server boot lewat Task Scheduler (akun SYSTEM).
Tidak memakai PowerShell maupun alat pihak ketiga (NSSM/WinSW) — hanya
`cmd`, Node, `schtasks`, `netsh`, dan `icacls` bawaan Windows.

Untuk server Linux dengan Docker, lihat [DEPLOY.md](DEPLOY.md).

## 1. Di laptop (yang ada internet)

**Susun paketnya:**

```
node tools\windows-server\susun-paket.mjs
```

Membangun jar backend (`mvn package`) dan dasbor (`npm run build`), lalu
menyusun `dist\OpenOrchestrator\`:

```
OpenOrchestrator\
├─ oo.cmd, oo.cjs              alat pasang/mulai/hentikan/periksa
├─ PASANG-WINDOWS-SERVER.md
├─ backend\openorchestrator-1.0.0.jar
├─ dasbor\                     server.js, .next\ (+ static), node_modules\, public\
└─ runtime\                    kosong — untuk versi ZIP portabel (lihat di bawah)
```

`data\` dan `log\` dibuat di server. Rahasia (sandi basis data, `JWT_SECRET`)
juga dibuat di server, bukan dibawa dari laptop.

**Unduh installer** (Windows x64), masing-masing satu berkas:

| Program | Versi | Untuk |
|---|---|---|
| Eclipse Temurin JDK (adoptium.net) | **25** | backend — versi lebih lama gagal dengan `UnsupportedClassVersionError` |
| Node.js (nodejs.org) | **22 LTS** | dasbor dan alat `oo` |
| PostgreSQL, installer EnterpriseDB (enterprisedb.com) | **17** | basis data |

**Kalau membawa data dari laptop**, siapkan juga:

- cadangan basis data baru (di dalam container lalu disalin keluar — mengalihkan `>` di PowerShell merusak berkas biner):
  ```
  docker exec openorchestrator-db pg_dump -U openorchestrator -Fc -f /tmp/oo.dump openorchestrator
  docker cp openorchestrator-db:/tmp/oo.dump oo.dump
  ```
- `signing.key` laptop (`%LOCALAPPDATA%\JakForge\ForgeHub\signing.key`, atau yang
  ditunjuk `OPENORCHESTRATOR_DATA` di `.env`). **Wajib** sepasang dengan cadangannya:
  tanpa kunci yang sama, semua kredensial di cadangan tidak bisa dibuka.
  Pindahkan lewat jalur aman — bukan chat atau email.

## 2. Di server

1. **Pasang** ketiga installer (butuh Administrator sekali):
   - Temurin: centang *Add to PATH* dan *Set JAVA_HOME*.
   - Node.js: bawaan saja. Lewati *Tools for native modules* (butuh internet).
   - PostgreSQL: catat sandi superuser `postgres` yang Anda buat. Lewati
     *Stack Builder* di akhir (butuh internet).
2. **Salin** folder `OpenOrchestrator` ke `C:\OpenOrchestrator`.
3. Kalau membawa data: buat `C:\OpenOrchestrator\data\` dan taruh
   `signing.key` di sana **sebelum** langkah berikut.
4. Buka **Command Prompt → Run as administrator**:

   ```
   cd /d C:\OpenOrchestrator
   oo pasang
   ```

   atau, dengan data dari laptop:

   ```
   oo pasang --dump D:\oo.dump
   ```

   `oo pasang` melakukan, berurutan:
   1. memeriksa Java 25, Node 22, jar, dan folder dasbor;
   2. membuat `data\backend.env` (sandi basis data dan `JWT_SECRET` acak) dan
      `data\dasbor.env`, lalu membatasi `data\` hanya untuk Administrators dan SYSTEM;
   3. membuat user dan basis data `openorchestrator` — **psql meminta sandi
      superuser `postgres`**; kalau ada `--dump`, memulihkannya;
   4. membuka firewall masuk TCP 8080 dan 3000, mendaftarkan tugas
      `OpenOrchestrator\Backend` dan `OpenOrchestrator\Dasbor` (saat boot, SYSTEM);
   5. menyalakan keduanya dan menunggu sampai backend `UP` dan dasbor menjawab.
      Menyala pertama kali, Flyway membuat atau memigrasikan tabel — 1–2 menit.

   Aman diulang: setelan yang sudah ada dipakai apa adanya.
5. Buka `http://<nama-server>:3000`. Kalau mulai dari kosong, masuk dengan
   admin awal dan **segera ganti sandinya**.
6. Arahkan Robot Agent, Studio, dan Open Assistant ke `http://<nama-server>:8080`.

## 3. Sehari-hari

| Perintah | |
|---|---|
| `oo periksa` | backend `UP`? dasbor menjawab? |
| `oo hentikan` / `oo mulai` | mematikan / menyalakan keduanya |
| `oo pulihkan D:\oo.dump` | memulihkan cadangan ke basis data **kosong** (backend dimatikan dulu) |

- Catatan proses: `log\backend.log`, `log\dasbor.log` (masing-masing sampai
  20 MB, ditambah satu cadangan `.1.log`). Proses yang mati dinyalakan lagi
  oleh penjaganya dalam 5–60 detik.
- **Memperbarui versi:** susun paket baru di laptop, lalu di server
  `oo hentikan`, ganti folder `backend\` dan `dasbor\`, `oo mulai`. Folder
  `data\` jangan disentuh. Migrasi basis data berjalan sendiri. Cadangkan dulu:
  `"C:\Program Files\PostgreSQL\17\bin\pg_dump" -h localhost -U openorchestrator -Fc -f D:\sebelum-update.dump openorchestrator`
  (psql/pg_dump meminta sandi user `openorchestrator` — `DB_PASSWORD` di `data\backend.env`).

## 4. Setelan (`data\backend.env`)

Dibaca Spring Boot sebagai `.properties` (`-Dspring.config.import=file:…/backend.env[.properties]`),
jadi **tanpa tanda kutip**, dan jalur ditulis `C:/…` (garis miring terbalik adalah
karakter escape). Yang sering diubah:

| Kunci | |
|---|---|
| `CORS_ORIGINS` | alamat dasbor seperti diketik pengguna, mis. `http://oo-server:3000,http://oo-server.kantor.local:3000`. Asal pertama juga tujuan pengalihan `/` dan `/assistant/connect`. |
| `OPENORCHESTRATOR_TZ` | zona waktu tampilan dan jadwal, bawaan `Asia/Jakarta` |
| `SERVER_PORT` | port API (bawaan 8080). Ubah juga `API_INTERNAL_URL` di `dasbor.env`. |
| `DB_URL` | kalau PostgreSQL di server lain |

`data\dasbor.env` (dibaca `node --env-file`): `PORT`, `HOSTNAME`, `API_INTERNAL_URL`.
Setelah mengubah: `oo hentikan` lalu `oo mulai`. Firewall menyesuaikan port
baru kalau `oo pasang` dijalankan lagi.

## 5. HTTPS (port sendiri, mis. 8443)

Untuk server yang port 443-nya sudah dipakai layanan lain. `oo` menjalankan
**gerbang** HTTPS kecil (Node bawaan, tugas `OpenOrchestrator\Gerbang`) di satu
port: `/api/…` dan `/actuator/…` diteruskan ke backend, selebihnya ke dasbor.
Backend dan dasbor lalu hanya menerima dari server itu sendiri (127.0.0.1).

**Yang diminta ke IT:**

- Sertifikat diterbitkan **CA kantor** (mis. AD Certificate Services), sebagai
  berkas `.pfx` (berisi kunci privat) beserta sandinya, dengan:
  - **SAN** memuat **setiap** alamat yang diketik pengguna dan robot: nama DNS
    (`DNS Name=oo.kantor.local`) dan/atau IP (`IP Address=10.1.2.3`). CN saja
    tidak cukup. Satu nama DNS lebih awet daripada IP.
  - **EKU** *Server Authentication*.
  - Saat mengekspor dari Windows: *Export private key*, **Include all
    certificates in the certification path if possible** (supaya sertifikat
    intermediate ikut dikirim — PC di jaringan tertutup tidak bisa
    mengunduhnya sendiri), enkripsi **AES256-SHA256**.
- Nama DNS-nya terdaftar di DNS kantor, mengarah ke server.
- Firewall: buka **TCP 8443** masuk (cukup itu saja; 3000 dan 8080 tidak perlu lagi).

**Atau CA milik sendiri, tanpa IT** — `tools/windows-server/sertifikat.ps1`, di
laptop (Windows PowerShell 5.1, `New-SelfSignedCertificate`):

```
cd tools\windows-server
powershell -ExecutionPolicy Bypass -File .\sertifikat.ps1 ca-baru -Rentang 10.114.0.0/16,10.119.0.0/16 [-Dns intra.bank]
powershell -ExecutionPolicy Bypass -File .\sertifikat.ps1 server -Nama oo-bjkt -Ip 10.114.40.108
powershell -ExecutionPolicy Bypass -File .\sertifikat.ps1 daftar
```

- `ca-baru` (sekali): root CA 10 tahun, **dibatasi** (name constraints) ke
  rentang IP `-Rentang` dan, bila diisi, nama di bawah `-Dns`. Kalau kunci
  root bocor, ia tetap tidak bisa dipakai memalsukan situs lain — Windows (juga
  klien .NET robot) dan OpenSSL/Node menolak sertifikat di luar batas itu.
  Kunci root tinggal di `Cert:\CurrentUser\My` laptop; cadangannya
  `%USERPROFILE%\oo-ca\root-cadangan.pfx` bersandi — pindahkan ke media offline.
- Klien mengimpor `%USERPROFILE%\oo-ca\klien\root.cer` ke *Trusted Root*
  **sekali** (`pasang-root.cmd` sebagai Administrator, atau GPO). Sesudah itu
  server baru — IP lain di dalam rentang — cukup `server -Nama … -Ip …` lalu
  `oo https`; klien tidak diubah.
- `server`: sertifikat 2 tahun (`-Hari`), SAN dari `-Ip`/`-Dns`, EKU server,
  root ikut di `.pfx`. IP/nama di luar batas root ditolak sebelum diterbitkan.
  Kunci server hanya ada di `.pfx`, tidak ditinggal di laptop.
- **Tentukan akhiran DNS sekarang bila bisa** (mis. domain AD kantor): root
  tanpa `-Dns` tidak sah untuk nama DNS apa pun, dan menambahkannya nanti
  berarti root baru (impor ulang sekali di klien). Begitu juga rentang IP:
  server di luar `-Rentang` butuh root baru — pilih rentang yang cukup lebar
  untuk server mendatang, tetapi tidak lebih lebar dari perlu.
- Memasang root sendiri di *Trusted Root* PC bank tetap keputusan keamanan:
  minta persetujuan tim keamanan.

**Memasang** (Command Prompt → Run as administrator, setelah `oo pasang`):

```
cd /d C:\OpenOrchestrator
oo https D:\oo.pfx --port 8443
```

`oo https` menanyakan sandi `.pfx` (tidak tampil di layar), lalu:

1. memeriksa sertifikatnya — sandi, alamat di SAN, masa berlaku, penerbit,
   **sidik jari SHA-256**, dan **rantai yang akan dikirim** (memperingatkan kalau
   intermediate tidak ikut);
2. memastikan port 8443 belum dipakai program lain (sebelum ada yang dimatikan);
3. menyalin sertifikat ke `data\oo.pfx`, menyimpan sandinya di `data\https.env`
   (hanya Administrators dan SYSTEM), mengisi `CORS_ORIGINS` dengan **semua**
   alamat (mis. `https://oo.kantor.local:8443,https://10.1.2.3:8443`), dan
   membuat backend serta dasbor hanya mendengar di 127.0.0.1;
4. menukar aturan firewall (8443 dibuka, 8080/3000 dihapus), mendaftarkan
   tugas gerbang, menyalakan semuanya, dan memeriksa.

Bawaannya semua nama DNS dan IP di SAN dipakai. Untuk memilih sendiri, atau
kalau SAN berupa wildcard (`*.kantor.local`): `--nama oo.kantor.local,10.1.2.3`
(dipisah koma; setiap alamat harus ada di sertifikat).

**Sesudahnya**, dasbor **dan** API ada di satu alamat: `https://oo.kantor.local:8443`
(atau IP yang ada di sertifikat). Robot Agent, Studio, dan Open Assistant
diarahkan ke alamat itu juga. Halaman `/` membuka dasbor langsung — tidak
dialihkan ke `http://…:3000`.

- PC pengguna dan robot harus memercayai CA kantor. Biasanya sudah otomatis
  lewat domain/GPO. Kalau peramban menampilkan peringatan sertifikat, yang
  kurang adalah kepercayaan pada CA di PC itu. **Jangan** mematikan
  pemeriksaan sertifikat di robot atau Studio.
- **Sertifikat diperbarui** (mendekati habis): jalankan `oo https D:\oo-baru.pfx`
  lagi. Masa berlakunya terlihat di keluaran perintah itu.
- **Kembali ke HTTP**: `oo https --lepas`.
- `oo periksa` kini juga memeriksa gerbang dan menyebut **sertifikat yang
  sedang aktif** (sidik jari SHA-256, alamat, penerbit, rantai, masa berlaku) —
  cocokkan dengan sidik jari yang dilihat robot. Catatannya di `log\gerbang.log`.
- Jangan pasang HTTPS langsung di backend (`server.ssl.*`): halaman `/` backend
  mengalihkan ke dasbor di `CORS_ORIGINS` (bawaannya `http://…:3000`).

## 6. Kalau policy perusahaan membatasi

- **Installer MSI tidak boleh:** ketiganya tersedia sebagai ZIP. Ekstrak ke
  `runtime\java` (berisi `bin\java.exe`), `runtime\node` (berisi `node.exe`),
  dan `runtime\pgsql` (berisi `bin\psql.exe`); `oo` memakai yang di `runtime\`
  lebih dulu. PostgreSQL versi ZIP perlu disiapkan dan didaftarkan sebagai
  layanan sendiri, sebagai Administrator:
  ```
  runtime\pgsql\bin\initdb -D C:\OpenOrchestrator\pgdata -U postgres -W -E UTF8 --locale=C
  runtime\pgsql\bin\pg_ctl register -N postgresql-17 -D C:\OpenOrchestrator\pgdata -S auto
  sc start postgresql-17
  ```
- **AppLocker/WDAC memblokir program:** minta IT mengizinkan penerbit
  *Eclipse Adoptium*, *OpenJS Foundation*, dan *EnterpriseDB* (ketiganya
  bertanda tangan digital), atau folder `C:\OpenOrchestrator\runtime\`.
- **Firewall diatur GPO:** `oo pasang` memberi peringatan dan tetap lanjut;
  minta IT membuka TCP 8080 dan 3000 masuk.
- **Task Scheduler tidak boleh menjalankan sebagai SYSTEM:** daftarkan ulang
  kedua tugas dengan akun layanan (Task Scheduler → Properties → *Change User*,
  *Run whether user is logged on or not*), dan beri akun itu izin penuh ke
  `data\` dan `log\`.
