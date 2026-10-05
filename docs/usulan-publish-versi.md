# Catatan: dialog Publish OpenStudio — versi, catatan rilis, dan `POST /api/packages`

> **Status (5 Okt 2026):** sisi OpenStudio sudah terkompilasi dan teruji di PC Fajar. Catatan ini
> untuk tim Open Orchestrator. **Tidak ada perubahan yang wajib**; dua pertanyaan dan satu usulan
> di bawah bersifat opsional.

Untuk tim Open Orchestrator. Ditulis dari sisi Studio (OpenStudio).

## Apa yang berubah di OpenStudio

Sebelumnya tombol Publish langsung mengemas proyek dan mengirimnya, dengan versi yang dihitung
otomatis dan deskripsi tetap "Diterbitkan dari OpenStudio oleh …". Sekarang Publish membuka
dialog bergaya UiPath:

1. **Properti paket**:
   - nama paket;
   - versi saat ini;
   - versi baru (bawaannya angka terakhir +1, **bisa diubah orang**);
   - catatan rilis.
2. **Opsi terbit**: ke Orchestrator, atau ke folder lokal sebagai berkas `.nupkg`. Pilihan
   kedua tidak menyentuh Orchestrator sama sekali.

Panggilan ke Orchestrator tetap sama: `POST /api/packages` dengan
`name`, `version`, `description`, `entryPoint`, dan `contentBase64` (zip proyek, paling besar
64 MB). Bedanya hanya isi dua kolom ini:

| Kolom | Dulu | Sekarang |
|---|---|---|
| `version` | selalu dihitung otomatis | dipilih orang di dialog (bentuk `x.y.z`) |
| `description` | "Diterbitkan dari OpenStudio oleh <pengguna>." | **catatan rilis** yang diketik orang; kalau kosong, teks lama |

## Perbaikan di sisi Studio (pemberitahuan saja)

"Versi saat ini" dibaca dari `GET /api/packages`. Dulu permintaan ini bisa terkirim **tanpa
token**: kalau Studio belum masuk, jawabannya 401 dan versinya terbaca "belum pernah terbit",
sehingga Studio mengusulkan `1.0.0` lagi. Sekarang Studio masuk dulu (`/api/auth/login`)
sebelum membaca daftar paket. Tidak ada yang perlu diubah di Orchestrator.

## Pertanyaan dan usulan (opsional)

1. **Versi yang sudah ada.** Dialog menolak versi yang sama atau lebih rendah dari versi tertinggi
   yang terbaca. Tapi kalau Orchestrator tidak bisa ditanya saat dialog dibuka, orang tetap bisa
   mengirim versi yang sudah ada di server.
   - Apa jawaban `POST /api/packages` untuk pasangan `name` + `version` yang sudah ada:
     menimpa, atau menolak?
   - Kalau menolak, mohon dengan kode status yang jelas (misalnya **409 Conflict**) dan
     `error` berisi pesan. Studio sudah menampilkan isi `error` apa adanya di dialog, dan dengan
     409 bisa menambahkan saran "naikkan versinya".
2. **Kolom `releaseNotes` terpisah.** Saat ini catatan rilis menumpang di `description`, sehingga
   deskripsi proses di dasbor berganti setiap kali terbit. Usulan: `POST /api/packages` menerima
   `releaseNotes` (opsional) per versi, dan `description` tetap deskripsi proses. Studio akan
   mengirim keduanya begitu kolomnya ada; sebelum itu tetap seperti sekarang.
3. **Urutan versi.** Studio membandingkan versi sebagai angka per bagian (`1.0.10` > `1.0.9`).
   Kalau dasbor atau robot memilih "versi terbaru" dengan urutan teks, `1.0.10` bisa dianggap
   lebih lama dari `1.0.9`. Mohon pastikan perbandingannya juga per angka.
