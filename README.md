# ForgeHub — Orchestrator JakForge

Pusat kendali untuk robot, proses, pekerjaan, antrean, aset, dan catatan
jalannya automasi JakForge.

---

## Mulai di sini: `start-forgehub.cmd`

Klik dua kali **`start-forgehub.cmd`**, lalu buka <http://localhost:8080>.

    Nama pengguna : FH_Admin
    Kata sandi    : forgehub

Itu saja. Tidak ada basis data yang perlu dipasang, tidak ada layanan yang
perlu dinyalakan, tidak ada berkas setelan yang perlu diisi lebih dulu.

Yang berjalan adalah **`server/`** — ForgeHub di atas ASP.NET Core 10 dengan
SQLite. Sudah dibangun, dijalankan, dan diuji sampai ujung: Studio menerbitkan
proyek ke sana, ForgeHub menjadwalkan pekerjaan, JakRunner menjemput dan
menjalankannya, lalu hasilnya kembali ke dasbor.

Datanya di `%LOCALAPPDATA%\JakForge\ForgeHub` — di luar folder proyek, jadi
tidak ikut terhapus saat proyek dibangun ulang.

### Menyambungkan JakRunner

Buat `jakrunner.json` di sebelah `JakRunner.exe`:

```json
{
  "forgeHubUrl": "http://localhost:8080",
  "username": "FH_Admin",
  "password": "forgehub",
  "robotName": ""
}
```

Robot mendaftarkan dirinya sendiri pada denyut pertama. Kosongkan `robotName`
untuk memakai `<nama-mesin>-<nama-pengguna>`.

### Menyambungkan Studio

Di Studio: tab **Design** → grup **ForgeHub** → **Sambungkan**, isi alamat dan
kredensialnya. Setelah itu **Terbitkan** mengirim proyek yang sedang terbuka
sebagai paket, dan prosesnya langsung siap dijalankan robot.

---

## Dua penerapan dalam satu folder

| | `server/` | `backend/` + `frontend/` |
|---|---|---|
| Tumpukan | ASP.NET Core 10, SQLite | Spring Boot 3.5, Next.js 15, PostgreSQL |
| Perlu dipasang | tidak ada (SDK .NET saja) | JDK 25, Maven, Node, Docker, PostgreSQL |
| Keadaan | **dibangun dan diuji jalan** | **belum pernah dikompilasi** |
| Untuk apa | satu mesin, pemakaian nyata sekarang | penyebaran banyak mesin nanti |

Keduanya berbicara **API yang sama** dan memakai bentuk tabel yang sama — nama
kolom, indeks yang selalu diawali `tenant_id`, keadaan pekerjaan yang sama.
Pindah dari yang satu ke yang lain nanti tidak mengubah Studio maupun JakRunner.

**Peringatan tentang `backend/` dan `frontend/`:** kode Java dan Next.js di
folder ini belum pernah dikompilasi atau dijalankan. Mesin tempat ia ditulis
tidak punya Java, Maven, Node, npm, maupun Docker. Untuk bagian itu yang bisa
saya janjikan hanya kode yang ditulis dengan hati-hati, bukan bukti dari sebuah
build yang hijau — jadi bacalah sisa dokumen ini sebagai rencana penerapan,
bukan sebagai catatan sesuatu yang sudah terbukti berjalan.

---

## Rencana penerapan, langkah demi langkah

Ini urutan yang saya ikuti saat menulisnya, dan urutan yang sama yang saya
sarankan saat Anda menelaahnya.

**1. Basis data lebih dulu.** `V1__init.sql` mendefinisikan seluruh tabel, dan
Hibernate disetel `ddl-auto: validate` — skema dikelola Flyway, bukan
Hibernate. Membiarkan Hibernate mengubah skema berarti bentuk basis data
bergantung pada versi kode yang kebetulan jalan terakhir, dan itu tidak bisa
ditinjau sebelum dijalankan.

**2. Pemisahan tenant di lapisan skema.** Setiap tabel data membawa
`tenant_id`, dan setiap indeks diawali `tenant_id`. Pemisahan yang hanya
diperiksa di kode akan bocor pada kueri pertama yang lupa menyaringnya.

**3. Autentikasi.** Login memeriksa BCrypt lalu mengeluarkan JWT yang membawa
`tenantId`. Setiap service membaca tenant dari token, TIDAK PERNAH dari badan
permintaan — nilai yang datang dari klien tidak boleh menentukan data siapa
yang terlihat.

**4. API baca dulu, tulis kemudian.** Dashboard, Jobs, Robots, Queues, Assets,
Processes, dan Logs sudah lengkap. Formulir untuk Environments, Credentials,
Packages, Libraries, Tenants, dan Settings belum — tabelnya ada, endpoint-nya
belum, dan layarnya mengatakan itu apa adanya alih-alih memajang tabel kosong
yang tampak rusak.

**5. Penerimaan log.** JakRunner dan Studio mengirim per bundel ke
`POST /api/logs`. Panel Real-Time Logs menariknya kembali dengan `afterId`,
jadi setiap penarikan hanya membawa baris yang benar-benar baru.

