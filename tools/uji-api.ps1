# Menguji kontrak yang benar-benar dipanggil Studio, JakRunner, dan activity
# Orchestrator — terhadap ForgeHub Java + PostgreSQL yang sedang berjalan.
#
# Setiap pemeriksaan menyebut apa yang DIHARAPKAN, bukan sekadar "tidak error".
# Uji yang hanya memastikan panggilan tidak melempar akan tetap hijau ketika
# jawabannya kosong.

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$gagal = 0
$lulus = 0

function Cek($nama, $dapat, $harap) {
    $ok = ("$dapat" -eq "$harap")
    if ($ok) { $script:lulus++ } else { $script:gagal++ }
    $tanda = if ($ok) { 'LULUS' } else { 'GAGAL' }
    $baris = "{0}  {1,-52} -> {2}" -f $tanda, $nama, $dapat
    if (-not $ok) { $baris += "   (harap $harap)" }
    Write-Host $baris
}

function CekBenar($nama, $syarat, $nilai) {
    $script:lulus += 0
    Cek $nama $syarat $true
    if (-not $syarat) { Write-Host ("        nilai: {0}" -f $nilai) }
}

# ---------- kesehatan (tanpa token) ----------
$health = Invoke-RestMethod "$base/api/health"
Cek "GET /api/health tanpa token" $health.status "OK"
Cek "  produknya" $health.product "ForgeHub"

# ---------- endpoint tertutup harus 401 ----------
try {
    Invoke-RestMethod "$base/api/processes" | Out-Null
    Cek "GET /api/processes tanpa token ditolak" "tembus" "401"
} catch {
    Cek "GET /api/processes tanpa token ditolak" $_.Exception.Response.StatusCode.value__ 401
}

# ---------- masuk dengan kata sandi dari basis data lama ----------
$login = Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' `
    -Body (@{ username = 'FH_Admin'; password = 'forgehub' } | ConvertTo-Json)

CekBenar "POST /api/auth/login mengembalikan token" ($login.token.Length -gt 20) $login.token
Cek "  penggunanya" $login.username "FH_Admin"
Cek "  perannya" $login.role "Administrator"
Cek "  penyewanya" $login.tenantName "default"

$h = @{ Authorization = "Bearer $($login.token)" }

# ---------- kata sandi salah harus ditolak ----------
try {
    Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username = 'FH_Admin'; password = 'salah' } | ConvertTo-Json) | Out-Null
    Cek "kata sandi salah ditolak" "diterima" "401"
} catch {
    Cek "kata sandi salah ditolak" $_.Exception.Response.StatusCode.value__ 401
}

# ---------- data pindahan terbaca ----------
$proc = Invoke-RestMethod "$base/api/processes" -Headers $h
Cek "GET /api/processes: jumlah" $proc.Count 3
Cek "  proses 'cha' versi paketnya" ($proc | Where-Object name -eq 'cha').packageVersion "1.0.3"
CekBenar "  'cha' punya riwayat pekerjaan" ((($proc | Where-Object name -eq 'cha').jobCount) -gt 0) ($proc | Where-Object name -eq 'cha').jobCount

$pkg = Invoke-RestMethod "$base/api/packages" -Headers $h
Cek "GET /api/packages: jumlah" $pkg.Count 6
Cek "  ukuran cha 1.0.3" (($pkg | Where-Object { $_.name -eq 'cha' -and $_.version -eq '1.0.3' }).sizeBytes) 16928

$rob = Invoke-RestMethod "$base/api/robots" -Headers $h
Cek "GET /api/robots: jumlah" $rob.Count 2
Cek "  robot lama sudah DISCONNECTED" (($rob | Where-Object name -eq 'UjiRobot').status) "DISCONNECTED"

$jobs = Invoke-RestMethod "$base/api/jobs?limit=500" -Headers $h
Cek "GET /api/jobs: jumlah" $jobs.Count 34
Cek "  yang berhasil" (@($jobs | Where-Object state -eq 'SUCCESSFUL').Count) 28

$logs = Invoke-RestMethod "$base/api/logs?limit=5000" -Headers $h
Cek "GET /api/logs: dibatasi di 2000, bukan seluruhnya" $logs.Count 2000

$q = Invoke-RestMethod "$base/api/queues" -Headers $h
Cek "GET /api/queues: jumlah" $q.Count 1
Cek "  TransactionQueue total butir" ($q[0].totalCount) 6

# ---------- unduh isi paket ----------
$tmp = Join-Path $env:TEMP 'cha-uji.zip'
Invoke-WebRequest "$base/api/packages/cha/1.0.3/content" -Headers $h -OutFile $tmp
Cek "GET /packages/cha/1.0.3/content: ukuran" (Get-Item $tmp).Length 16928
$sig = [System.IO.File]::ReadAllBytes($tmp)[0..3] -join ','
Cek "  tanda tangan ZIP (PK..)" $sig "80,75,3,4"
Remove-Item $tmp -Force

