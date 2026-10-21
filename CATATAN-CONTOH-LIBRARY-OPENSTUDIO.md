# Catatan dari tim OpenStudio: contoh paket library + kunci `libraries`

21 Okt 2026. Tanggapan untuk bagian **"Yang belum bisa kami cocokkan"** di `ROBOT-API.md` (Feed library, V20).
Terima kasih, feed library-nya cepat dan lengkap.

## 1. Paket library asli hasil Studio

Ada dua berkas di folder `contoh-openstudio/`. Keduanya dibangun dengan kode Studio sungguhan, bukan disusun
tangan: library lewat `LibraryBuilder`, dan proses lewat `ProjectPackage` (Terbitkan). Kodenya ada di
[Fahib16/Studio#46](https://github.com/Fahib16/Studio/pull/46), commit `92c27a1`.

| Berkas | Jenis | Ukuran | SHA-256 |
|---|---|---|---|
| `ContohPajak.1.2.0.nupkg` | library | 5272 B | `712aad4ddb21a57ce223aeab7465749cf8e43e9fba96d8f2a3525cc89ef66a75` |
| `LibraryConsumer.1.0.0.nupkg` | proses yang memakai library itu | 2824 B | `72e046f110299baf0594c9cb95a2b0f36cf964a1dbc9a5ed772d632fa3cd57da` |

**`ContohPajak.1.2.0.nupkg` (unggah lewat `POST /api/libraries`)**
- **Isi paket:** `ContohPajak.nuspec` di akar (tag `OpenStudio Library`, tanpa dependensi), `lib/net45/ContohPajak.dll`, dan berkas OPC (`_rels/.rels`, `[Content_Types].xml`, `package/services/metadata/...psmdcp`).
- **Isi DLL:** dua activity, satu per workflow (`HitungPajak.xaml` dan `Bantu/Persen.xaml`). Kelasnya turunan `Custom.Orchestrator.Activities.LibraryActivity`, dan berkas proyeknya tersemat sebagai sumber daya `OpenStudioLibrary.zip`.

**`LibraryConsumer.1.0.0.nupkg` (unggah lewat Unggah Paket)**
- **`.nuspec`:** tag `OpenStudio Automation entry:Main.xaml`, dan `<dependency id="ContohPajak" version="[1.2.0]" />` di grup `.NETFramework4.5`.
- **`lib/net45/project.json`:**
  ```json
  {
    "name": "LibraryConsumer",
    "main": "Main.xaml",
    "outputType": "Process",
    "dependencies": { "ContohPajak": "[1.2.0]" },
    "libraries": [ "ContohPajak" ]
  }
  ```
- **Keluaran yang diharapkan saat dijalankan** (sudah teruji di executor kami, tanpa Orchestrator): `Laporan` = `"pajak 22"`, `CatatanAkhir` = `"awal+pajak"`.

**Sudah diperiksa di sisi kami:** uji `LibraryPackageMatchesTheFeedRulesAndIsMarkedInProjectJson` mencocokkan paket library
dengan aturan unggah di `ROBOT-API.md`:
- `.nuspec` di akar;
- tag `OpenStudio` + `Library`;
- **tepat satu** DLL di seluruh paket, langsung di `lib/net45/`;
- DLL bertanda `MZ`.

**Belum diperiksa:** paket ini belum pernah diunggah ke server V20 sungguhan, karena lab kami masih di `4077572`.

## 2. Jawaban: menandai dependensi library di `project.json`

Bisa. Sudah diterapkan di Studio (commit `92c27a1` di atas). Bentuknya kunci baru **`libraries`**: daftar Id, bukan objek.

```json
"dependencies": { "ContohPajak": "[1.2.0]", "OpenStudio.Database.Activities": "2.0.1" },
"libraries": [ "ContohPajak" ]
```

**Isi kunci:**
- **Id saja.** Setiap Id di `libraries` juga ada di `dependencies`, dan versinya **hanya** di `dependencies`, supaya
  tidak ada dua sumber versi. Aturan versi persis tetap seperti yang sudah Anda pakai.
- **Bukan pengganti `dependencies`.** Robot dan Studio lama yang tidak mengenal `libraries` berjalan seperti dulu.

**Cara Studio mengisinya:**
- Sumbernya `.nuspec` paket yang dipasang lewat Kelola Paket: paket ber-tag `OpenStudio Library` masuk daftar,
  paket activity tidak.
- Kunci tidak ditulis sama sekali kalau proyek tidak memakai library.
- Diperbarui saat paket dipasang atau dilepas, dan sesaat sebelum Terbitkan, jadi `project.json` di dalam paket
  proses selalu ikut membawanya.

**Usul untuk server** (silakan disesuaikan): Id di `libraries` yang **tidak ada di feed** bisa langsung ditolak saat
Unggah Paket (atau diperingatkan), dan saat klaim dianggap `LibraryNotFound`, tanpa menunggu robot gagal memuat DLL.
Paket dari Studio sebelum perubahan ini tidak punya `libraries`, jadi untuk paket seperti itu perilaku sekarang
(Id yang tidak dikenal = paket activity) tetap perlu dipertahankan.

## 3. Status sisi robot

Robot Agent **belum** membaca `libraries[]` dari klaim job. Rencananya mengikuti petunjuk di `ROBOT-API.md`:
1. unduh `url` dengan token agent;
2. cocokkan SHA-256 (sidik yang tidak cocok dilaporkan seperti `PackageIntegrityFailed`);
3. taruh DLL dari `lib/net45/` di folder extensions sebelum job berjalan.

Kami kabari lagi kalau sudah diuji melawan server.
