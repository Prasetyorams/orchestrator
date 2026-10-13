<#
CA milik sendiri untuk gerbang HTTPS OpenOrchestrator ('oo https'), dengan
New-SelfSignedCertificate. Dijalankan di LAPTOP (Windows PowerShell 5.1), bukan
di server orchestrator.

  .\sertifikat.ps1 ca-baru -Rentang 10.114.0.0/16,10.119.0.0/16 [-Dns intra.bank] [-Label "Bank X"] [-Tahun 10]
  .\sertifikat.ps1 server -Nama oo-bjkt -Ip 10.114.40.108[,...] [-Dns oo1.intra.bank,...] [-Hari 730]
  .\sertifikat.ps1 daftar

(Kalau ExecutionPolicy menolak: powershell -ExecutionPolicy Bypass -File .\sertifikat.ps1 ...)

Sekali: 'ca-baru' membuat root CA. root.cer-nya diimpor ke Trusted Root di
tiap PC klien (robot, Studio, peramban) - SEKALI. Sesudah itu setiap server,
yang sekarang maupun yang baru, cukup dibuatkan sertifikat dengan 'server';
klien tidak perlu diubah selama IP/nama server ada di dalam batas root.

Batas root (name constraints): root hanya sah untuk IP di -Rentang dan nama di
bawah -Dns. Kalau kuncinya bocor, ia tidak bisa dipakai memalsukan situs lain.
Tanpa -Dns, root TIDAK sah untuk nama DNS apa pun - menambah nama DNS nanti
berarti root baru (dan impor ulang sekali di klien), jadi tentukan akhiran
domainnya sekarang bila bisa (mis. domain AD kantor).

