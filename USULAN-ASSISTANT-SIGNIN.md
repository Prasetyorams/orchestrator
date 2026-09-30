# Usulan: Open Assistant masuk lewat dasbor (seperti UiPath Assistant)

Untuk tim Open Orchestrator. Ditulis 30 Sep 2026 dari sisi robot (JakForge Studio / Open Assistant).
Sisi Open Assistant **sudah ditulis** (belum terkompilasi, belum teruji); yang belum ada hanya sisi
server. Selama `/api/health` belum menyebut kemampuan `assistant.signin`, Open Assistant menjawab
jujur "Orchestrator ini belum mendukung masuk lewat dasbor" dan orang tetap bisa memakai machine key.

## Tujuan

Open Assistant yang belum tersambung menampilkan halaman **Hubungkan** dengan dua pilihan:

1. **Machine key** — sudah ada (kontrak v2, `/api/agent/*`).
2. **Masuk lewat dasbor** — orang masuk ke dasbor di peramban dengan akun Orchestrator-nya, dasbor
   menampilkan popup "Buka Open Assistant", klik → Open Assistant tersambung **sebagai pengguna
   itu** (robot attended: identitasnya orang, bukan mesin — sejalan dengan jawaban kami di
   `ROBOT-API-tanggapan-robot.md`, pertanyaan 3).

Tidak ada sandi yang diketik di Open Assistant, dan tidak ada token di URL.

## Alur (OAuth 2.0 untuk aplikasi desktop: RFC 8252 + PKCE RFC 7636)

```
Open Assistant                      Peramban / dasbor                       Orchestrator API
  │ state, code_verifier (acak, di memori)
  │ buka /assistant/connect?… ───────► belum masuk? → halaman login biasa
  │                                    popup: "Sambungkan Open Assistant di
  │                                    DESKTOP-X sebagai Fajar?"  [Buka Open Assistant] [Batal]
  │                                    klik → kode sekali pakai ──────────────► (simpan: kode, user,
  │ ◄── openassistant://signin?code=…&state=…                                   challenge, 60 dtk)
  │ cocokkan state
  │ POST /api/auth/assistant/token {code, codeVerifier} ──────────────────────► cek SHA256(verifier)
  │ ◄───────────────────────────────── {token, refreshToken, user, robotName}
  │ simpan tersandi DPAPI; denyut/klaim job v1 dengan token itu
```

### 1. Kemampuan di `GET /api/health`

Tambahkan `"assistant.signin"` ke daftar `capabilities`. Open Assistant memeriksa ini sebelum
membuka peramban.

### 2. Halaman dasbor `GET /assistant/connect`

Parameter kueri (dikirim Open Assistant):

| Parameter | Isi |
|---|---|
| `client` | `open-assistant` |
| `state` | acak 256-bit (base64url) — **dikembalikan apa adanya** di redirect |
| `code_challenge` | `BASE64URL(SHA256(code_verifier))` |
| `code_challenge_method` | `S256` (tolak selain ini) |
| `redirect_uri` | `openassistant://signin` — **hanya nilai ini yang diterima** (daftar putih) |
| `machine` | nama komputer, untuk ditampilkan di popup |

Perilaku:
- Belum masuk → halaman login dasbor biasa, lalu kembali ke halaman ini dengan parameter yang sama.
- Sudah masuk → popup: *"Sambungkan Open Assistant di **{machine}** sebagai **{nama pengguna}**?"*
  dengan tombol **Buka Open Assistant** dan **Batal**. Popup ini penting: jangan redirect otomatis
  tanpa klik (mencegah situs lain memicu sambungan diam-diam).
- **Buka Open Assistant** → buat kode sekali pakai (acak ≥128 bit, berlaku **60 detik**, terikat ke
  pengguna, `code_challenge`, dan `client`), lalu redirect ke
  `openassistant://signin?code={kode}&state={state}`.
