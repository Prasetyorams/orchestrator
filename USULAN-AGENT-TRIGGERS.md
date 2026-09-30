# Usulan: daftar pemicu (jadwal) untuk agent machine key

Untuk tim Open Orchestrator. Ditulis 30 Sep 2026, dari sisi robot (JakForge Studio / Open Assistant).
Kontrak yang dirujuk: `ROBOT-API.md` di repo orchestrator (commit `07979fe`).

## Latar

Open Assistant kini menyambung ke Orchestrator dengan **machine key** (kontrak v2, `/api/agent/*`),
juga di PC attended: tanpa service Robot Agent, Open Assistant sendiri yang login, berdenyut,
mengambil job, dan melapor — dengan loop yang sama dengan service unattended.

Jalur lama v1 (akun pengguna + sandi di `jakrunner.json`, `/api/auth/login`) ingin kami hapus
supaya satu PC hanya punya satu cara menyambung. Satu fitur Open Assistant masih bergantung pada
v1 dan belum punya padanan v2:

- **Halaman Jadwal** membaca `GET /api/triggers` (izin `triggers.read`). Token agent hanya
  berlaku di `/api/agent/**` (ROBOT-API 2.2), jadi dengan machine key halaman ini kosong.

Sampai ada padanannya, jalur v1 **tidak** kami hapus. Di Open Assistant, jalur v1 hanya aktif
kalau machine key belum disambungkan; begitu disambungkan, v1 dimatikan dan Jadwal kosong.

## Usulan 1 (yang kami butuhkan): `GET /api/agent/triggers`

Dengan token agent. Hanya membaca; mengubah pemicu tetap lewat dasbor.

→ `200`
```json
{
  "triggers": [
    {
      "name": "Tagihan harian",
      "folderId": "…",
      "processName": "Tagihan",
      "robotId": "…",
      "robotName": "Robot_A",
      "cron": "0 0 8 * * MON-FRI",
      "intervalMinutes": 0,
      "timezone": "Asia/Jakarta",
      "enabled": true,
      "nextRunAt": "2026-10-01T08:00:00+07:00"
    }
  ]
}
```

| Aturan | Keterangan |
|---|---|
| Cakupan | Pemicu yang **bisa dijalankan di mesin ini**: menargetkan salah satu robot mesin ini (sama dengan `robots` di jawaban login), atau tidak menargetkan robot tertentu tetapi prosesnya ada di folder robot mesin ini. Pemicu penyewa lain tidak pernah ikut. |
| Medan | Sama dengan `GET /api/triggers` yang sekarang dibaca Open Assistant (`name`, `processName`, `robotName`, `cron`, `intervalMinutes`, `enabled`, `nextRunAt`), ditambah `robotId` (supaya cocok dengan `robots[].id` login) dan `timezone` kalau cron punya zona waktu sendiri. `robotId`/`robotName` null = robot mana pun. |
| Kosong | `200 {"triggers": []}`, bukan `404`. |
| Galat | `401` token kedaluwarsa (agent login ulang, seperti endpoint agent lain). |
| Beban | Open Assistant membacanya saat halaman Jadwal dibuka dan paling sering tiap 60 detik. Kalau lebih disukai, `heartbeat` boleh membawa `triggersVersion` supaya agent hanya membaca ulang saat berubah (opsional). |

Izin: cukup token agent; tidak perlu izin dasbor baru. Kalau tim ingin membatasi, kami usulkan
setelan mesin "tampilkan jadwal ke agent" (bawaan: ya).

## Pertanyaan untuk tim Orchestrator

1. Apakah pemicu selalu menargetkan satu robot, atau bisa "robot mana pun di folder ini"? Aturan
   cakupan di atas mengikuti jawabannya.
2. Zona waktu `cron`: zona server, zona penyewa, atau per pemicu?
3. Apakah `nextRunAt` sudah dihitung server (seperti di `GET /api/triggers`)? Kami ingin tetap
   memakai hitungan server, bukan menghitung cron sendiri.

## Tidak perlu perubahan server (sisi robot, untuk diketahui)

- **Activity Orchestrator (antrean, aset, bucket) di job v2**: sudah bekerja dengan
  `executorToken` (ROBOT-API 2.6). Agent menulisnya ke `session.json` di folder job, runtime
  membacanya lalu menghapusnya, dan activity memakai token itu (`HubConnection.UseSession`),
  bukan akun di `jakrunner.json`. Tidak ada yang diminta dari server.
- **Activity Orchestrator di run manual** (tombol Play, bukan job): tidak ada token job, jadi
  activity ini tetap memakai login Studio (`forgehub.json`). Itu disengaja; tidak diminta
  endpoint baru.
- **Log dari run manual** (tombol Play di Open Assistant, bukan job dari dasbor): v2 hanya
  menerima log ber-`jobId`. Kami **tidak** meminta endpoint baru; log run manual cukup tetap di
  PC (berkas log harian). Kalau tim ingin melihatnya di dasbor, beri tahu kami dan kita bahas
  bentuknya terpisah.

## Yang dilakukan sisi robot begitu endpoint tersedia

1. `OpenOrchestratorAgentClient.GetTriggers()` + uji kontrak.
2. Halaman Jadwal Open Assistant membaca dari agent (attended: di proses Open Assistant; service:
   agent menulisnya ke folder publik status, tanpa rahasia) — tanpa akun pengguna.
3. Jalur v1 `jakrunner.json` dihapus dari Open Assistant.
