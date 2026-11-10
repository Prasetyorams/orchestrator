# Catatan: field log tambahan dari robot (`fields`)

**Dari:** tim Open Studio (Fahib16/Studio, PR #106, di-merge 10 Nov 2026)
**Untuk:** tim Open Orchestrator
**Sifat:** catatan dan usulan saja. Tidak ada kode di cabang ini; keputusan di tangan kalian.

## Ringkas

Robot Open Studio sekarang bisa mengirim kunci **opsional** `fields` di setiap baris
`POST /api/agent/logs`. Ini padanan *Add Log Fields / Remove Log Fields* UiPath, yang dipakai
REFramework untuk menandai log per proses dan per transaksi (di UiPath tampil di Orchestrator Logs
dan Insights).

- **Tidak ada yang rusak:** kuncinya menambah. Menurut kode 8912ce5, `AgentService.writeLogs`
  membaca tiap baris sebagai `Map` dan hanya mengambil kunci yang dikenal, jadi `fields` diabaikan.
  (Ini dari membaca kode; belum diuji ujung-ke-ujung melawan Orchestrator.)
- **Yang belum ada:** field itu tidak tersimpan di tabel `logs` dan tidak tampil di dasbor.
  Robot tidak membutuhkan perubahan apa pun lagi; sisanya ada di sisi Orchestrator.

## Bentuk di kabel

```json
{ "lines": [ { "jobId": "…", "seq": 41, "level": "INFO",
               "source": "Executor/Log Message (Success)", "sessionId": 3,
               "message": "Transaction Successful. #3 (INV-0003)",
               "loggedAt": "2026-11-10T09:12:03.1200000+07:00",
               "fields": { "logF_BusinessProcessName": "Framework",
                           "logF_TransactionStatus": "Success",
                           "logF_TransactionNumber": "3",
                           "logF_TransactionID": "INV-0003",
                           "logF_TransactionField1": "",
                           "logF_TransactionField2": "" } } ] }
```

Aturan dari sisi robot:

- `fields` **tidak dikirim** kalau tidak ada field aktif. Baris lain sama persis dengan sebelumnya.
- Objek datar `nama → teks`. Nilai **selalu string** (robot mengubahnya dengan budaya invarian),
  boleh kosong `""`.
- Field berlaku sejak *Add Log Fields* sampai *Remove Log Fields* dalam job yang sama, jadi
  banyak baris berurutan membawa field yang sama.
- Nama tidak boleh kosong dan tidak boleh bentrok dengan kolom log bawaan (robot menolaknya,
  tanpa beda huruf besar/kecil): `message`, `msg`, `level`, `lvl`, `source`, `src`, `loggedAt`,
  `t`, `time`, `timeStamp`, `jobId`, `robotId`, `robotName`, `machineName`, `processName`,
  `sessionId`, `seq`, `fields`.
- Robot **tidak membatasi** jumlah field atau panjang nilai; batasnya sebaiknya di server (usulan 2).

Field yang dipakai templat REFramework Open Studio (sama dengan UiPath):

| Field | Isi | Kapan |
|---|---|---|
| `logF_BusinessProcessName` | Config `logF_BusinessProcessName` (bawaan `Framework`) | sepanjang job, sejak Initialization |
| `logF_TransactionStatus` | `Success`, `BusinessException`, `ApplicationException` | baris status transaksi |
| `logF_TransactionNumber` | nomor transaksi | baris status transaksi |
| `logF_TransactionID` | Reference item antrean (waktu sekarang kalau kosong) / nomor baris DataRow | baris status transaksi |
| `logF_TransactionField1/2` | bebas (bawaan kosong) | baris status transaksi |

Pengguna juga bisa memakai *Add Log Fields* dengan nama sendiri.

## Usulan untuk Orchestrator

1. **Simpan.** Kolom baru `fields jsonb NULL` di `logs` (migrasi baru), diisi dari `line.fields`
   di `writeLogs` → `LogRepository.insertAgentLine`. Kalau `fields` bukan objek, abaikan.
   Ambil hanya nilai string.
2. **Batasi dan sensor.** Misalnya maks 50 kunci per baris, nama ≤ 100 karakter, nilai ≤ 1 KB
   (seperti `truncateBytes` untuk pesan), dan lewatkan nilainya lewat `SecretRedaction.redact`,
   sama dengan `message`.
3. **Tampilkan.** Di halaman Logs dan rincian job: field sebagai kolom atau label pada baris.
   Filter per field, mis. `logF_TransactionStatus = BusinessException`, dan pencarian per
   `logF_TransactionID`. Kalau filternya sering dipakai, indeks GIN pada `fields` bisa membantu.
4. **Ekspor.** `GET /api/logs/export` ikut membawa field.

Usulan 1 saja sudah cukup supaya data tidak hilang; 2–4 bisa menyusul.

## Pengujian di sisi robot

- `OpenStudio.Robot.Tests`: `SendLogsCarriesLogFieldsAsAnOptionalObject` (JSON berisi `fields`
  hanya bila ada), `LogFieldsFromTheExecutorLogAreShippedWithTheirLine` (field dari log executor
  ikut barisnya ke `/api/agent/logs`).
- `OpenStudio.Tests`: templat REFramework DataRow dijalankan di executor (berhasil, Business
  Exception, System Exception) dan baris status transaksinya membawa field di atas; susunan
  Add/Remove Log Fields diperiksa di ketiga templat (Queue, DataRow, Linear).
- Belum diuji: kiriman sungguhan ke Orchestrator 8912ce5. Kalau kalian menambahkan kolomnya,
  kami siap menguji job REFramework di VM dan melaporkan hasilnya di PR ini.
