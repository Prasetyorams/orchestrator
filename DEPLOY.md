# Memasang Open Orchestrator di server

Panduan langkah demi langkah untuk memasang Open Orchestrator di server
Linux, ditulis untuk yang belum pernah melakukannya. Daftar periksanya juga
tersedia sebagai lembar kerja: [DEPLOY-checklist.xlsx](DEPLOY-checklist.xlsx).

Perintah diketik di server lewat SSH, kecuali yang ditandai **(di laptop)**.

---

## 0. Gambaran besarnya

```
Internet ──HTTPS──► Caddy (port 443) ──► frontend  (dasbor, :3000)
                                     └─► backend   (API, :8080) ──► PostgreSQL (:5432, hanya di dalam)
Studio / JakRunner ──HTTPS──► backend
```

Hanya Caddy yang terbuka ke internet. Port 3000, 8080, dan 5432 ditutup dari luar.

---

## 1. Yang perlu disiapkan

- [ ] **Server (VPS) Linux Ubuntu 24.04** — minimal 2 vCPU, RAM 4 GB, disk
  40 GB. Dengan RAM 2 GB, build Java dan Next.js sering gagal.
- [ ] **Domain**, dengan dua subdomain, misalnya:
  - `orchestrator.domainmu.com` → dasbor
  - `api.orchestrator.domainmu.com` → API
- [ ] **Record DNS tipe A** untuk kedua subdomain, mengarah ke IP publik server.
- [ ] **Akses SSH** ke server (IP, nama pengguna, kata sandi atau kunci).
- [ ] **Akses ke repo GitHub** (token atau kunci SSH bila repo-nya private).
- [ ] *(Kalau datanya dibawa)* laptop yang sekarang menjalankan Orchestrator.

---

## 2. Masuk dan rapikan server

**(di laptop)**, di PowerShell:

```bash
ssh root@IP_SERVER
```

Perbarui sistem dan buat pengguna biasa (jangan bekerja sebagai root):

```bash
apt update && apt upgrade -y
adduser deploy
usermod -aG sudo deploy
```

Keluar (`exit`), lalu masuk lagi: `ssh deploy@IP_SERVER`.

Firewall — hanya SSH, HTTP, dan HTTPS:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80
sudo ufw allow 443
sudo ufw enable
```

Pasang Docker:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker deploy
```

Keluar dan masuk lagi, lalu `docker compose version` harus menampilkan versi.

- [ ] Server sudah diperbarui
- [ ] Firewall aktif, hanya port 22, 80, dan 443 yang terbuka
- [ ] Docker terpasang

---

## 3. Ambil kodenya

```bash
sudo mkdir -p /opt/openorchestrator && sudo chown deploy: /opt/openorchestrator
cd /opt/openorchestrator
git clone https://github.com/Prasetyorams/orchestrator.git app
cd app
```

---

## 4. Siapkan `signing.key` — JANGAN dilewati

Semua kredensial robot di basis data disandikan dengan kunci ini.

- **Kuncinya salah:** aplikasi tetap menyala, tetapi setiap kredensial
  terbaca kosong.
- **Berkasnya tidak ada:** Docker membuat *folder* bernama `signing.key` dan
  backend gagal menyala.

Pilih satu:

**A. Instalasi baru (data mulai dari nol):**

```bash
mkdir -p /opt/openorchestrator/data
head -c 64 /dev/urandom | base64 -w0 > /opt/openorchestrator/data/signing.key
```

**B. Membawa data dari laptop** — salin berkas yang **sama persis**. Buat dulu
foldernya di server (`mkdir -p /opt/openorchestrator/data`), lalu **(di laptop)**:

```bash
scp "$env:LOCALAPPDATA\JakForge\ForgeHub\signing.key" deploy@IP_SERVER:/opt/openorchestrator/data/
```

Lalu di server — hanya pengguna di dalam container backend (uid 10001) yang
boleh membacanya:

```bash
sudo chown 10001:10001 /opt/openorchestrator/data/signing.key
sudo chmod 400 /opt/openorchestrator/data/signing.key
```

- [ ] `signing.key` ada, isinya satu baris teks acak
- [ ] **Salinannya disimpan di tempat aman lain** (password manager atau
  flashdisk). Kunci ini hilang = semua kredensial hilang.

---

## 5. Isi `.env`

```bash
cd /opt/openorchestrator/app
cp .env.example .env
```