# ---------- denyut robot: robot baru mendaftar sendiri ----------
$namaUji = "RobotUji-$(Get-Random -Maximum 99999)"
$hb = Invoke-RestMethod "$base/api/robots/$namaUji/heartbeat" -Method Post -Headers $h `
    -ContentType 'application/json' `
    -Body (@{ status = 'AVAILABLE'; cpuPercent = 12.5; memoryMb = 640; machineName = 'MESIN-UJI-BARU' } | ConvertTo-Json)
Cek "POST heartbeat robot baru" $hb.ok "True"

$rob2 = Invoke-RestMethod "$base/api/robots" -Headers $h
$baru = $rob2 | Where-Object name -eq $namaUji
CekBenar "  robot mendaftarkan dirinya sendiri" ($null -ne $baru) $namaUji
Cek "  statusnya AVAILABLE (baru berdenyut)" $baru.status "AVAILABLE"
Cek "  cpu tercatat" $baru.cpuPercent 12.5

$mesin = Invoke-RestMethod "$base/api/machines" -Headers $h
CekBenar "  mesinnya ikut terdaftar" (($mesin | Where-Object name -eq 'MESIN-UJI-BARU') -ne $null) ($mesin.name -join ',')

# ---------- siklus penuh sebuah pekerjaan ----------
$job = Invoke-RestMethod "$base/api/jobs" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ processName = 'cha'; robotName = $namaUji; source = 'Uji'; priority = 'High';
              inputJson = '{"in_Nama":"Budi"}' } | ConvertTo-Json)
CekBenar "POST /api/jobs membuat pekerjaan" ($job.id.Length -eq 36) $job.id

$ambil = Invoke-RestMethod "$base/api/jobs/next?robot=$namaUji" -Headers $h
Cek "GET /api/jobs/next mengambil pekerjaan itu" $ambil.job.id $job.id
Cek "  keadaannya jadi RUNNING" $ambil.job.state "RUNNING"
Cek "  argumen masukan terbawa" $ambil.job.inputJson '{"in_Nama":"Budi"}'

$lagi = Invoke-RestMethod "$base/api/jobs/next?robot=$namaUji" -Headers $h
Cek "  pengambilan kedua tidak mengembalikan yang sama" ($null -eq $lagi.job) $true

Invoke-RestMethod "$base/api/jobs/$($job.id)/state" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ state = 'RUNNING'; progress = 40; info = 'Sedang memproses' } | ConvertTo-Json) | Out-Null

$cek = Invoke-RestMethod "$base/api/jobs/$($job.id)" -Headers $h
Cek "POST state: kemajuan tercatat" $cek.progress 40

Invoke-RestMethod "$base/api/jobs/$($job.id)/state" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ state = 'SUCCESSFUL'; outputJson = '{"hasil":"ok"}' } | ConvertTo-Json) | Out-Null

$cek2 = Invoke-RestMethod "$base/api/jobs/$($job.id)" -Headers $h
Cek "  selesai jadi SUCCESSFUL" $cek2.state "SUCCESSFUL"
Cek "  kemajuan dipaksa 100" $cek2.progress 100
Cek "  keluaran tersimpan" $cek2.outputJson '{"hasil":"ok"}'
Cek "  info lama tidak terhapus" $cek2.info "Sedang memproses"
CekBenar "  waktu selesai terisi" ($null -ne $cek2.endedAt) $cek2.endedAt

# ---------- keadaan yang tidak dikenal ditolak ----------
try {
    Invoke-RestMethod "$base/api/jobs/$($job.id)/state" -Method Post -Headers $h -ContentType 'application/json' `
        -Body (@{ state = 'ENTAHLAH' } | ConvertTo-Json) | Out-Null
    Cek "keadaan tak dikenal ditolak" "diterima" "400"
} catch {
    Cek "keadaan tak dikenal ditolak" $_.Exception.Response.StatusCode.value__ 400
}

# ---------- log berkelompok ----------
$sebelum = (Invoke-RestMethod "$base/api/logs?jobId=$($job.id)" -Headers $h).Count
$tulis = Invoke-RestMethod "$base/api/logs" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ lines = @(
        @{ level = 'INFO';  message = 'baris uji satu'; robotName = $namaUji; jobId = $job.id },
        @{ level = 'ANEH';  message = 'tingkat tak dikenal jadi INFO'; robotName = $namaUji },
        @{ level = 'INFO';  message = '' }
      )} | ConvertTo-Json)
Cek "POST /api/logs: yang ditulis" $tulis.written 2
Cek "  baris kosong dilewat" ((Invoke-RestMethod "$base/api/logs?jobId=$($job.id)" -Headers $h).Count) ($sebelum + 1)

$logJob = Invoke-RestMethod "$base/api/logs?jobId=$($job.id)" -Headers $h
CekBenar "  log tersaring per pekerjaan" ($logJob.Count -ge 2) $logJob.Count

# ---------- aset dan kredensial: bolak-balik penyandian ----------
Invoke-RestMethod "$base/api/assets" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ name = 'UjiRahasia'; type = 'Secret'; value = 'sandi-super-rahasia' } | ConvertTo-Json) | Out-Null

$daftarAset = Invoke-RestMethod "$base/api/assets" -Headers $h
$rahasia = $daftarAset | Where-Object name -eq 'UjiRahasia'
Cek "aset Secret: nilainya TIDAK muncul di daftar" ($null -eq $rahasia.valueText) $true
Cek "  tapi ditandai punya isi" $rahasia.hasValue "True"

$nilai = Invoke-RestMethod "$base/api/assets/UjiRahasia/value" -Headers $h
Cek "  dibuka di endpoint /value" $nilai.value "sandi-super-rahasia"

try {
    Invoke-RestMethod "$base/api/assets" -Method Post -Headers $h -ContentType 'application/json' `
        -Body (@{ name = 'UjiAngka'; type = 'Integer'; value = 'bukan angka' } | ConvertTo-Json) | Out-Null
    Cek "aset Integer berisi huruf ditolak" "diterima" "400"
} catch {
    Cek "aset Integer berisi huruf ditolak" $_.Exception.Response.StatusCode.value__ 400
}