- **Batal** → redirect ke `openassistant://signin?error=access_denied&state={state}`.
- Setelah redirect, tampilkan teks "Anda boleh menutup tab ini" (peramban akan bertanya "Buka Open
  Assistant?" — itu perilaku normal peramban untuk skema aplikasi).

### 3. `POST /api/auth/assistant/token` (tanpa token)

```json
{ "code": "…", "codeVerifier": "…", "machineName": "DESKTOP-ILR0BGM", "clientVersion": "1.0.0.0" }
```

→ `200`
```json
{
  "token": "eyJ…", "expiresAt": "2026-09-30T16:00:00Z",
  "refreshToken": "…",
  "user": { "username": "fajar", "displayName": "Fajar" },
  "robotName": "fajar-DESKTOP-ILR0BGM"
}
```

| Aturan | Keterangan |
|---|---|
| Kode | Sekali pakai; dipakai kedua kali → `400 invalid_grant` **dan** cabut token yang sudah terbit dari kode itu. |
| PKCE | `BASE64URL(SHA256(codeVerifier)) == code_challenge`; salah → `400 invalid_grant`. |
| `token` | Token akses pengguna seperti `/api/auth/login` (izin = peran pengguna, jadi halaman Jadwal dengan `triggers.read` ikut bekerja). Umur disarankan ≤ 1 jam. |
| `refreshToken` | Acak, disimpan server sebagai hash, terikat pengguna + mesin; umur disarankan 30 hari geser. |
| `robotName` | Robot attended yang dipakai Open Assistant untuk denyut/klaim job v1 (`/api/robots/{robotName}/heartbeat`, `/api/jobs/next?robot=`). Server yang menentukan (mis. robot attended milik pengguna itu, atau dibuat otomatis). |

### 4. `POST /api/auth/assistant/refresh` (tanpa token)

`{ "refreshToken": "…" }` → `200` dengan bentuk yang sama (token baru **dan** refreshToken baru —
rotasi; yang lama langsung tidak berlaku). `400/401` kalau dicabut/kedaluwarsa → Open Assistant
menampilkan "Sesi berakhir" dan halaman Hubungkan lagi.

### 5. `POST /api/auth/assistant/logout` (tanpa token)

`{ "refreshToken": "…" }` → `200` (idempoten). Dipanggil saat tombol **Putuskan**.

### 6. Dasbor (disarankan)

Di halaman profil pengguna: daftar "Open Assistant yang tersambung" (mesin, terakhir aktif) dengan
tombol **Cabut** — mencabut refreshToken itu.

## Yang sudah dilakukan sisi robot

- Halaman **Hubungkan** di Open Assistant (robot tidur → bangun + animasi), dua pilihan.
- `JakRunner/Core/AssistantSignIn.cs`: state + PKCE S256, buka `/assistant/connect`, skema
  `openassistant://` didaftarkan per pengguna (HKCU, tanpa admin), tautan dari peramban diteruskan ke
  Open Assistant yang sedang berjalan, tukar kode → token, simpan `%LOCALAPPDATA%\JakForge\Assistant\account.json`
  (token tersandi DPAPI akun Windows), refresh otomatis dengan rotasi, logout.
- `ForgeHubClient` memakai token akun itu (bukan nama pengguna + sandi `jakrunner.json`) untuk
  denyut, klaim job, laporan, log, dan **jadwal** — jadi setelah server ini ada, jalur lama
  `jakrunner.json` bisa dihapus tanpa kehilangan halaman Jadwal
  (menggantikan kebutuhan `GET /api/agent/triggers` untuk PC attended; usulan itu tetap berguna
  untuk mesin unattended).

## Pertanyaan untuk tim Orchestrator

1. Robot attended untuk pengguna: sudah ada modelnya ("Attended" di Robots), atau perlu dibuat
   otomatis saat masuk pertama? Nama yang disarankan?
2. Satu pengguna di beberapa PC: satu robot per pengguna, atau per pengguna+mesin?
3. Umur token/refresh yang diinginkan tim, dan apakah perlu izin khusus (mis. `assistant.connect`)
   untuk boleh menyambungkan Open Assistant?
