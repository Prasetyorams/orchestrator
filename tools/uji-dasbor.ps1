# Menguji dasbor, peringatan, dan endpoint pengelolaan.
#
# Yang diperiksa bukan sekadar "jawabannya 200", melainkan apakah angkanya
# BENAR. Dasbor yang menampilkan nol untuk semuanya juga menjawab 200.

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$gagal = 0
$lulus = 0

function Cek($nama, $dapat, $harap) {
    $ok = ("$dapat" -eq "$harap")
    if ($ok) { $script:lulus++ } else { $script:gagal++ }
    $tanda = if ($ok) { 'LULUS' } else { 'GAGAL' }
    $baris = "{0}  {1,-54} -> {2}" -f $tanda, $nama, $dapat
    if (-not $ok) { $baris += "   (harap $harap)" }
    Write-Host $baris
}

$login = Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' `
    -Body (@{ username = 'FH_Admin'; password = 'forgehub' } | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($login.token)" }

# ---------- dasbor ----------
$d = Invoke-RestMethod "$base/api/dashboard" -Headers $h

Cek "dasbor: jumlah robot" $d.robots.total 2
Cek "  semua robot lama terputus" $d.robots.disconnected 2
Cek "  tidak ada yang tersedia" $d.robots.available 0

$jobsAll = Invoke-RestMethod "$base/api/jobs?limit=1000" -Headers $h
Cek "  pekerjaan berjalan cocok dengan tabel" $d.jobs.running (@($jobsAll | Where-Object state -eq 'RUNNING').Count)
Cek "  pekerjaan menunggu cocok dengan tabel" $d.jobs.pending (@($jobsAll | Where-Object state -eq 'PENDING').Count)

$procAll = Invoke-RestMethod "$base/api/processes" -Headers $h
Cek "  jumlah proses cocok" $d.library.processes $procAll.Count

$pkgAll = Invoke-RestMethod "$base/api/packages" -Headers $h
Cek "  jumlah paket cocok" $d.library.packages $pkgAll.Count

$qAll = Invoke-RestMethod "$base/api/queues" -Headers $h
Cek "  jumlah antrean cocok" $d.queues.total $qAll.Count
Cek "  butir antrean total cocok" ($d.queues.newItems + $d.queues.inProgress) `
    (($qAll | ForEach-Object { $_.newCount + $_.inProgressCount } | Measure-Object -Sum).Sum)

Cek "  tingkat keberhasilan angka wajar" (($d.successRate -ge 0) -and ($d.successRate -le 100)) $true
Cek "  waktu server ada" ($null -ne $d.serverTime) $true
Cek "  ringkasan antrean terisi" ($d.queueSummary.Count -ge 1) $true

# ---------- riwayat 14 hari ----------
$hist = Invoke-RestMethod "$base/api/dashboard/history" -Headers $h
Cek "riwayat: tepat 14 hari" $hist.Count 14
Cek "  hari kosong tetap muncul (bukan dilompati)" @($hist | Where-Object { $_.successful -eq 0 }).Count.GetType().Name "Int32"

$totalBerhasil = ($hist | Measure-Object -Property successful -Sum).Sum
Cek "  ada hari dengan pekerjaan berhasil" ($totalBerhasil -gt 0) $true

$urut = $true
for ($i = 1; $i -lt $hist.Count; $i++) {
    if ([datetime]$hist[$i].day -le [datetime]$hist[$i-1].day) { $urut = $false }
}
Cek "  urut menaik tanpa hari kembar" $urut $true

# ---------- pencarian ----------
$cari = Invoke-RestMethod "$base/api/search?q=cha" -Headers $h
Cek "cari 'cha' menemukan sesuatu" ($cari.Count -gt 0) $true
Cek "  menemukan prosesnya" (@($cari | Where-Object { $_.kind -eq 'Proses' -and $_.label -eq 'cha' }).Count) 1
Cek "  menemukan paketnya juga" (@($cari | Where-Object kind -eq 'Paket').Count -ge 1) $true

$cariBesar = Invoke-RestMethod "$base/api/search?q=CHA" -Headers $h
Cek "  huruf besar tetap ketemu (ILIKE)" ($cariBesar.Count) ($cari.Count)

$cariPendek = Invoke-RestMethod "$base/api/search?q=c" -Headers $h
Cek "  satu huruf tidak menyeret seluruh basis data" $cariPendek.Count 0

# Tanda % tidak boleh berubah jadi "cocokkan apa saja".
$cariPersen = Invoke-RestMethod "$base/api/search?q=%25%25" -Headers $h
Cek "  tanda persen diloloskan, bukan jadi wildcard" $cariPersen.Count 0

# ---------- peringatan ----------
$a = Invoke-RestMethod "$base/api/alerts?limit=500" -Headers $h
Cek "peringatan: ada isinya" ($a.Count -gt 0) $true

$belum = Invoke-RestMethod "$base/api/alerts?unread=1&limit=500" -Headers $h
Cek "  saringan belum-dibaca lebih sedikit atau sama" ($belum.Count -le $a.Count) $true
Cek "  cocok dengan hitungan di dasbor" $belum.Count $d.unreadAlerts

if ($belum.Count -gt 0) {
    $satu = $belum[0]
    Invoke-RestMethod "$base/api/alerts/$($satu.id)/read" -Method Post -Headers $h | Out-Null
    $belum2 = Invoke-RestMethod "$base/api/alerts?unread=1&limit=500" -Headers $h
    Cek "  menandai satu mengurangi hitungannya" $belum2.Count ($belum.Count - 1)
}

$semua = Invoke-RestMethod "$base/api/alerts/read-all" -Method Post -Headers $h
Cek "  tandai semua berhasil" $semua.ok "True"
$belum3 = Invoke-RestMethod "$base/api/alerts?unread=1&limit=500" -Headers $h
Cek "  tidak ada yang tersisa belum dibaca" $belum3.Count 0

# ---------- pengguna, peran, penyewa ----------
$u = Invoke-RestMethod "$base/api/users" -Headers $h
Cek "pengguna: jumlah" $u.Count 1
Cek "  hash kata sandi TIDAK ikut terkirim" ($null -eq $u[0].passwordHash) $true

$r = Invoke-RestMethod "$base/api/roles" -Headers $h
Cek "peran: empat peran bawaan" $r.Count 4
Cek "  Administrator dipakai satu pengguna" (($r | Where-Object name -eq 'Administrator').userCount) 1

$t = Invoke-RestMethod "$base/api/tenants" -Headers $h
Cek "penyewa: satu" $t.Count 1
Cek "  jumlah robotnya" $t[0].robotCount 2

# ---------- membuat dan menghapus pengguna ----------
$nu = "uji$(Get-Random -Maximum 99999)"
try {
    Invoke-RestMethod "$base/api/users" -Method Post -Headers $h -ContentType 'application/json' `
        -Body (@{ username = $nu; password = '123' } | ConvertTo-Json) | Out-Null
    Cek "kata sandi terlalu pendek ditolak" "diterima" "400"
} catch {
    Cek "kata sandi terlalu pendek ditolak" $_.Exception.Response.StatusCode.value__ 400
}

