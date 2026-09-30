# Catatan: identitas yang ditampilkan Open Assistant (machine key vs akun)

Untuk tim Open Orchestrator. Ditulis 30 Sep 2026 dari sisi robot, sesudah uji Fajar di PC attended
(machine key tersambung ke `http://localhost:3000`, mesin DESKTOP-ILR0BGM, **belum ada robot yang
diikat ke mesin itu**). Kontrak yang dirujuk: `ROBOT-API.md` (commit `07979fe`).

## Yang ditampilkan Open Assistant sekarang

Mengikuti UiPath Assistant, yang menampilkan *identitas robot/pengguna*, bukan nama komputer:

| Cara tersambung | Nama di menu samping | Keterangan |
|---|---|---|
| Machine key, ada robot diikat ke mesin | `robots[].name` dari jawaban `POST /api/agent/login` | Nama mesin (`machine.name`) di tooltip |
| Machine key, **belum ada robot** | `machine.name` dari jawaban login | Status "Belum ada robot" (kuning) |
| Masuk lewat dasbor (usulan `USULAN-ASSISTANT-SIGNIN.md`) | `user.displayName` / `user.username` | Robot di tooltip |
| Belum tersambung | akun Windows | — |

Semua nama diambil dari jawaban Orchestrator, bukan dari isian di Open Assistant. Isian "Nama mesin"
di Open Assistant hanya dikirim sebagai `machineName` saat login (dipakai server untuk peringatan
"kunci dipakai dari komputer lain"), dan sesudah tersambung diganti dengan `machine.name`.

## Temuan dan permintaan

1. **Robot attended dengan machine key.** `ROBOT-API.md` 2.2: "Robot `Attended` tidak pernah
   diikat ke mesin". Akibatnya PC kerja (attended) yang disambungkan dengan machine key harus
   memakai robot bertipe unattended/mesin, dengan akun Windows = orang yang memakai PC itu. Open
   Assistant hanya memberi job ke robot yang akun Windows-nya sama dengan pengguna yang sedang masuk
   (aturan yang sama dengan agent service). Pertanyaan: apakah itu memang model yang diinginkan
   untuk PC attended, atau PC attended sebaiknya **hanya** lewat "Masuk lewat dasbor" (identitas =
   pengguna), seperti UiPath? Jawaban tim menentukan pilihan mana yang kami tonjolkan di halaman
   Hubungkan.

2. **Mesin tanpa robot.** Login machine key berhasil walaupun belum ada robot yang diikat ke mesin
   (`robots: []`); Open Assistant tersambung tetapi tidak akan pernah mendapat job. Kami sudah
   menampilkannya ("Belum ada robot"). Saran untuk dasbor: di Robots → Machines, tandai mesin yang
   online tetapi tidak melayani robot mana pun, supaya admin tahu langkah berikutnya.

3. **Nama komputer vs nama mesin.** `machineName` di login = nama yang diketik orang di Open
   Assistant (biasanya nama komputer). Saran: halaman Machines menampilkan `agentHostName` terakhir
   di samping nama mesin, supaya ketidakcocokan terlihat tanpa membuka log.

4. **Pengingat:** `USULAN-AGENT-TRIGGERS.md` (PR #3) dan `USULAN-ASSISTANT-SIGNIN.md` masih
   menunggu tanggapan. Dengan "Masuk lewat dasbor", PC attended mendapat identitas pengguna dan
   halaman Jadwal (izin `triggers.read`) tanpa endpoint pemicu khusus agent.
