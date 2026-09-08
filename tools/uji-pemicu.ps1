# Menguji pemicu terjadwal dan penjadwalnya terhadap ForgeHub yang berjalan.
#
# Uji ini SENGAJA menunggu penjadwal benar-benar menembak, bukan sekadar
# memeriksa bahwa barisnya tersimpan. Pemicu yang tersimpan rapi tapi tidak
# pernah berjalan adalah persis kegagalan yang paling mungkin terjadi, dan
# satu-satunya cara membuktikannya tidak terjadi adalah menunggu.

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

$nama = "UjiPemicu-$(Get-Random -Maximum 99999)"

# ---------- penolakan bentuk yang salah ----------
try {
    Invoke-RestMethod "$base/api/triggers" -Method Post -Headers $h -ContentType 'application/json' `
        -Body (@{ name = $nama; processName = 'cha'; cron = 'bukan cron sama sekali' } | ConvertTo-Json) | Out-Null
    Cek "cron cacat ditolak saat dibuat" "diterima" "400"
} catch {
    Cek "cron cacat ditolak saat dibuat" $_.Exception.Response.StatusCode.value__ 400
}

try {
    Invoke-RestMethod "$base/api/triggers" -Method Post -Headers $h -ContentType 'application/json' `
        -Body (@{ name = $nama; processName = 'cha'; cron = '0 7 * * *'; timezone = 'Asia/Djakarta' } | ConvertTo-Json) | Out-Null
    Cek "zona waktu salah ketik ditolak" "diterima" "400"
} catch {
    Cek "zona waktu salah ketik ditolak" $_.Exception.Response.StatusCode.value__ 400
}

try {
    Invoke-RestMethod "$base/api/triggers" -Method Post -Headers $h -ContentType 'application/json' `
        -Body (@{ name = $nama; processName = 'proses-yang-tidak-ada' } | ConvertTo-Json) | Out-Null
    Cek "proses yang belum diterbitkan ditolak" "diterima" "400"
} catch {
    Cek "proses yang belum diterbitkan ditolak" $_.Exception.Response.StatusCode.value__ 400
}

# ---------- cron dengan zona waktu ----------
$buat = Invoke-RestMethod "$base/api/triggers" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ name = $nama; processName = 'cha'; cron = '0 7 * * *'; timezone = 'Asia/Jakarta';
              priority = 'High'; enabled = $true } | ConvertTo-Json)
Cek "pemicu cron dibuat" $buat.ok "True"

$daftar = Invoke-RestMethod "$base/api/triggers" -Headers $h
$p = $daftar | Where-Object name -eq $nama
Cek "  tersimpan dengan cron-nya" $p.cron "0 7 * * *"
Cek "  zonanya tersimpan" $p.timezone "Asia/Jakarta"
Cek "  aktif" $p.enabled "True"

# 07:00 Asia/Jakarta = 00:00 UTC. Inilah yang salah di versi .NET, yang
# menyimpan zonanya tapi menghitung dalam UTC.
$next = [DateTimeOffset]::Parse($p.nextRunAt).ToUniversalTime()
Cek "  jalan berikutnya 00:00 UTC (= 07:00 WIB)" ("{0:HH:mm}" -f $next) "00:00"

# ---------- nyala/mati ----------
$mati = Invoke-RestMethod "$base/api/triggers/$nama/toggle" -Method Post -Headers $h
Cek "toggle mematikan" $mati.enabled "False"
$p2 = (Invoke-RestMethod "$base/api/triggers" -Headers $h) | Where-Object name -eq $nama
Cek "  waktu jalannya dikosongkan saat mati" ($null -eq $p2.nextRunAt) $true

$nyala = Invoke-RestMethod "$base/api/triggers/$nama/toggle" -Method Post -Headers $h
Cek "toggle menyalakan lagi" $nyala.enabled "True"
$p3 = (Invoke-RestMethod "$base/api/triggers" -Headers $h) | Where-Object name -eq $nama
Cek "  waktu jalannya dihitung ulang, bukan yang lama" ($null -ne $p3.nextRunAt) $true

$next3 = [DateTimeOffset]::Parse($p3.nextRunAt).ToUniversalTime()
Cek "  dan waktunya di masa depan" ($next3 -gt [DateTimeOffset]::UtcNow) $true

# ---------- penjadwal benar-benar menembak ----------
# Pemicu berselang 1 menit, lalu ditunggu. Penjadwal berputar tiap 30 detik.
$namaCepat = "UjiCepat-$(Get-Random -Maximum 99999)"
Invoke-RestMethod "$base/api/triggers" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ name = $namaCepat; processName = 'cha'; intervalMinutes = 1; enabled = $true } | ConvertTo-Json) | Out-Null

# Dimajukan supaya jatuh tempo sekarang, tanpa menunggu satu menit penuh.
docker exec -e PGPASSWORD=forgehub forgehub-db psql -U forgehub -d forgehub -q -c `
    "UPDATE triggers SET next_run_at = now() - interval '1 second' WHERE name = '$namaCepat'" | Out-Null

$sebelum = (Invoke-RestMethod "$base/api/jobs?process=cha&limit=500" -Headers $h).Count
Write-Host "  menunggu penjadwal (maksimal 60 detik)..."

$terjadwal = $null
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 3
    $jobs = Invoke-RestMethod "$base/api/jobs?process=cha&limit=500" -Headers $h
    $terjadwal = $jobs | Where-Object { $_.source -eq 'Trigger' -and $_.info -like "*$namaCepat*" }
    if ($terjadwal) { break }
}

