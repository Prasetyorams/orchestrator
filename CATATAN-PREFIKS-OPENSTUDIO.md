# Catatan: peringatan palsu "pustaka bukan bawaan OpenStudio" saat unggah paket (V17)

Dari tim robot (OpenStudio / Robot Agent). Diperiksa di `c35d93e`, dari kode
(belum dicoba di server berjalan). Hanya dokumentasi, tidak ada perubahan kode.

## 1. `KNOWN_ASSEMBLY_PREFIXES` belum mengenal nama `OpenStudio`

`PackageInspector.KNOWN_ASSEMBLY_PREFIXES` masih memakai nama lama (`OpenRPA`,
`Custom.`, `JakForge.`). Sejak ganti nama (16 Okt), Studio menulis workflow dengan
pustaka bernama `OpenStudio*`, misalnya:

| `xmlns ... assembly=` | Asal |
|---|---|
| `OpenStudio.Interfaces` | dasar activity (hampir semua workflow) |
| `OpenStudio` | activity bawaan Studio |
| `OpenStudio.NM`, `OpenStudio.IE`, `OpenStudio.Office`, `OpenStudio.Forms`, `OpenStudio.Utilities`, `OpenStudio.Script` | paket activity bawaan |
| `OpenStudio.Mail.Activities`, `OpenStudio.Database.Activities`, ... | paket activity (dulu `Custom.*`) |

Akibatnya **setiap** paket dari Studio versi sekarang memunculkan peringatan
"Workflow merujuk pustaka yang bukan bawaan OpenStudio: OpenStudio.Interfaces, ...".
Pengguna akan terbiasa mengabaikannya, padahal peringatan ini berguna untuk pustaka
yang benar-benar asing.

**Usulan:** tambahkan `"OpenStudio"` (tanpa titik, agar `OpenStudio` dan
`OpenStudio.*` sama-sama cocok, sama seperti `"OpenRPA"`). Prefiks lama tetap
dipertahankan untuk paket dari Studio lama. Workflow lama tetap terbaca:
Studio sekarang memetakan nama lama ke nama baru saat membuka.

Kasus uji yang disarankan: unggah `.nupkg` dari Terbitkan → Folder lokal di
OpenStudio terbaru. Hasil yang diharapkan: tanpa peringatan pustaka.

## 2. Komentar kelas menyebut nama lama

Javadoc `PackageInspector` menyebut `JakForge.Robot.Core`. Namanya sekarang
`OpenStudio.Robot.Core`. `PackageCache.ExtractSafely` dan `EntryPointResolver`
masih ada dengan nama yang sama.

## 3. Yang sudah sesuai dengan sisi robot

Ini sekadar konfirmasi, tidak perlu tindakan:

- Isi `lib/<target>/` dipindah ke akar, sesuai `ContentFolder = "lib/net45/"` di Studio.
- Robot mengekstrak paket apa adanya dan mencari workflow di akar.
- Format `dependencies` (nama → versi) sama dengan `project.json` buatan Studio.
- Batas 260 karakter jalur relevan, karena robot berjalan di .NET Framework 4.6.2.

Catatan sebelumnya #10 (tombol unggah `.nupkg`) sudah terjawab oleh V17.
Terima kasih, PR #10 kami tutup.
