# Catatan dari tim OpenStudio: feed Library

21 Okt 2026. Tahap L2 dari fitur Library di OpenStudio (St-4 nomor 6).
## Latar

OpenStudio kini bisa membuat **proyek Library** seperti UiPath. Saat diterbitkan,
setiap workflow-nya dikompilasi menjadi activity, lalu dipasang di proyek lain.
Hasil terbitnya berupa paket `.nupkg` dengan isi berikut:

- `<Id>.nuspec`, dengan tag `OpenStudio Library` dan
  `<dependencies><group targetFramework=".NETFramework4.5">` bila library
  memakai paket lain;
- tepat satu `lib/net45/<Id>.dll`. Workflow-nya tersemat di DLL itu sebagai
  sumber daya `OpenStudioLibrary.zip`.

`PackageInspector` sekarang menolak paket berisi `.dll` dengan kode `NOT_PROCESS`
("paket activity … Pasang lewat OpenStudio › Kelola Paket"). Untuk paket proses,
penolakan itu sudah benar. Saat ini library hanya bisa dibagikan lewat feed lokal
atau folder. Robot unattended belum bisa memasangnya sendiri, sehingga proses
yang memakai library gagal di robot dengan galat "Could not load file or assembly".

## Yang kami minta

Padanan feed **Libraries** di UiPath Orchestrator. Usulan bentuknya ada di bawah;
nama dan jalurnya silakan disesuaikan.

1. **Unggah** `POST /api/libraries` (multipart, satu `.nupkg`).
   - Validasi:
     - id dan versi diambil dari nuspec;
     - harus ada tag `OpenStudio Library`;
     - harus ada tepat satu `lib/net45/*.dll`;
     - batas ukuran sama dengan paket proses;
     - SHA-256 disimpan.
   - Versi yang sudah ada dijawab `409`.
   - Izinnya sama dengan menerbitkan paket proses.
2. **Daftar** `GET /api/libraries?search=`
   - Kolom: id, versi (terbaru dulu), deskripsi, diterbitkan oleh/pada, sha256.
3. **Unduh** `GET /api/libraries/{id}/{version}/content`
   - Bisa diakses akun Studio dan robot (machine key).
4. **Hapus** `DELETE /api/libraries/{id}/{version}`.
5. **Robot (kontrak v2)**
   - Di jawaban `GET /api/agent/jobs/next`, sertakan library yang dibutuhkan paket
     proses: `libraries: [{ id, version, sha256 }]`. Daftarnya diambil dari
     `dependencies` di `project.json` yang cocok dengan feed library.
   - Sediakan `GET /api/agent/libraries/{id}/{version}/content` dengan autentikasi
     mesin.
   - Robot mengunduh library, mencocokkan SHA-256, lalu menaruh DLL-nya di folder
     extensions sebelum job dijalankan.
   - Library yang tidak ada di feed dilaporkan sebagai galat klaim. Robot tidak
     mencari di tempat lain.
6. **Terbit proses**
   - `PackageInspector` tidak memberi peringatan untuk dependensi yang ada di feed
     library.
   - Untuk dependensi yang tidak ada di feed, `PackageInspector` tetap memberi
     peringatan seperti sekarang.

## Keamanan

- Library adalah kode yang dijalankan robot. Perlakukan seperti paket proses:
  - masuk audit log;
  - hanya pengguna dengan izin terbit yang boleh mengunggah;
  - robot hanya boleh membaca.
- Robot selalu memverifikasi SHA-256 dari Orchestrator sebelum memakai DLL.

## Pertanyaan

1. Apakah feed library dipisah per folder Orchestrator, atau satu feed untuk seluruh
   tenant? Di UiPath, keduanya bisa.
2. Pilih `libraries` di `jobs/next` (diisi server), atau robot membaca
   `project.json` sendiri lalu meminta satu per satu?
3. Berapa batas ukuran paket library?

## Sisi OpenStudio (L3, setelah endpoint ada)

- Dialog Terbitkan untuk proyek Library mendapat pilihan tujuan Orchestrator. Saat
  ini pilihan itu tampil tetapi nonaktif.
- Kelola Paket membaca feed library dari Orchestrator sebagai sumber paket.
- Robot Agent memasang library sebelum menjalankan job.