Buat dua nilai acak (jalankan dua kali, catat hasilnya):

```bash
openssl rand -base64 48
```

`nano .env`, lalu isi (ganti domainnya):

```ini
OPENORCHESTRATOR_DATA=/opt/openorchestrator/data
JWT_SECRET=<hasil acak pertama>
DB_PASSWORD=<hasil acak kedua>
OPENORCHESTRATOR_TZ=Asia/Jakarta
CORS_ORIGINS=https://orchestrator.domainmu.com
NEXT_PUBLIC_API_URL=https://api.orchestrator.domainmu.com
```

Simpan: Ctrl+O, Enter, Ctrl+X.

- **`JWT_SECRET` wajib diganti.** Backend masih menerima nilai bawaan dari
  repo, dan siapa pun yang tahu nilai itu bisa memalsukan login.
- **`DB_PASSWORD` hanya dipakai saat volume DB dibuat pertama kali.**
  Mengubahnya belakangan tidak mengubah kata sandi yang sudah tersimpan.
- **`NEXT_PUBLIC_API_URL` "dibakar" ke dasbor saat build.** Kalau diubah,
  dasbor harus di-build ulang.

```bash
chmod 600 .env
```

- [ ] `JWT_SECRET` dan `DB_PASSWORD` acak, bukan `GANTI-SAYA`
- [ ] `CORS_ORIGINS` dan `NEXT_PUBLIC_API_URL` memakai `https://` dan domain yang benar

---

## 6. Tutup port langsung ke container

`docker-compose.yml` membuka port 5432, 8080, dan 3000 ke semua jaringan.
Di server, buat berkas override yang dibaca otomatis oleh Docker Compose:

```bash
nano docker-compose.override.yml
```

```yaml
services:
  db:
    ports: !reset []
  backend:
    ports: !override
      - "127.0.0.1:8080:8080"
  frontend:
    ports: !override
      - "127.0.0.1:3000:3000"
```

DB tidak terbuka sama sekali; backend dan frontend hanya bisa diakses dari
dalam server (oleh Caddy).

---

## 7. Nyalakan

> **Membawa data dari laptop?** Kerjakan dulu bagian **10**, jangan langkah ini.

```bash
docker compose up -d --build
```

Build pertama sekitar 5–15 menit (mengunduh dependensi Maven dan npm). Cek:

```bash
docker compose ps
curl -s localhost:8080/actuator/health
```

`db` dan `backend` harus **(healthy)**, `frontend` **Up**, dan `curl`
menjawab `{"status":"UP"}`. Kalau gagal: `docker compose logs backend --tail 100`.

---

## 8. HTTPS dengan Caddy

Caddy mengurus sertifikat HTTPS gratis secara otomatis.

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

`sudo nano /etc/caddy/Caddyfile`, hapus isinya, ganti dengan:

```
orchestrator.domainmu.com {
    reverse_proxy 127.0.0.1:3000
}

api.orchestrator.domainmu.com {
    request_body {
        max_size 200MB
    }
    reverse_proxy 127.0.0.1:8080
}
```

`max_size` membatasi unggahan paket dan berkas; naikkan kalau paketmu lebih besar.

```bash
sudo systemctl reload caddy
```

- [ ] `https://orchestrator.domainmu.com` membuka halaman login, dengan gembok di browser
- [ ] `https://api.orchestrator.domainmu.com/api/health` menjawab `"OpenOrchestrator"`

---

## 9. Login pertama (instalasi baru)

1. Masuk dengan `OO_Admin` / `openorchestrator`.
2. **Segera ganti kata sandinya** — kata sandi bawaan itu tertulis di README publik.
3. Buat akun untuk tiap orang dan untuk robot (peran **Robot**, bukan Administrator).

- [ ] Kata sandi `OO_Admin` sudah diganti

---

## 10. (Opsional) Pindahkan data dari laptop

**(di laptop):**

```bash
docker exec openorchestrator-db pg_dump -U openorchestrator -d openorchestrator -Fc -f /tmp/oo.dump
docker cp openorchestrator-db:/tmp/oo.dump oo.dump
scp oo.dump deploy@IP_SERVER:/opt/openorchestrator/
```

**Di server** — sesudah langkah 4 opsi **B**, 5, dan 6, tetapi **sebelum
backend pernah menyala**:

