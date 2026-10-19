# Catatan dari tim robot: tombol unggah paket .nupkg di dasbor

Usulan dari tim OpenStudio / Robot Agent. Berkas ini hanya dokumentasi; tidak
ada perubahan kode.

Diperiksa di `d42dd9e`, **dari kode**: belum dicoba melawan server yang
berjalan.

## Ringkasnya

OpenStudio sekarang bisa **Publish ke folder lokal** dan menghasilkan berkas
`.nupkg` (tata letak sama dengan UiPath). Agar berkas itu bisa masuk ke Open
Orchestrator, dasbor perlu tombol **Unggah paket**. Di UiPath, tombol ini ada di
Orchestrator › Packages › Upload.

Backend sudah hampir siap. Jalur unggah biner dari catatan #8 sudah ada:
`POST /api/packages` multipart, capability `packages.upload.binary`. Terima
kasih. Yang kurang ada dua:

| # | Yang kurang | Di mana |
|---|---|---|
| 1 | Tombol unggah berkas di Tenant › Paket | `frontend/app/tenant/packages/page.tsx`, `components/DaftarPaket.tsx` |
| 2 | Nama dan versi dibaca dari `.nuspec` bila tidak dikirim | `service/PackageService.java` (`publishFile`) |

## Kasus pemakaian

- **Studio tidak bisa menjangkau Orchestrator.** Contohnya PC developer di
  jaringan terpisah dari server bank. Developer mem-publish ke folder lokal atau
  share. Admin lalu mengunggah berkas `.nupkg` itu lewat dasbor.
- **Rilis lewat persetujuan.** Paket diperiksa dulu sebagai berkas, baru
  diunggah ke Orchestrator produksi oleh orang lain.

## Keadaan sekarang (dari kode)

- Tenant › Paket (`DaftarPaket`) hanya menampilkan daftar, mengunduh, dan
  menghapus. Belum ada unggah.
- `PackageController.publishFile` menerima bagian `file` ditambah medan form
  (`name`, `version`, `description`, `entryPoint`, `environment`).
- `checkPublishable` mewajibkan `name`. Isi `.nuspec` di dalam berkas tidak
  dibaca.
- `version` yang tidak dikirim otomatis menjadi `1.0.0`
  (`PublishPackageRequest.DEFAULT_VERSION`). Akibatnya, berkas versi 1.2.3
  yang diunggah hanya dengan `name` akan tercatat sebagai 1.0.0 tanpa
  peringatan.
- Izin sudah ada: `POST /api/packages` membutuhkan `packages.create` /
  `packages.update` (`PermissionInterceptor`).

## Usulan

1. **Dasbor.**
   - Tombol "Unggah paket" di Tenant › Paket, tampil hanya bagi yang punya
     `packages.create`.
   - Dialog pilih berkas `.nupkg` (boleh juga `.zip`), lalu kirim multipart ke
     `POST /api/packages`, endpoint yang sudah ada.
   - Sesudah berhasil, daftar disegarkan dan nama + versi hasilnya ditampilkan.
   - Bila versi sudah ada, tampilkan pesan dari 409 `PACKAGE_VERSION_EXISTS`
     apa adanya. Pesannya sudah jelas: "Naikkan versinya…".
2. **Backend: metadata dari `.nuspec`.** Bila berkasnya zip berisi tepat satu
   `*.nuspec` di akar, dan medan form `name`/`version` kosong:
   - `<id>` dipakai sebagai `name`, `<version>` sebagai `version`, dan
     `<description>` sebagai `description`.
   - Cukup `java.util.zip` + parser XML JDK; tidak perlu pustaka baru. Matikan
     DTD/entitas eksternal saat mengurai XML (XXE).
   - Bila medan form diisi, medan form yang menang. Dengan begitu `curl` dan
     klien lain tetap bisa menimpa nilainya.
   - Versi dari `.nuspec` harus dipakai **sebelum** bawaan `1.0.0` berlaku.
     Jadi `fromForm` perlu tahu apakah `version` memang dikirim.
   - Tolak bila `.nuspec` lebih dari satu atau `<id>`/`<version>` kosong
     (400, pesan jelas).
3. **Simpan berkasnya apa adanya.** Jangan dibongkar atau dikemas ulang.
   SHA-256 yang diperiksa robot dihitung dari byte yang diunggah, seperti
   sekarang. Penyesuaian tata letak dikerjakan di sisi robot (lihat bawah).
4. **`entryPoint` boleh kosong.** Robot mencari sendiri: `main` di
   `project.json`, lalu `Main.xaml`. Tag nuspec `entry:<berkas>` memang ada,
   tetapi spasinya diganti `_`, jadi jangan dipakai sebagai jalur.

## Isi `.nupkg` dari OpenStudio

```
<id>.nuspec                                   ← metadata (id, version, title, authors, description, releaseNotes, tags)
lib/net45/project.json                        ← isi proyek
lib/net45/Main.xaml
lib/net45/<subfolder>/…
_rels/.rels
package/services/metadata/core-properties/<guid>.psmdcp
[Content_Types].xml
```

- `<id>` adalah nama proyek dengan karakter selain `A-Z a-z 0-9 . - _` diganti
  `_` (contoh: "Record Video" menjadi `Record_Video`).
- Nama entri di zip **di-escape persen per segmen** (standar OPC/NuGet).
  Contohnya `lib/net45/Proses%20Nasabah.xaml`. Kalau backend kelak perlu
  membaca berkas di dalamnya, entrinya harus di-unescape dulu.

## Sisi robot (dikerjakan tim kami)

Saat ini robot unattended mengekstrak paket lalu mencari `.xaml` /
`project.json` di **akar** paket. Berkas `.nupkg` yang diunggah apa adanya akan
gagal dengan pesan "Paket tidak berisi berkas .xaml di akarnya".

Kami akan menambahkan pengenalan tata letak `.nupkg` di Robot Agent:

- Paket dianggap `.nupkg` bila ada `.nuspec` di akar dan isi di `lib/net45/`.
- Isi `lib/net45/` diekstrak ke akar folder paket, dengan nama yang
  di-unescape.

Paket zip lama (dari Publish langsung) tetap berjalan seperti sekarang. Tidak
ada perubahan kontrak API.

**Urutan rilis:** tombol unggah sebaiknya diumumkan ke pengguna setelah versi
robot dengan dukungan `.nupkg` terpasang. Kalau tidak, paket yang diunggah
belum bisa dijalankan.

## Uji yang diusulkan

- Unggah `.nupkg` hasil Publish lokal OpenStudio tanpa medan form. Paket
  tercatat dengan `name`/`version` dari `.nuspec`, dan proses bernama sama
  dibuat atau ditautkan seperti jalur Publish biasa.
- Unggah versi yang sudah ada: 409 `PACKAGE_VERSION_EXISTS`, dan pesannya
  tampil di dialog.
- Medan form `version` diisi beda dengan `.nuspec`: medan form yang dipakai.
- Zip tanpa `.nuspec` dan tanpa `name`: 400 "Nama paket wajib diisi."
- `.nuspec` berisi DOCTYPE/entitas eksternal: ditolak, dan tidak ada akses
  berkas atau jaringan.
- Pengguna tanpa `packages.create`: tombol tidak tampil, dan unggah langsung
  lewat API ditolak 403.