Kunci privat root tinggal di penyimpanan sertifikat pengguna ini
(Cert:\CurrentUser\My, "OpenOrchestrator Root CA"); cadangannya diekspor ke
root-cadangan.pfx bersandi - pindahkan ke media offline. Berkas lain di
-Folder (bawaan %USERPROFILE%\oo-ca). Sandi diminta tanpa gema.
#>
param(
  [Parameter(Mandatory = $true, Position = 0)][ValidateSet('ca-baru', 'server', 'daftar')][string]$Perintah,
  [string[]]$Rentang,
  [string[]]$Dns,
  [string]$Label,
  [int]$Tahun = 10,
  [string]$Nama,
  [string[]]$Ip,
  [int]$Hari = 730,
  [string]$Folder = (Join-Path $env:USERPROFILE 'oo-ca')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

function Gagal([string]$pesan) { Write-Host ""; Write-Host "GAGAL: $pesan" -ForegroundColor Red; exit 1 }

# -File meneruskan "a,b" sebagai satu teks; dipecah di sini.
function Pecah([string[]]$nilai) { @($nilai | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
# @(): fungsi yang mengembalikan array kosong menghasilkan $null.
$Rentang = @(Pecah $Rentang); $Dns = @(Pecah $Dns); $Ip = @(Pecah $Ip)

# ------------------------------------------------------------------ IP dan rentang

function IpSah([string]$s) {
  if ($s -notmatch '^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$') { return $false }
  foreach ($o in $Matches[1..4]) { if ([int]$o -gt 255) { return $false } }
  $true
}

function IpKeAngka([string]$s) {
  $b = [System.Net.IPAddress]::Parse($s).GetAddressBytes()
  ([uint32]$b[0] -shl 24) -bor ([uint32]$b[1] -shl 16) -bor ([uint32]$b[2] -shl 8) -bor [uint32]$b[3]
}

# 4294967295 = 0xFFFFFFFF; literal heksa itu dibaca PowerShell sebagai Int32 -1.
function Masker([int]$prefix) { [uint32](([uint64]4294967295 -shl (32 - $prefix)) -band [uint64]4294967295) }

function AngkaKeIp([uint32]$n) { '{0}.{1}.{2}.{3}' -f (($n -shr 24) -band 255), (($n -shr 16) -band 255), (($n -shr 8) -band 255), ($n -band 255) }

function RentangSah([string]$r) {
  if ($r -notmatch '^([\d.]+)/(\d{1,2})$') { return $false }
  (IpSah $Matches[1]) -and [int]$Matches[2] -ge 8 -and [int]$Matches[2] -le 32
}

function DiRentang([string]$ip, [string]$r) {
  $jaring, $prefix = $r -split '/'
  $m = Masker ([int]$prefix)
  ((IpKeAngka $ip) -band $m) -eq ((IpKeAngka $jaring) -band $m)
}

function NamaDnsSah([string]$d) { $d -match '^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$' }

function DiDomain([string]$nama, [string]$akhiran) { $nama -eq $akhiran -or $nama.EndsWith('.' + $akhiran) }

# ------------------------------------------------------------------ DER name constraints

# New-SelfSignedCertificate tidak menerima name constraints berbentuk teks,
# jadi ekstensinya disusun sendiri (RFC 5280 4.2.1.10).
function Der([byte]$tag, [byte[]]$isi) {
  $l = New-Object System.Collections.Generic.List[byte]
  $l.Add($tag); $n = $isi.Length
  if ($n -lt 128) { $l.Add([byte]$n) } elseif ($n -lt 256) { $l.Add(0x81); $l.Add([byte]$n) } else { $l.Add(0x82); $l.Add([byte]($n -shr 8)); $l.Add([byte]($n -band 255)) }
  $l.AddRange($isi)
  , $l.ToArray()
}

function EkstensiBatas([string[]]$rentang, [string[]]$dns) {
  $sub = New-Object System.Collections.Generic.List[byte]
  foreach ($r in $rentang) {
    $jaring, $prefix = $r -split '/'
    $b = [System.Net.IPAddress]::Parse($jaring).GetAddressBytes() + [System.Net.IPAddress]::Parse((AngkaKeIp (Masker ([int]$prefix)))).GetAddressBytes()
    $sub.AddRange([byte[]](Der 0x30 (Der 0x87 $b)))          # iPAddress [7]: alamat + masker
  }
  # Tanpa akhiran: hanya "invalid" (tidak pernah ada) - tidak ada nama DNS
  # sungguhan yang bisa diterbitkan root ini.
  $daftarDns = if (@($dns).Count) { @($dns) } else { @('invalid') }
  foreach ($d in $daftarDns) {
    $sub.AddRange([byte[]](Der 0x30 (Der 0x82 ([Text.Encoding]::ASCII.GetBytes($d)))))   # dNSName [2]
  }
  $nc = Der 0x30 (Der 0xA0 $sub.ToArray())                 # permittedSubtrees [0]
  New-Object System.Security.Cryptography.X509Certificates.X509Extension('2.5.29.30', $nc, $true)
}

# ------------------------------------------------------------------ bantu

# OO_CA_SANDI / OO_PFX_SANDI yang sudah diisi dipakai apa adanya - hanya untuk
# uji otomatis; jangan disimpan di berkas atau riwayat.
function MintaSandi([string]$env, [string]$tanya, [switch]$Ulang) {
  $isi = [Environment]::GetEnvironmentVariable($env)
  if ($isi) { return (ConvertTo-SecureString $isi -AsPlainText -Force) }
  $s1 = Read-Host -AsSecureString "  $tanya"
  if ((New-Object System.Net.NetworkCredential('', $s1)).Password.Length -lt 12) { Gagal 'Sandi minimal 12 karakter.' }
  if ($Ulang) {
    $s2 = Read-Host -AsSecureString '  Ulangi'
    if ((New-Object System.Net.NetworkCredential('', $s1)).Password -cne (New-Object System.Net.NetworkCredential('', $s2)).Password) { Gagal 'Sandi tidak sama.' }
  }
  $s1
}

function Sidik($cert) {
  $h = [System.Security.Cryptography.SHA256]::Create().ComputeHash($cert.RawData)
  ($h | ForEach-Object { $_.ToString('X2') }) -join ':'
}

function BukanDiRepo([string]$dir) {
  $d = (Resolve-Path $dir).Path
  while ($d) {
    if (Test-Path (Join-Path $d '.git')) { Gagal "Folder '$dir' ada di dalam repo git ($d). Pakai -Folder lain, mis. %USERPROFILE%\oo-ca." }
    $d = Split-Path $d -Parent
  }
}

function San($cert) {
  $e = $cert.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.17' }
  if ($e) { $e.Format($false) } else { '-' }
}

function TulisCrlf([string]$jalur, [string[]]$baris) {
  [IO.File]::WriteAllText($jalur, (($baris -join "`r`n") + "`r`n"), (New-Object Text.UTF8Encoding($false)))
}

function MuatCa {
  $berkas = Join-Path $Folder 'ca.json'
  if (-not (Test-Path $berkas)) { Gagal "Belum ada root CA di $Folder. Jalankan 'sertifikat.ps1 ca-baru' dulu." }
  $ca = Get-Content $berkas -Raw | ConvertFrom-Json
  $root = Get-Item -LiteralPath ("Cert:\CurrentUser\My\" + $ca.Thumbprint) -ErrorAction SilentlyContinue
  if (-not $root -or -not $root.HasPrivateKey) {
    Gagal ("Kunci root tidak ada di Cert:\CurrentUser\My pengguna ini. Impor dulu cadangannya:`n" +
      "  Import-PfxCertificate -FilePath <media offline>\root-cadangan.pfx -CertStoreLocation Cert:\CurrentUser\My -Exportable")
  }
  $ca | Add-Member -NotePropertyName Root -NotePropertyValue $root
  $ca
}

# ------------------------------------------------------------------ ca-baru

function CaBaru {
  if (-not $Rentang.Count) { Gagal '-Rentang wajib, mis. -Rentang 10.114.0.0/16,10.119.0.0/16' }
  foreach ($r in $Rentang) { if (-not (RentangSah $r)) { Gagal "Rentang tidak sah: '$r' (bentuk a.b.c.d/n, n 8..32)." } }
  foreach ($d in $Dns) { if (-not (NamaDnsSah $d)) { Gagal "Akhiran DNS tidak sah: '$d'." } }

  New-Item -ItemType Directory -Force -Path $Folder | Out-Null
  BukanDiRepo $Folder
  if (Test-Path (Join-Path $Folder 'ca.json')) {
    Gagal "Sudah ada root CA di $Folder. Tidak ditimpa - root baru berarti semua klien mengimpor ulang."
  }

  Write-Host ""
  Write-Host "Root CA baru"
  Write-Host "  Sandi ini melindungi cadangan kunci root (root-cadangan.pfx)."
  $sandi = MintaSandi 'OO_CA_SANDI' 'Sandi cadangan root (min. 12 karakter)' -Ulang

  $cn = "OpenOrchestrator Root CA" + $(if ($Label) { " - $Label" } else { '' }) + " ($(Get-Date -Format yyyy-MM-dd))"
  $root = New-SelfSignedCertificate -Type Custom -Subject "CN=$cn, O=OpenOrchestrator" -FriendlyName 'OpenOrchestrator Root CA' `
    -KeyAlgorithm RSA -KeyLength 4096 -HashAlgorithm SHA256 -KeyExportPolicy Exportable `
    -KeyUsage CertSign, CRLSign -NotAfter (Get-Date).AddYears($Tahun) -CertStoreLocation Cert:\CurrentUser\My `
    -TextExtension @('2.5.29.19={critical}{text}ca=1&pathlength=0') -Extension (EkstensiBatas $Rentang $Dns)

  New-Item -ItemType Directory -Force -Path (Join-Path $Folder 'klien'), (Join-Path $Folder 'server') | Out-Null
  Export-Certificate -Cert $root -FilePath (Join-Path $Folder 'root.cer') -Type CERT | Out-Null
  Copy-Item (Join-Path $Folder 'root.cer') (Join-Path $Folder 'klien\root.cer')
  Export-PfxCertificate -Cert $root -FilePath (Join-Path $Folder 'root-cadangan.pfx') -Password $sandi `
    -CryptoAlgorithmOption AES256_SHA256 | Out-Null
  [ordered]@{ Thumbprint = $root.Thumbprint; Rentang = $Rentang; Dns = $Dns; Dibuat = (Get-Date -Format s) } |
    ConvertTo-Json | Set-Content (Join-Path $Folder 'ca.json') -Encoding UTF8

  $sidik = Sidik $root
  $batas = ($Rentang -join ', ') + $(if ($Dns.Count) { ' dan *.' + ($Dns -join ', *.') } else { '' })
  TulisCrlf (Join-Path $Folder 'klien\pasang-root.cmd') @(
    '@echo off',
    'rem Memasang root CA OpenOrchestrator ke "Trusted Root Certification Authorities"',
    'rem komputer ini (sekali per PC). Jalankan sebagai Administrator.',
    "rem Root ini dibatasi: hanya sah untuk $batas.",
    "rem Sidik jari SHA-256 yang benar: $sidik",
    'net session >nul 2>&1 || (echo Jalankan sebagai Administrator. & exit /b 1)',
    'certutil -addstore -f Root "%~dp0root.cer"')

  Write-Host ""
  Write-Host "Selesai."
  Write-Host "  root CA   $cn (berlaku $Tahun tahun)"
  Write-Host "  sidik     SHA-256 $sidik"
  Write-Host "  batas     IP $($Rentang -join ', '); DNS $(if ($Dns.Count) { $Dns -join ', ' } else { '(tidak ada)' })"
  Write-Host "  untuk klien: $Folder\klien\ (root.cer + pasang-root.cmd)"
  Write-Host "  cadangan: $Folder\root-cadangan.pfx - PINDAHKAN ke media offline."
  Write-Host ""
  Write-Host "Langkah berikutnya: .\sertifikat.ps1 server -Nama <nama> -Ip <IP server>"
}

# ------------------------------------------------------------------ server

function Server {
  if ($Nama -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { Gagal 'Nama server wajib (huruf/angka/._-), mis. -Nama oo-bjkt' }
  if (-not $Ip.Count -and -not $Dns.Count) { Gagal 'Sebutkan -Ip dan/atau -Dns.' }
  $ca = MuatCa
  BukanDiRepo $Folder

  # Diperiksa di sini, bukan baru ditolak klien nanti: alamat di luar batas
  # root membuat sertifikatnya ditolak di setiap PC.
  $san = @()
  foreach ($a in $Ip) {
    if (-not (IpSah $a)) { Gagal "IP tidak sah: '$a'." }
    if (-not ($ca.Rentang | Where-Object { DiRentang $a $_ })) {
      Gagal "IP $a di luar batas root CA ($($ca.Rentang -join ', ')). Klien akan menolaknya; perlu root baru dengan rentang yang mencakupnya."
    }
    $san += "IPAddress=$a"
  }
  foreach ($d in $Dns) {
    if (-not (NamaDnsSah $d)) { Gagal "Nama DNS tidak sah: '$d'." }
    if (-not ($ca.Dns | Where-Object { DiDomain $d $_ })) {
      Gagal "Nama $d di luar batas root CA ($(if ($ca.Dns) { $ca.Dns -join ', ' } else { 'tidak ada akhiran DNS' })). Klien akan menolaknya."
    }
    $san += "DNS=$d"
  }

  $akhir = (Get-Date).AddDays($Hari)
  if ($akhir -gt $ca.Root.NotAfter) { $akhir = $ca.Root.NotAfter.AddDays(-1); Write-Host "  catatan: dipotong sampai sehari sebelum root habis ($akhir)." }

  Write-Host ""
  Write-Host "Sertifikat server '$Nama'"
  Write-Host "  Sandi .pfx: diketik lagi saat 'oo https' di server. Kirim sandinya lewat jalur lain dari berkasnya."
  $sandi = MintaSandi 'OO_PFX_SANDI' 'Sandi .pfx (min. 12 karakter)' -Ulang

  $tujuan = Join-Path $Folder "server\$Nama"
  New-Item -ItemType Directory -Force -Path $tujuan | Out-Null
  $pfx = Join-Path $tujuan "$Nama.pfx"
  foreach ($lama in @("$Nama.pfx", "$Nama.cer")) {
    $p = Join-Path $tujuan $lama
    if (Test-Path $p) { Move-Item -Force $p "$p.lama"; Write-Host "  catatan: $lama lama disimpan sebagai $lama.lama" }
  }

  # CN sengaja bukan nama host (ada spasi): yang dicocokkan klien hanya SAN.
  $srv = New-SelfSignedCertificate -Type Custom -Subject "CN=OpenOrchestrator $Nama, O=OpenOrchestrator" `
    -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256 -KeyExportPolicy Exportable `
    -KeyUsage DigitalSignature, KeyEncipherment -NotAfter $akhir -Signer $ca.Root -CertStoreLocation Cert:\CurrentUser\My `
    -TextExtension @('2.5.29.19={critical}{text}ca=0', '2.5.29.37={text}1.3.6.1.5.5.7.3.1', ('2.5.29.17={text}' + ($san -join '&')))
  try {
    # BuildChain: root ikut di .pfx, jadi rantai lengkap terkirim ke klien.
    Export-PfxCertificate -Cert $srv -FilePath $pfx -Password $sandi -ChainOption BuildChain -CryptoAlgorithmOption AES256_SHA256 | Out-Null
    Export-Certificate -Cert $srv -FilePath (Join-Path $tujuan "$Nama.cer") -Type CERT | Out-Null
    $sidik = Sidik $srv
    $sanTeks = San $srv
    $sampai = $srv.NotAfter
  } finally {
    # Kunci server hanya di .pfx; tidak ditinggal di penyimpanan laptop.
    Remove-Item -LiteralPath ("Cert:\CurrentUser\My\" + $srv.Thumbprint) -DeleteKey -Confirm:$false
  }

  Write-Host ""
  Write-Host "Selesai."
  Write-Host "  .pfx      $pfx"
  Write-Host "  SAN       $sanTeks"
  Write-Host "  berlaku   sampai $sampai"
  Write-Host "  sidik     SHA-256 $sidik"
  Write-Host ""
  Write-Host "Di server (Command Prompt admin): salin $Nama.pfx, lalu"
  Write-Host "  oo https D:\$Nama.pfx --port 8443"
  Write-Host "dan hapus salinan .pfx di D:\ sesudahnya."
}

# ------------------------------------------------------------------ daftar

function Daftar {
  $ca = MuatCa
  Write-Host "Root CA  $($ca.Root.Subject)"
  Write-Host "  sidik  SHA-256 $(Sidik $ca.Root)"
  Write-Host "  batas  IP $($ca.Rentang -join ', '); DNS $(if ($ca.Dns) { $ca.Dns -join ', ' } else { '(tidak ada)' })"
  Write-Host "  sampai $($ca.Root.NotAfter)"
  Get-ChildItem (Join-Path $Folder 'server') -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $berkas = Join-Path $_.FullName "$($_.Name).cer"
    if (Test-Path $berkas) {
      $c = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($berkas)
      Write-Host ""
      Write-Host $_.Name
      Write-Host "  SAN    $(San $c)"
      Write-Host "  sampai $($c.NotAfter)$(if ($c.NotAfter -lt (Get-Date).AddDays(30)) { '  <- HAMPIR HABIS' })"
      Write-Host "  sidik  SHA-256 $(Sidik $c)"
    }
  }
}

switch ($Perintah) {
  'ca-baru' { CaBaru }
  'server' { Server }
  'daftar' { Daftar }
}