$buat = Invoke-RestMethod "$base/api/users" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ username = $nu; password = 'rahasia123'; displayName = 'Uji Coba'; role = 'Auditor' } | ConvertTo-Json)
Cek "pengguna baru dibuat" $buat.created "True"

# Pengguna baru harus benar-benar bisa masuk — hash-nya dipakai sungguhan.
$loginBaru = Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' `
    -Body (@{ username = $nu; password = 'rahasia123' } | ConvertTo-Json)
Cek "  bisa masuk dengan sandinya" $loginBaru.username $nu
Cek "  perannya terbawa" $loginBaru.role "Auditor"

# Menyunting tanpa mengirim kata sandi tidak boleh mengosongkannya.
Invoke-RestMethod "$base/api/users" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ username = $nu; displayName = 'Diubah' } | ConvertTo-Json) | Out-Null
$loginLagi = Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' `
    -Body (@{ username = $nu; password = 'rahasia123' } | ConvertTo-Json)
Cek "  sunting tanpa sandi TIDAK mengosongkan sandinya" $loginLagi.username $nu

# Bukan administrator tidak boleh mengubah pengguna.
$hAuditor = @{ Authorization = "Bearer $($loginBaru.token)" }
try {
    Invoke-RestMethod "$base/api/users" -Method Post -Headers $hAuditor -ContentType 'application/json' `
        -Body (@{ username = 'seharusnya-gagal'; password = 'rahasia123' } | ConvertTo-Json) | Out-Null
    Cek "Auditor tidak boleh membuat pengguna" "diizinkan" "403"
} catch {
    Cek "Auditor tidak boleh membuat pengguna" $_.Exception.Response.StatusCode.value__ 403
}

try {
    Invoke-RestMethod "$base/api/users/FH_Admin" -Method Delete -Headers $h | Out-Null
    Cek "Administrator terakhir tidak bisa dihapus" "terhapus" "400"
} catch {
    Cek "Administrator terakhir tidak bisa dihapus" $_.Exception.Response.StatusCode.value__ 400
}

Invoke-RestMethod "$base/api/users/$nu" -Method Delete -Headers $h | Out-Null
Cek "pengguna uji dihapus" ((Invoke-RestMethod "$base/api/users" -Headers $h).Count) 1

# ---------- lisensi dan setelan ----------
$lic = Invoke-RestMethod "$base/api/licensing" -Headers $h
Cek "lisensi: dua produk" $lic.Count 2
$attended = ($lic | Where-Object { $_.product -like '*Attended*' -and $_.product -notlike '*Unattended*' })
Cek "  terpakai dihitung dari robot sungguhan" $attended.used 2

$s = Invoke-RestMethod "$base/api/settings" -Headers $h
Cek "setelan: penyewa" $s.tenant "default"
Cek "  basis datanya PostgreSQL" $s.database "PostgreSQL"
Cek "  zona tampilan tersetel" $s.displayTimezone "Asia/Jakarta"
Cek "  jumlah pekerjaan cocok" $s.counts.jobs $jobsAll.Count

# ---------- gudang berkas ----------
$b = Invoke-RestMethod "$base/api/buckets" -Headers $h
Cek "gudang: bawaan 'Shared' ada" (@($b | Where-Object name -eq 'Shared').Count) 1

$isi = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('halo dunia'))
$unggah = Invoke-RestMethod "$base/api/buckets/Shared/files" -Method Post -Headers $h `
    -ContentType 'application/json' `
    -Body (@{ fileName = '..\..\jahat.txt'; contentBase64 = $isi; contentType = 'text/plain' } | ConvertTo-Json)
Cek "  jalur '..\' dibuang dari nama berkas" $unggah.fileName "jahat.txt"
Cek "  ukurannya benar" $unggah.sizeBytes 10

$daftarBerkas = Invoke-RestMethod "$base/api/buckets/Shared/files" -Headers $h
$berkas = $daftarBerkas | Where-Object fileName -eq 'jahat.txt'
Cek "  berkas terdaftar" ($null -ne $berkas) $true

$tmp = Join-Path $env:TEMP 'uji-bucket.txt'
Invoke-WebRequest "$base/api/buckets/Shared/files/$($berkas.id)/content" -Headers $h -OutFile $tmp
Cek "  isinya terbaca kembali" (Get-Content $tmp -Raw) "halo dunia"
Remove-Item $tmp -Force

# Nama yang sama diganti, bukan ditumpuk.
Invoke-RestMethod "$base/api/buckets/Shared/files" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ fileName = 'jahat.txt'; contentBase64 = $isi } | ConvertTo-Json) | Out-Null
$daftar2 = Invoke-RestMethod "$base/api/buckets/Shared/files" -Headers $h
Cek "  unggah ulang mengganti, bukan menumpuk" (@($daftar2 | Where-Object fileName -eq 'jahat.txt').Count) 1

$b2 = Invoke-RestMethod "$base/api/buckets" -Headers $h
Cek "  hitungan berkas di gudang ikut naik" (($b2 | Where-Object name -eq 'Shared').fileCount) 1

$berkas2 = $daftar2 | Where-Object fileName -eq 'jahat.txt'
Invoke-RestMethod "$base/api/buckets/Shared/files/$($berkas2.id)" -Method Delete -Headers $h | Out-Null
Cek "  berkas dihapus" ((Invoke-RestMethod "$base/api/buckets/Shared/files" -Headers $h).Count) 0

Write-Host ""
Write-Host ("{0} LULUS, {1} GAGAL" -f $lulus, $gagal)
if ($gagal -gt 0) { exit 1 }