**6. Frontend menyusul API.** Setiap layar memakai React Query dengan
`refetchInterval` yang sesuai isinya: 2 detik untuk log, 5 detik untuk job,
10 detik untuk ringkasan dan robot.

**7. Docker paling akhir**, setelah keduanya berdiri sendiri.

---

## Menjalankan

```bash
cd ForgeHub
cp .env.example .env      # lalu GANTI JWT_SECRET
docker compose up --build
```

| Alamat | Isinya |
|---|---|
| http://localhost:3000 | ForgeHub |
| http://localhost:8080 | API |
| http://localhost:8080/actuator/health | Pemeriksaan kesehatan |

Masuk pertama kali: **FH_Admin** / **forgehub**

Sandi itu ada di dalam `V2__seed.sql` yang tersimpan di repositori ini, jadi ia
bukan rahasia bagi siapa pun yang bisa membaca kodenya. Ganti begitu Anda masuk.

### Tanpa Docker

```bash
# Basis data
docker run -d --name forgehub-db -p 5432:5432 \
  -e POSTGRES_DB=forgehub -e POSTGRES_USER=forgehub -e POSTGRES_PASSWORD=forgehub \
  postgres:17-alpine

# Backend
cd backend && mvn spring-boot:run

# Frontend
cd frontend && npm install && npm run dev
```

---

## Susunan

```
ForgeHub/
  backend/                     Spring Boot, Java 25, Maven
    src/main/java/id/jakforge/forgehub/
      auth/                    login dan token
      common/                  entity dasar, penanganan kesalahan, dashboard
      config/                  security dan Jackson
      security/                JWT
      robot/                   robot, machine, environment, credential
      process/                 process dan package
      job/                     job dan trigger
      queue/                   antrean dan itemnya
      asset/                   aset
      log/                     penerimaan dan pembacaan log
    src/main/resources/
      application.yml
      db/migration/            V1 skema, V2 data awal
  frontend/                    Next.js 15, TypeScript, Tailwind
    app/                       satu folder per layar
    components/                Sidebar, TopNav, RealTimeLogs, komponen dasar
    lib/api.ts                 klien HTTP dan tipe balasan
  docker-compose.yml
```

Komponen dasar (Card, Badge, Button, Table) ditulis langsung di
`components/ui/primitives.tsx` dengan gaya shadcn/ui. shadcn/ui memang bekerja
dengan cara menyalin komponennya ke dalam proyek, bukan dipasang sebagai
dependensi — jadi bentuk akhirnya sama, tanpa menuntut `npx shadcn add`
dijalankan lebih dulu sebelum proyek ini bisa dibangun.

---

## API

| Metode | Alamat | Gunanya |
|---|---|---|
| POST | `/api/auth/login` | Masuk, menghasilkan JWT |
| GET | `/api/auth/me` | Siapa yang sedang masuk |
| GET | `/api/dashboard/summary` | Angka untuk kartu ringkasan |
| GET | `/api/robots` | Daftar robot |
| POST | `/api/robots/{name}/heartbeat` | Denyut dari JakRunner atau Studio |
| GET | `/api/processes` | Daftar proses |
| GET | `/api/jobs` | Daftar pekerjaan |
| POST | `/api/jobs` | Mulai pekerjaan |
| PATCH | `/api/jobs/{id}` | Robot melaporkan perubahan keadaan |
| GET | `/api/queues` | Antrean beserta hitungannya |
| POST | `/api/queues/{id}/next` | Ambil satu item untuk dikerjakan |
| GET | `/api/assets` | Daftar aset |
| POST | `/api/logs` | Kirim bundel log |
| GET | `/api/logs?afterId=` | Tarik log yang lebih baru |

Semuanya menuntut `Authorization: Bearer <token>` kecuali `login` dan
`actuator/health`.

---

## Menyambungkan JakRunner dan Studio

Robot mengirim denyut dan log lewat dua panggilan ini:

```
POST /api/robots/{namaRobot}/heartbeat
  { "status": "AVAILABLE", "cpuPercent": 12.5, "memoryMb": 340 }

POST /api/logs
  { "lines": [ { "message": "...", "level": "INFO", "robotName": "...", "jobId": "..." } ] }
```

Keduanya sengaja dibuat sesederhana ini supaya bisa dipanggil dari mana saja —
termasuk dari activity di dalam workflow.

---

## Kalau gagal dibangun

Yang paling mungkin, sesuai urutan kemungkinannya:

1. **Java 25 belum tersedia di image Maven.** Java 25 baru; kalau
   `maven:3.9-eclipse-temurin-25` belum ada, turunkan ke `-21` di
   `backend/Dockerfile` dan ubah `<java.version>` di `pom.xml` menjadi `21`.
   Tidak ada kode di sini yang memakai fitur khusus Java 25.
2. **Versi Spring Boot.** `pom.xml` memakai 3.5.0. Kalau versinya belum ada di
   repositori Anda, pakai versi 3.x terbaru yang ada.
3. **`package-lock.json` belum ada.** Jalankan `npm install` sekali di
   `frontend/` supaya terbentuk, lalu bangun ulang image-nya.
4. **Rentang versi npm.** `package.json` memakai `^`, jadi versi minor terbaru
   yang diambil. Kalau ada yang bentrok, kunci ke versi pastinya.