Invoke-RestMethod "$base/api/credentials" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ name = 'UjiKredensial'; username = 'budi'; password = 'rahasia123'; description = 'awal' } | ConvertTo-Json) | Out-Null
$kred = Invoke-RestMethod "$base/api/credentials/UjiKredensial/value" -Headers $h
Cek "kredensial: nama pengguna" $kred.username "budi"
Cek "  kata sandi terbaca kembali" $kred.password "rahasia123"

# Menyunting keterangan TANPA kata sandi tidak boleh menghapus kata sandinya.
Invoke-RestMethod "$base/api/credentials" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ name = 'UjiKredensial'; username = 'budi'; description = 'diubah' } | ConvertTo-Json) | Out-Null
$kred2 = Invoke-RestMethod "$base/api/credentials/UjiKredensial/value" -Headers $h
Cek "  sunting tanpa sandi TIDAK menghapus sandinya" $kred2.password "rahasia123"

# ---------- antrean: siklus penuh dengan percobaan ulang ----------
$butir = Invoke-RestMethod "$base/api/queues/TransactionQueue/items" -Method Post -Headers $h `
    -ContentType 'application/json' -Body (@{ reference = "UJI-$(Get-Random -Maximum 99999)"; content = '{"a":1}' } | ConvertTo-Json)
CekBenar "POST butir antrean" ($butir.id.Length -eq 36) $butir.id

$ambilB = Invoke-RestMethod "$base/api/queues/TransactionQueue/next" -Method Post -Headers $h `
    -ContentType 'application/json' -Body (@{ robotName = $namaUji } | ConvertTo-Json)
Cek "  diambil robot" $ambilB.item.id $butir.id
Cek "  isinya terbawa" $ambilB.item.content '{"a":1}'

$gagal1 = Invoke-RestMethod "$base/api/queues/items/$($butir.id)/result" -Method Post -Headers $h `
    -ContentType 'application/json' -Body (@{ status = 'FAILED'; exception = 'coba lagi' } | ConvertTo-Json)
Cek "  FAILED pertama dicoba lagi" $gagal1.retried "True"
Cek "  percobaan ke-" $gagal1.attempt 1

$ambilB2 = Invoke-RestMethod "$base/api/queues/TransactionQueue/next" -Method Post -Headers $h `
    -ContentType 'application/json' -Body (@{ robotName = $namaUji } | ConvertTo-Json)
Cek "  kembali ke antrean dan bisa diambil lagi" $ambilB2.item.id $butir.id
Cek "  hitungan percobaannya naik" $ambilB2.item.retries 1

$sukses = Invoke-RestMethod "$base/api/queues/items/$($butir.id)/result" -Method Post -Headers $h `
    -ContentType 'application/json' -Body (@{ status = 'SUCCESSFUL'; output = '{"ok":true}' } | ConvertTo-Json)
Cek "  SUCCESSFUL tidak dicoba lagi" $sukses.retried "False"

# ---------- bersih-bersih ----------
Invoke-RestMethod "$base/api/jobs/$($job.id)" -Method Delete -Headers $h | Out-Null
Invoke-RestMethod "$base/api/queues/items/$($butir.id)" -Method Delete -Headers $h | Out-Null
Invoke-RestMethod "$base/api/assets/UjiRahasia" -Method Delete -Headers $h | Out-Null
Invoke-RestMethod "$base/api/credentials/UjiKredensial" -Method Delete -Headers $h | Out-Null
Invoke-RestMethod "$base/api/robots/$namaUji" -Method Delete -Headers $h | Out-Null
Invoke-RestMethod "$base/api/machines/MESIN-UJI-BARU" -Method Delete -Headers $h | Out-Null

Write-Host ""
Write-Host ("{0} LULUS, {1} GAGAL" -f $lulus, $gagal)
if ($gagal -gt 0) { exit 1 }