```bash
cd /opt/openorchestrator/app
docker compose up -d db
docker cp ../oo.dump openorchestrator-db:/tmp/oo.dump
docker exec openorchestrator-db pg_restore -U openorchestrator -d openorchestrator --no-owner /tmp/oo.dump
docker compose up -d --build
```

Urutannya penting: backend yang sudah pernah menyala di DB kosong sudah
membuat tabel dan admin sendiri, dan pemulihan akan bentrok. Kalau itu
terjadi, mulai lagi dari DB kosong dengan `docker compose down -v` — ini
MENGHAPUS data di server, jadi aman hanya selama datanya memang belum ada.

- [ ] Bisa login dengan akun lama
- [ ] Buka satu aset bertipe **Credential**: isinya terbaca, bukan kosong
  (bukti `signing.key`-nya benar)

---

## 11. Sambungkan Studio dan JakRunner

- **Studio:** alamat orchestrator → `https://api.orchestrator.domainmu.com`,
  lalu login dengan akunmu.
- **JakRunner:** di `jakrunner.json`, `forgeHubUrl` →
  `https://api.orchestrator.domainmu.com` (nama kuncinya memang masih
  `forgeHubUrl`); pakai akun robot.

- [ ] Robot muncul **online** di menu Robots
- [ ] Satu job percobaan dari dasbor berjalan sampai selesai

---

## 12. Cadangan otomatis

```bash
mkdir -p /opt/openorchestrator/cadangan
crontab -e
```

Tambahkan (setiap jam 02.00, simpan 14 hari terakhir):

```
0 2 * * * docker exec openorchestrator-db pg_dump -U openorchestrator -d openorchestrator -Fc > /opt/openorchestrator/cadangan/oo-$(date +\%F).dump && find /opt/openorchestrator/cadangan -name '*.dump' -mtime +14 -delete
```

- [ ] Besok pagi ada berkas `.dump` di folder cadangan
- [ ] Cadangan disalin berkala ke **luar** server (laptop atau cloud storage) —
  kalau server rusak, cadangan di dalamnya ikut hilang
- [ ] `signing.key` ikut dicadangkan; tanpa kunci itu cadangan DB bisa
  dipulihkan tetapi kredensialnya tidak terbaca
- [ ] Pemulihan sudah dicoba minimal sekali — cadangan yang belum pernah
  dicoba belum tentu bisa dipakai

---

## 13. Memperbarui ke versi baru

```bash
cd /opt/openorchestrator/app
docker exec openorchestrator-db pg_dump -U openorchestrator -d openorchestrator -Fc > ../sebelum-update-$(date +%F).dump
git pull
docker compose up -d --build
docker compose ps
```

Migrasi basis data (Flyway) berjalan otomatis saat backend naik.

---

## Checklist akhir

| | Periksa |
|---|---|
| ☐ | Dasbor terbuka lewat `https://` |
| ☐ | Port 5432, 8080, dan 3000 **tidak** bisa diakses dari luar (dari laptop, `curl http://IP_SERVER:8080` harus gagal) |
| ☐ | `JWT_SECRET` dan `DB_PASSWORD` acak; `.env` ber-chmod 600 |
| ☐ | Kata sandi `OO_Admin` sudah diganti |
| ☐ | `signing.key` ada di server **dan** salinannya tersimpan di tempat lain |
| ☐ | Cadangan harian berjalan, dan salinannya ada di luar server |
| ☐ | Robot online, satu job percobaan sukses |
| ☐ | Bila data dipindah: kredensial terbaca (tidak kosong) |

## Kalau ada masalah

| Gejala | Penyebab paling umum |
|---|---|
| Backend terus *restarting* | `signing.key` tidak ada (Docker membuat folder), atau `DB_PASSWORD` tidak cocok dengan volume lama — cek `docker compose logs backend` |
| Login di dasbor gagal / error CORS | `CORS_ORIGINS` tidak sama persis dengan alamat dasbor, termasuk `https://` |
| Dasbor memanggil alamat API yang salah | `NEXT_PUBLIC_API_URL` salah — perbaiki di `.env`, lalu `docker compose up -d --build frontend` |
| HTTPS tidak jalan | DNS belum mengarah ke IP server, atau port 80/443 belum dibuka |
| Kredensial terbaca kosong | `signing.key` di server berbeda dari yang dipakai saat kredensial dibuat |
| Build berhenti di tengah | RAM kurang — pakai server 4 GB atau tambahkan swap |