Cek "penjadwal membuat pekerjaan" ($null -ne $terjadwal) $true
if ($terjadwal) {
    Cek "  sumbernya Trigger" $terjadwal.source "Trigger"
    Cek "  keadaannya PENDING" $terjadwal.state "PENDING"
    Cek "  prosesnya benar" $terjadwal.processName "cha"

    $p4 = (Invoke-RestMethod "$base/api/triggers" -Headers $h) | Where-Object name -eq $namaCepat
    Cek "  waktu jalan terakhir tercatat" ($null -ne $p4.lastRunAt) $true

    $next4 = [DateTimeOffset]::Parse($p4.nextRunAt).ToUniversalTime()
    Cek "  waktu berikutnya dimajukan ke depan" ($next4 -gt [DateTimeOffset]::UtcNow) $true

    Invoke-RestMethod "$base/api/jobs/$($terjadwal.id)" -Method Delete -Headers $h | Out-Null
}

# ---------- pemicu yang prosesnya hilang dimatikan sendiri ----------
Invoke-RestMethod "$base/api/processes" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ name = 'ProsesSementara' } | ConvertTo-Json) | Out-Null

$namaYatim = "UjiYatim-$(Get-Random -Maximum 99999)"
Invoke-RestMethod "$base/api/triggers" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ name = $namaYatim; processName = 'ProsesSementara'; intervalMinutes = 1 } | ConvertTo-Json) | Out-Null

Invoke-RestMethod "$base/api/processes/ProsesSementara" -Method Delete -Headers $h | Out-Null

docker exec -e PGPASSWORD=forgehub forgehub-db psql -U forgehub -d forgehub -q -c `
    "UPDATE triggers SET next_run_at = now() - interval '1 second' WHERE name = '$namaYatim'" | Out-Null

Write-Host "  menunggu penjadwal mematikan pemicu yatim..."
$yatim = $null
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 3
    $yatim = (Invoke-RestMethod "$base/api/triggers" -Headers $h) | Where-Object name -eq $namaYatim
    if ($yatim.enabled -eq $false) { break }
}

Cek "pemicu yang prosesnya hilang dimatikan" $yatim.enabled "False"

$alert = Invoke-RestMethod "$base/api/logs?limit=50" -Headers $h
Cek "  dan tidak berulang tiap 30 detik" ($null -eq $yatim.nextRunAt) $true

# ---------- bersih-bersih ----------
Invoke-RestMethod "$base/api/triggers/$nama" -Method Delete -Headers $h | Out-Null
Invoke-RestMethod "$base/api/triggers/$namaCepat" -Method Delete -Headers $h | Out-Null
Invoke-RestMethod "$base/api/triggers/$namaYatim" -Method Delete -Headers $h | Out-Null

Write-Host ""
Write-Host ("{0} LULUS, {1} GAGAL" -f $lulus, $gagal)
if ($gagal -gt 0) { exit 1 }
