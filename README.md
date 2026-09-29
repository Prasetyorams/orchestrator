# Open Orchestrator

Pusat kendali untuk robot, proses, pekerjaan, antrean, aset, dan catatan
jalannya automasi JakForge — pasangan orchestrator untuk JakForge Studio dan
JakRunner.

| Bagian | Tumpukan |
|---|---|
| `backend/` | Spring Boot 3.5, Java 25, Maven, PostgreSQL 17 (skema lewat Flyway) |
| `frontend/` | Next.js 15, TypeScript, Tailwind |
| `docker-compose.yml` | db + backend + frontend dalam satu perintah |

---

## Menjalankan di komputer sendiri

Butuh Docker Desktop.

```bash
cp .env.example .env      # lalu isi — setiap variabel dijelaskan di dalamnya
docker compose up -d --build
```

`signing.key` harus sudah ada di folder `OPENORCHESTRATOR_DATA` sebelum
menyalakan; cara membuatnya ada di `.env.example`.

| Alamat | Isinya |
|---|---|
| http://localhost:3000 | Dasbor |
| http://localhost:8080 | API |
| http://localhost:8080/actuator/health | Pemeriksaan kesehatan |

Masuk pertama kali: **OO_Admin** / **openorchestrator**. Akun ini dibuat
otomatis saat basis datanya masih kosong (setelan `openorchestrator.bootstrap`
di `backend/src/main/resources/application.yml`). Sandinya tertulis di repo
ini, jadi **ganti begitu masuk**.

Memasang di server sungguhan: lihat **[DEPLOY.md](DEPLOY.md)** (beserta
daftar periksanya, `DEPLOY-checklist.xlsx`).

### Pengembangan tanpa container backend

```bash
docker compose up -d db                       # basis data saja
cd backend && mvn spring-boot:run             # DB_PASSWORD diisi seperti di .env
cd frontend && npm install && npm run dev
```

Uji backend: `mvn test` di `backend/`. Pemeriksaan tipe frontend:
`node node_modules/typescript/bin/tsc --noEmit -p .` di `frontend/`.

---

## Menyambungkan Studio dan JakRunner

**Studio:** tab **Design** → grup **ForgeHub** (nama lama di Studio) → **Sambungkan**, isi alamat
API (`http://localhost:8080`) dan akunmu. **Terbitkan** mengirim proyek yang
terbuka sebagai paket, dan prosesnya langsung bisa dijalankan robot.

**JakRunner:** buat `jakrunner.json` di sebelah `JakRunner.exe`:

```json
{
  "forgeHubUrl": "http://localhost:8080",
  "username": "Robot_Saya",
  "password": "...",
  "robotName": ""
}
```

Nama kuncinya memang masih `forgeHubUrl` — JakRunner membacanya dengan nama
itu. Pakai akun robot (peran **Robot**), bukan akun admin. Robot mendaftar
sendiri pada denyut pertama; `robotName` kosong berarti
`<nama-mesin>-<nama-pengguna>`.

---

## Repo ini dan repo Studio

Orchestrator dulu tinggal di dalam repo Studio dan berdiri sendiri di repo
ini sejak 25 September 2026. Keduanya hanya bersambung lewat API HTTP.
Pemanggilnya ada di [repo Studio](https://github.com/Fahib16/Studio):

| Klien | Berkas di repo Studio |
|---|---|
| Studio (Terbitkan, Sambungkan) | `OpenRPA/ForgeHub/StudioForgeHubClient.cs` |
| JakRunner (denyut, pekerjaan, log) | `JakRunner/Core/ForgeHubClient.cs` |
| Activity kategori Orchestrator | `Custom.Orchestrator/Runtime/HubConnection.cs` |

Jadi yang harus dijaga di sini adalah **bentuk API-nya**: mengganti alamat
atau nama medan JSON tanpa menyesuaikan klien-klien itu mematahkan robot yang
sudah terpasang, dan gejalanya muncul di mesin robot, jauh dari sini.

Kontrak lengkapnya — yang berlaku sekarang (v1) dan usulan untuk Robot Agent
unattended (v2) — ada di **[ROBOT-API.md](ROBOT-API.md)**.
`tools/uji-api.ps1` memeriksa kontrak v1 terhadap backend yang berjalan.

---

## Susunan

```
backend/
  src/main/java/id/jakforge/openorchestrator/
    controller/     satu controller per sumber daya, di bawah /api/<sumber>
    service/        logika; menerima principal dan memeriksa folder
    repository/     semua SQL (JDBC, tanpa JPA)
    dto/            request (tervalidasi) dan response bertipe
    security/       JWT, izin per endpoint, SecretBox, hash kata sandi
    audit/          pencatatan jejak audit
    config/         setelan (@ConfigurationProperties), keamanan, penjadwal
    common/, model/ pembantu dan enum bersama
  src/main/resources/
    application.yml
    db/migration/   V1–V7 (Flyway; berkas yang sudah dijalankan tidak diubah)
  legacy/           kode JPA lama, tidak dikompilasi
frontend/
  app/              satu folder per halaman
  components/       Shell, TopNav, FolderSidebar, NavBar, dialog, tabel
  lib/              klien API, izin, folder, tema, bahasa (kamus en/jv)
tools/
  buat-ikon.mjs     membangkitkan favicon.ico dan apple-icon.png dari icon.svg
  uji-*.ps1         uji API manual; butuh data proses yang sudah ada
```

---

## Catatan penting

- **`signing.key`** menyandikan kredensial di basis data. Kunci yang berbeda
  membuat semua kredensial terbaca kosong, jadi cadangkan bersama basis
  datanya dan jangan pernah dibuat ulang untuk data yang sudah ada.
- Hash kata sandi dan bentuk sandi kredensial sengaja sama dengan versi
  .NET lama (yang sudah dihapus dari repo ini), supaya data yang dipindah
  dari sana tetap terbaca; `DotNetCompatTest` menjaganya.
- Flyway V1–V6 tidak boleh disunting — checksum-nya sudah tercatat di setiap
  basis data yang pernah menjalankannya. Perubahan skema selalu berupa
  migrasi baru.
