// OpenOrchestrator di Windows Server tanpa Docker — alat bantu di balik oo.cmd.
//
// Ditulis untuk Node bawaan saja: tanpa paket npm, tanpa PowerShell. Server
// perusahaan sering membatasi PowerShell (ExecutionPolicy, Constrained
// Language Mode), sedangkan Node toh harus ada untuk dasbor.
//
//   oo pasang [--dump <berkas.dump>] [--superuser <nama>]
//                       sekali, sebagai Administrator: berkas .env, basis data,
//                       firewall, tugas Task Scheduler, lalu menyalakan semuanya
//   oo setelan          hanya membuat data\backend.env dan dasbor.env, untuk diperiksa
//                       atau diubah (mis. CORS_ORIGINS) sebelum 'oo pasang'
//   oo mulai            menyalakan kedua tugas
//   oo hentikan         mematikan backend dan dasbor
//   oo periksa          apakah backend dan dasbor menjawab
//   oo pulihkan <berkas.dump>
//                       memulihkan cadangan pg_dump ke basis data yang masih kosong
//   oo https <berkas.pfx> [--port 8443] [--nama oo.kantor.local,10.1.2.3]
//                       HTTPS lewat gerbang: satu port HTTPS untuk dasbor dan API;
//                       backend dan dasbor hanya menerima dari server ini sendiri
//   oo https --lepas    kembali ke HTTP biasa (3000 dan 8080)
//   oo jalan backend|dasbor|gerbang
//                       penjaga yang dijalankan tugas: menyalakan proses, menulis
//                       catatannya ke log\, dan menyalakannya ulang kalau mati
//
// Tata letak folder (semua relatif terhadap folder berkas ini):
//   backend\openorchestrator-*.jar
//   dasbor\server.js (+ .next\, node_modules\, public\)
//   data\backend.env, data\dasbor.env, data\signing.key   ← hanya Administrator & SYSTEM
//   data\https.env, data\oo.pfx                           ← hanya kalau HTTPS dipasang
//   log\backend.log, log\dasbor.log, log\gerbang.log
//   runtime\java, runtime\node, runtime\pgsql             ← opsional, versi ZIP portabel

"use strict";

const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const crypto = require("node:crypto");
const http = require("node:http");
const https = require("node:https");
const tls = require("node:tls");
const net = require("node:net");
const { spawn, spawnSync } = require("node:child_process");

const ROOT = __dirname;
const DATA = path.join(ROOT, "data");
const LOG = path.join(ROOT, "log");
const BACKEND_ENV = path.join(DATA, "backend.env");
const DASBOR_ENV = path.join(DATA, "dasbor.env");
const HTTPS_ENV = path.join(DATA, "https.env");

const TUGAS = {
  backend: "OpenOrchestrator\\Backend",
  dasbor: "OpenOrchestrator\\Dasbor",
  gerbang: "OpenOrchestrator\\Gerbang",
};

// Gerbang hanya ikut kalau HTTPS dipasang ('oo https').
function adaHttps() {
  return fs.existsSync(HTTPS_ENV);
}

function tugasAktif() {
  return Object.keys(TUGAS).filter((n) => n !== "gerbang" || adaHttps());
}

// SID, bukan nama: "Administrators" bernama lain di Windows berbahasa lain.
const SID_ADMIN = "*S-1-5-32-544";
const SID_SYSTEM = "*S-1-5-18";

function gagal(pesan) {
  console.error("\nGAGAL: " + pesan);
  process.exit(1);
}

function info(pesan) {
  console.log(pesan);
}

// ---------------------------------------------------------------- berkas .env

// Format KUNCI=nilai, satu per baris, # untuk komentar. Sengaja sesederhana
// ini: berkas yang sama dibaca Spring Boot sebagai .properties dan Node lewat
// --env-file, jadi nilai tidak boleh diberi tanda kutip dan tidak boleh
// memuat garis miring terbalik.
function bacaEnv(berkas) {
  const hasil = {};
  for (const baris of fs.readFileSync(berkas, "utf8").replace(/^\uFEFF/, "").split(/\r?\n/)) {
    const t = baris.trim();
    if (!t || t.startsWith("#")) continue;
    const i = t.indexOf("=");
    if (i > 0) hasil[t.slice(0, i).trim()] = t.slice(i + 1).trim();
  }
  return hasil;
}

// Mengganti nilai satu kunci (atau menambahkannya di akhir); komentar dan
// baris lain dibiarkan. nilai null menghapus barisnya.
function ubahEnv(berkas, kunci, nilai) {
  const baris = fs.readFileSync(berkas, "utf8").replace(/^﻿/, "").split(/\r?\n/);
  const i = baris.findIndex((b) => b.trim().startsWith(kunci + "="));
  if (nilai === null) {
    if (i >= 0) baris.splice(i, 1);
  } else if (i >= 0) {
    baris[i] = kunci + "=" + nilai;
  } else {
    while (baris.length && baris[baris.length - 1].trim() === "") baris.pop();
    baris.push(kunci + "=" + nilai, "");
  }
  fs.writeFileSync(berkas, baris.join("\r\n"));
}

function acak(byte) {
  // base64url: aman di SQL, di .properties, dan di baris perintah.
  return crypto.randomBytes(byte).toString("base64url");
}

function garisMiring(p) {
  return p.replace(/\\/g, "/");
}

function siapkanEnv() {
  fs.mkdirSync(DATA, { recursive: true });
  fs.mkdirSync(LOG, { recursive: true });

  if (!fs.existsSync(BACKEND_ENV)) {
    const isi = [
      "# Setelan backend OpenOrchestrator di server ini. Dibuat oleh 'oo pasang'",
      "# pada " + new Date().toISOString().slice(0, 10) + ".",
      "#",
      "# RAHASIA: DB_PASSWORD dan JWT_SECRET. Folder data\\ hanya bisa dibaca",
      "# Administrator dan SYSTEM; jangan salin berkas ini ke chat, email, atau git.",
      "#",
      "# Dibaca Spring Boot sebagai .properties: tanpa tanda kutip, dan jalur",
      "# ditulis dengan garis miring biasa (C:/...), bukan garis miring terbalik.",
      "",
      "DB_URL=jdbc:postgresql://localhost:5432/openorchestrator",
      "DB_USER=openorchestrator",
      "DB_PASSWORD=" + acak(24),
      "",
      "# Penanda tangan token login. Mengubahnya mengeluarkan semua yang sedang masuk.",
      "JWT_SECRET=" + acak(48),
      "",
      "# Alamat dasbor seperti yang diketik pengguna di peramban. Asal pertama juga",
      "# dipakai untuk mengalihkan / dan /assistant/connect ke dasbor. Beberapa",
      "# alamat dipisah koma, mis. http://oo-server:3000,http://oo-server.kantor.local:3000",
      "CORS_ORIGINS=http://" + os.hostname().toLowerCase() + ":3000",
      "",
      "# Kunci penyandian kredensial. Kalau membawa data dari mesin lain, kunci",
      "# dari mesin itu HARUS disalin ke sini sebelum backend pertama kali menyala;",
      "# kalau tidak ada, backend membuat kunci baru dan kredensial lama tak terbaca.",
      "OPENORCHESTRATOR_SIGNING_KEY=" + garisMiring(path.join(DATA, "signing.key")),
      "",
      "OPENORCHESTRATOR_TZ=Asia/Jakarta",
      "SERVER_PORT=8080",
      "",
    ].join("\r\n");
    fs.writeFileSync(BACKEND_ENV, isi);
    info("  dibuat   data\\backend.env (sandi basis data dan JWT_SECRET acak)");
  } else {
    info("  ada      data\\backend.env — dipakai apa adanya");
  }

  const be = bacaEnv(BACKEND_ENV);
  if (!fs.existsSync(DASBOR_ENV)) {
    const isi = [
      "# Setelan dasbor OpenOrchestrator (node --env-file). Tidak berisi rahasia.",
      "",
      "PORT=3000",
      "# 0.0.0.0 = menerima dari jaringan, bukan hanya dari server ini sendiri.",
      "HOSTNAME=0.0.0.0",
      "# Ke mana dasbor meneruskan /api. Backend di server yang sama.",
      "API_INTERNAL_URL=http://localhost:" + (be.SERVER_PORT || "8080"),
      "NODE_ENV=production",
      "",
    ].join("\r\n");
    fs.writeFileSync(DASBOR_ENV, isi);
    info("  dibuat   data\\dasbor.env");
  } else {
    info("  ada      data\\dasbor.env — dipakai apa adanya");
  }

  if (!fs.existsSync(path.join(DATA, "signing.key"))) {
    info("  catatan  data\\signing.key belum ada: backend akan membuat kunci baru.");
    info("           Kalau membawa data dari laptop, hentikan di sini, salin signing.key");
    info("           laptop ke data\\, lalu jalankan 'oo pasang' lagi.");
  }
}

function kunciFolderData() {
  // Hanya Administrator dan SYSTEM (akun yang menjalankan tugas). Warisan izin
  // dari C:\ diputus; kalau tidak, grup Users ikut bisa membaca sandinya.
  const r = spawnSync("icacls", [DATA, "/inheritance:r", "/grant:r",
    SID_ADMIN + ":(OI)(CI)F", SID_SYSTEM + ":(OI)(CI)F", "/T", "/Q"], { encoding: "utf8" });
  if (r.status !== 0) gagal("icacls tidak bisa membatasi izin folder data:\n" + (r.stdout || "") + (r.stderr || ""));
  info("  izin     data\\ hanya untuk Administrators dan SYSTEM");
}

// ------------------------------------------------------------ alamat program

function cariProgram(nama, kandidat) {
  for (const k of kandidat) if (k && fs.existsSync(k)) return k;
  const r = spawnSync("where", [nama], { encoding: "utf8" });
  if (r.status === 0) return r.stdout.split(/\r?\n/)[0].trim();
  return null;
}

function javaExe() {
  return cariProgram("java.exe", [
    path.join(ROOT, "runtime", "java", "bin", "java.exe"),
    process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, "bin", "java.exe"),
  ]);
}

function nodeExe() {
  const portabel = path.join(ROOT, "runtime", "node", "node.exe");
  return fs.existsSync(portabel) ? portabel : process.execPath;
}

function pgBin(nama) {
  const pf = process.env.ProgramFiles || "C:\\Program Files";
  return cariProgram(nama + ".exe", [
    path.join(ROOT, "runtime", "pgsql", "bin", nama + ".exe"),
    path.join(pf, "PostgreSQL", "17", "bin", nama + ".exe"),
  ]);
}

function jarBackend() {
  const folder = path.join(ROOT, "backend");
  const jar = fs.existsSync(folder) && fs.readdirSync(folder).find((f) => /^openorchestrator-.*\.jar$/.test(f));
  return jar ? path.join(folder, jar) : null;
}

function dbDariUrl(url) {
  const m = /^jdbc:postgresql:\/\/([^/:?]+)(?::(\d+))?\/([^?]+)/.exec(url || "");
  if (!m) gagal("DB_URL di data\\backend.env tidak berbentuk jdbc:postgresql://host:port/nama: " + url);
  return { host: m[1], port: m[2] || "5432", nama: m[3] };
}

// ------------------------------------------------------------ pemeriksaan awal

function periksaPrasyarat() {
  const java = javaExe();
  if (!java) gagal("Java tidak ditemukan. Pasang Eclipse Temurin JDK 25 (centang 'Add to PATH'),\n"
    + "atau ekstrak versi ZIP-nya ke runtime\\java.");
  const v = spawnSync(java, ["-version"], { encoding: "utf8" });
  const m = /version "(\d+)/.exec((v.stderr || "") + (v.stdout || ""));
  if (!m || Number(m[1]) < 25) gagal("Backend butuh Java 25 atau lebih baru; yang ditemukan: "
    + (m ? m[1] : "tidak diketahui") + " (" + java + ")");
  info("  java     " + m[1] + "  " + java);

  const [mayor] = process.versions.node.split(".").map(Number);
  if (mayor < 22) gagal("Butuh Node.js 22 atau lebih baru; yang berjalan: " + process.versions.node);
  info("  node     " + process.versions.node + "  " + nodeExe());

  const jar = jarBackend();
  if (!jar) gagal("Tidak ada openorchestrator-*.jar di folder backend\\.");
  info("  backend  " + path.relative(ROOT, jar));

  for (const p of ["dasbor\\server.js", "dasbor\\.next\\static", "dasbor\\public"]) {
    if (!fs.existsSync(path.join(ROOT, p))) gagal("Folder dasbor tidak lengkap: " + p + " tidak ada.\n"
      + "Susun ulang paketnya di laptop dengan susun-paket.mjs.");
  }
  info("  dasbor   dasbor\\server.js");
}

function harusAdmin() {
  if (spawnSync("net", ["session"], { stdio: "ignore" }).status !== 0) {
    gagal("Jalankan dari Command Prompt 'Run as administrator'.");
  }
}

// ---------------------------------------------------------------- basis data

function buatBasisData(superuser) {
  const env = bacaEnv(BACKEND_ENV);
  const db = dbDariUrl(env.DB_URL);
  const user = env.DB_USER || "openorchestrator";
  for (const [k, v] of [["DB_USER", user], ["nama basis data", db.nama]]) {
    if (!/^[a-z_][a-z0-9_]*$/.test(v)) gagal(k + " hanya boleh huruf kecil, angka, dan _: " + v);
  }
  const sandi = (env.DB_PASSWORD || "").replace(/'/g, "''");
  if (!sandi) gagal("DB_PASSWORD kosong di data\\backend.env.");

  const psql = pgBin("psql");
  if (!psql) gagal("psql tidak ditemukan. Pasang PostgreSQL 17, atau ekstrak versi ZIP-nya ke runtime\\pgsql.");

  // Sementara, di folder yang izinnya sudah dibatasi, dan dihapus begitu
  // selesai: sandinya tidak lewat baris perintah, yang bisa dibaca proses lain.
  const sql = path.join(DATA, "buat-db.sql");
  fs.writeFileSync(sql, [
    "\\set ON_ERROR_STOP on",
    `SELECT 'CREATE ROLE ${user} LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${user}')\\gexec`,
    `ALTER ROLE ${user} WITH LOGIN PASSWORD '${sandi}';`,
    `SELECT 'CREATE DATABASE ${db.nama} OWNER ${user} ENCODING ''UTF8'' TEMPLATE template0'`
      + ` WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${db.nama}')\\gexec`,
    "",
  ].join("\n"));

  info(`\n  Basis data: psql meminta sandi superuser PostgreSQL '${superuser}'`);
  info("  (yang dibuat saat memasang PostgreSQL).");
  try {
    const r = spawnSync(psql, ["-h", db.host, "-p", db.port, "-U", superuser, "-d", "postgres", "-q", "-f", sql],
      { stdio: "inherit" });
    if (r.status !== 0) gagal("psql gagal membuat user/basis data (kode " + r.status + ").");
  } finally {
    fs.rmSync(sql, { force: true });
  }
  info(`  siap     user '${user}' dan basis data '${db.nama}' di ${db.host}:${db.port}`);
}

function psqlUser() {
  const env = bacaEnv(BACKEND_ENV);
  const db = dbDariUrl(env.DB_URL);
  // PGPASSWORD lewat lingkungan proses anak, bukan argumen.
  return {
    db,
    env: { ...process.env, PGPASSWORD: env.DB_PASSWORD },
    dasar: ["-h", db.host, "-p", db.port, "-U", env.DB_USER || "openorchestrator", "-d", db.nama],
  };
}

function pulihkan(dump) {
  if (!dump || !fs.existsSync(dump)) gagal("Berkas cadangan tidak ditemukan: " + (dump || "(kosong)"));
  if (berjalan("backend")) gagal("Backend sedang berjalan. Jalankan 'oo hentikan' dulu.");

  const psql = pgBin("psql");
  const pgRestore = pgBin("pg_restore");
  if (!psql || !pgRestore) gagal("psql/pg_restore tidak ditemukan.");
  const k = psqlUser();

  // Hanya ke basis data kosong. Kalau backend sudah pernah menyala, Flyway
  // sudah membuat tabelnya dan pemulihan akan bentrok di tengah jalan.
  const n = spawnSync(psql, [...k.dasar, "-tAc",
    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"], { env: k.env, encoding: "utf8" });
  if (n.status !== 0) gagal("Tidak bisa masuk ke basis data dengan setelan di data\\backend.env:\n" + n.stderr);
  if (Number(n.stdout.trim()) > 0) {
    gagal(`Basis data '${k.db.nama}' sudah berisi ${n.stdout.trim()} tabel. Pemulihan hanya ke basis data kosong:\n`
      + `hapus dan buat ulang dulu (DROP DATABASE ${k.db.nama}; lalu 'oo pasang' lagi), atau pakai basis data lain.`);
  }

  if (!fs.existsSync(path.join(DATA, "signing.key"))) {
    gagal("data\\signing.key belum ada. Salin signing.key dari mesin asal cadangan ini dulu —\n"
      + "tanpa kunci yang sama, semua kredensial di cadangan tidak bisa dibuka.");
  }

  info("  memulihkan " + dump + " …");
  const r = spawnSync(pgRestore, [...k.dasar, "--no-owner", "--no-privileges", "--exit-on-error", dump],
    { env: k.env, stdio: "inherit" });
  if (r.status !== 0) gagal("pg_restore gagal (kode " + r.status + ").");
  info("  pulih    backend akan menjalankan migrasi yang belum ada saat menyala");
}

// ---------------------------------------------------------------- Windows

function firewall() {
  const be = bacaEnv(BACKEND_ENV);
  const da = bacaEnv(DASBOR_ENV);
  const semua = [["OpenOrchestrator API", be.SERVER_PORT || "8080"], ["OpenOrchestrator Dasbor", da.PORT || "3000"],
    ["OpenOrchestrator HTTPS", adaHttps() ? bacaEnv(HTTPS_ENV).HTTPS_PORT || "8443" : ""]];
  // Dengan HTTPS hanya port gerbang yang dibuka; backend dan dasbor cuma
  // mendengar di 127.0.0.1, dan aturan lamanya dihapus.
  const buka = adaHttps() ? semua.slice(2) : semua.slice(0, 2);
  for (const [nama] of semua.filter((a) => !buka.includes(a))) {
    spawnSync("netsh", ["advfirewall", "firewall", "delete", "rule", "name=" + nama], { stdio: "ignore" });
  }
  for (const [nama, port] of buka) {
    const ada = spawnSync("netsh", ["advfirewall", "firewall", "show", "rule", "name=" + nama], { stdio: "ignore" }).status === 0;
    if (ada) {
      spawnSync("netsh", ["advfirewall", "firewall", "set", "rule", "name=" + nama, "new", "localport=" + port], { stdio: "ignore" });
    } else {
      const r = spawnSync("netsh", ["advfirewall", "firewall", "add", "rule", "name=" + nama, "dir=in", "action=allow",
        "protocol=TCP", "localport=" + port], { encoding: "utf8" });
      if (r.status !== 0) {
        info(`  PERINGATAN firewall: aturan '${nama}' (TCP ${port}) tidak bisa dibuat — mungkin diatur GPO.`);
        info("             Minta tim IT membuka port masuk TCP " + port + ".");
        continue;
      }
    }
    info(`  firewall TCP ${port} masuk diizinkan (${nama})`);
  }
}

function xml(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function daftarkanTugas() {
  const node = nodeExe();
  if (!adaHttps()) spawnSync("schtasks", ["/Delete", "/TN", TUGAS.gerbang, "/F"], { stdio: "ignore" });
  for (const nama of tugasAktif()) {
    const tn = TUGAS[nama];
    // Lewat XML karena dua setelan bawaan schtasks /Create merusak layanan
    // yang berjalan terus: batas waktu 72 jam (lalu dimatikan) dan prioritas 7
    // (di bawah normal). Menyalakan ulang proses yang mati diurus penjaga
    // ('oo jalan'), bukan Task Scheduler.
    const isi = `<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>OpenOrchestrator ${nama} (oo.cjs jalan ${nama})</Description></RegistrationInfo>
  <Triggers><BootTrigger><Enabled>true</Enabled></BootTrigger></Triggers>
  <Principals><Principal id="Author"><UserId>S-1-5-18</UserId><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings><StopOnIdleEnd>false</StopOnIdleEnd><RestartOnIdle>false</RestartOnIdle></IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>5</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>${xml(node)}</Command>
      <Arguments>"${xml(path.join(ROOT, "oo.cjs"))}" jalan ${nama}</Arguments>
      <WorkingDirectory>${xml(ROOT)}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
`;
    const berkas = path.join(DATA, "tugas-" + nama + ".xml");
    fs.writeFileSync(berkas, Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(isi.replace(/\n/g, "\r\n"), "utf16le")]));
    try {
      const r = spawnSync("schtasks", ["/Create", "/TN", tn, "/XML", berkas, "/F"], { encoding: "utf8" });
      if (r.status !== 0) gagal("schtasks tidak bisa mendaftarkan " + tn + ":\n" + (r.stdout || "") + (r.stderr || ""));
    } finally {
      fs.rmSync(berkas, { force: true });
    }
    info(`  tugas    ${tn} (saat server menyala, akun SYSTEM)`);
  }
}

function mulai() {
  for (const nama of tugasAktif()) {
    const r = spawnSync("schtasks", ["/Run", "/TN", TUGAS[nama]], { encoding: "utf8" });
    if (r.status !== 0) gagal("schtasks /Run " + TUGAS[nama] + ":\n" + (r.stdout || "") + (r.stderr || ""));
  }
  info("  dinyalakan: " + tugasAktif().join(", "));
}

// ------------------------------------------------------------ penjaga proses

function berkasPid(nama) {
  return path.join(LOG, nama + ".pid");
}

function hidup(pid) {
  try { process.kill(pid, 0); return true; } catch { return false; }
}

function berjalan(nama) {
  try {
    const pid = Number(fs.readFileSync(berkasPid(nama), "utf8"));
    return pid && hidup(pid) ? pid : null;
  } catch {
    return null;
  }
}

function hentikan() {
  for (const [nama, tn] of Object.entries(TUGAS)) {
    const pid = berjalan(nama);
    if (!pid && nama === "gerbang" && !adaHttps()) {
      spawnSync("schtasks", ["/End", "/TN", tn], { stdio: "ignore" });
      continue;
    }
    if (pid) {
      // /T: penjaga beserta java/node yang dijalankannya.
      spawnSync("taskkill", ["/PID", String(pid), "/T", "/F"], { stdio: "ignore" });
      info(`  dihentikan ${nama} (pid ${pid})`);
    } else {
      info(`  ${nama} tidak berjalan`);
    }
    fs.rmSync(berkasPid(nama), { force: true });
    spawnSync("schtasks", ["/End", "/TN", tn], { stdio: "ignore" });
  }
}

const BATAS_LOG = 20 * 1024 * 1024;

function bukaLog(nama) {
  const berkas = path.join(LOG, nama + ".log");
  let ukuran = fs.existsSync(berkas) ? fs.statSync(berkas).size : 0;
  let fd = fs.openSync(berkas, "a");
  const cermin = process.stdout.isTTY;
  return (teks) => {
    if (ukuran > BATAS_LOG) {
      // Satu cadangan (.1.log); yang lebih lama dibuang.
      fs.closeSync(fd);
      const lama = path.join(LOG, nama + ".1.log");
      fs.rmSync(lama, { force: true });
      fs.renameSync(berkas, lama);
      fd = fs.openSync(berkas, "a");
      ukuran = 0;
    }
    fs.writeSync(fd, teks);
    ukuran += Buffer.byteLength(teks);
    if (cermin) process.stdout.write(teks);
  };
}

function jalan(nama) {
  if (!TUGAS[nama]) gagal("oo jalan backend|dasbor|gerbang");
  fs.mkdirSync(LOG, { recursive: true });
  const lain = berjalan(nama);
  if (lain && lain !== process.pid) gagal(nama + " sudah berjalan (pid " + lain + ").");
  fs.writeFileSync(berkasPid(nama), String(process.pid));

  const tulis = bukaLog(nama);
  const cap = (s) => tulis(`${new Date().toISOString()} [penjaga] ${s}\r\n`);

  let perintah, args, cwd;
  if (nama === "backend") {
    perintah = javaExe();
    const jar = jarBackend();
    if (!perintah || !jar) { cap("java atau jar backend tidak ditemukan"); process.exit(1); }
    // Spring Boot membaca berkas .env sebagai .properties; ${DB_URL} dan
    // kawan-kawan di application.yml terisi dari sana. Tidak 'optional:' —
    // berkas yang hilang harus menggagalkan, bukan diam-diam memakai bawaan.
    args = ["-Dspring.config.import=file:" + garisMiring(BACKEND_ENV) + "[.properties]", "-jar", jar];
    cwd = path.join(ROOT, "backend");
  } else if (nama === "dasbor") {
    perintah = nodeExe();
    args = ["--env-file=" + DASBOR_ENV, "server.js"];
    cwd = path.join(ROOT, "dasbor");
  } else {
    // Gerbang HTTPS di proses anak sendiri, supaya dinyalakan ulang dengan
    // cara yang sama kalau mati.
    if (!adaHttps()) { cap("HTTPS belum dipasang (data\\https.env tidak ada)"); process.exit(1); }
    perintah = nodeExe();
    args = [path.join(ROOT, "oo.cjs"), "gerbang-proses"];
    cwd = ROOT;
  }

  let jeda = 5;
  const nyalakan = () => {
    const awal = Date.now();
    cap("menyalakan: " + perintah + " " + args.join(" "));
    const anak = spawn(perintah, args, { cwd, stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
    anak.stdout.on("data", (b) => tulis(b.toString()));
    anak.stderr.on("data", (b) => tulis(b.toString()));
    anak.on("error", (e) => cap("tidak bisa dijalankan: " + e.message));
    anak.on("close", (kode) => {
      // Mati cepat berulang (mis. basis data belum siap saat boot): jeda
      // dinaikkan sampai 60 detik supaya log tidak banjir.
      jeda = Date.now() - awal > 120_000 ? 5 : Math.min(jeda * 2, 60);
      cap(`berhenti (kode ${kode}); dinyalakan lagi dalam ${jeda} detik`);
      setTimeout(nyalakan, jeda * 1000);
    });
  };
  nyalakan();
}

// ---------------------------------------------------------------- HTTPS

// Sandi tanpa gema ke layar; dari pipa (bukan konsol) dibaca satu baris.
function tanyaRahasia(tanya) {
  return new Promise((selesai) => {
    process.stdout.write(tanya);
    const stdin = process.stdin;
    stdin.setEncoding("utf8");
    let isi = "";
    if (!stdin.isTTY) {
      const baca = (d) => {
        isi += d;
        const i = isi.indexOf("\n");
        if (i >= 0) { stdin.off("data", baca); stdin.pause(); process.stdout.write("\n"); selesai(isi.slice(0, i).replace(/\r$/, "")); }
      };
      stdin.on("data", baca);
      return;
    }
    stdin.setRawMode(true);
    stdin.resume();
    const baca = (d) => {
      for (const c of d) {
        if (c === "\r" || c === "\n") {
          stdin.setRawMode(false); stdin.off("data", baca); stdin.pause();
          process.stdout.write("\n");
          return selesai(isi);
        }
        if (c === "\u0003") { process.stdout.write("\n"); process.exit(130); }
        if (c === "\b" || c === "\u007f") { isi = isi.slice(0, -1); continue; }
        isi += c;
      }
    };
    stdin.on("data", baca);
  });
}

// Membuka .pfx dan membaca sertifikatnya. Node tidak punya pembaca PKCS#12
// publik, jadi sertifikatnya diambil lewat jabat tangan TLS ke diri sendiri di
// 127.0.0.1 — rejectUnauthorized: false di sini hanya untuk MEMBACA sertifikat
// milik sendiri, bukan untuk memercayai pihak lain.
async function bacaSertifikat(pfx, passphrase) {
  try {
    tls.createSecureContext({ pfx, passphrase });
  } catch (e) {
    gagal("Berkas .pfx tidak bisa dibuka — sandinya salah, atau berkasnya tidak berisi kunci privat.\n(" + e.message + ")");
  }
  const server = tls.createServer({ pfx, passphrase });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  try {
    return await bacaSertifikatDari(server.address().port);
  } finally {
    server.close();
  }
}

// Sertifikat yang disajikan di port ini (127.0.0.1), beserta rantai yang ikut
// DIKIRIM — itulah yang diterima robot dan peramban. Hanya membaca; tidak ada
// yang dipercaya dari sini.
function bacaSertifikatDari(port, servername) {
  return new Promise((selesai, tolak) => {
    const s = tls.connect({ host: "127.0.0.1", port, rejectUnauthorized: false,
      ...(servername && !net.isIP(servername) ? { servername } : {}) }, () => {
      // getPeerCertificate(true) LEBIH DULU: sesudah getPeerX509Certificate()
      // ia mengembalikan objek kosong (Node 22).
      const puncak = s.getPeerCertificate(true);
      const rantai = [];
      const dilihat = new Set();
      for (let c = puncak; c && c.fingerprint256 && !dilihat.has(c.fingerprint256); c = c.issuerCertificate) {
        dilihat.add(c.fingerprint256);
        rantai.push(c);
      }
      s.end();
      if (!puncak || !puncak.raw) return tolak(new Error("tidak ada sertifikat"));
      selesai({ cert: new crypto.X509Certificate(puncak.raw), rantai });
    });
    s.setTimeout(5000, () => s.destroy(new Error("waktu habis")));
    s.on("error", tolak);
  });
}

// subject berupa teks (X509Certificate) atau objek (getPeerCertificate).
function namaUmum(subject) {
  if (subject && typeof subject === "object") return subject.CN || Object.values(subject).join(", ") || "?";
  const cn = /(?:^|\n)CN=([^\n]+)/.exec(subject || "");
  return cn ? cn[1] : (subject || "?").replace(/\n/g, ", ");
}

function portBebas(port) {
  return new Promise((selesai) => {
    const s = net.createServer();
    s.once("error", (e) => selesai(e.code));
    s.listen(Number(port), () => s.close(() => selesai(null)));
  });
}

// Alamat di SAN sertifikat: nama DNS dan alamat IP. CN tidak dibaca — klien
// modern (termasuk robot) hanya mencocokkan SAN.
function alamatSertifikat(cert) {
  const san = (cert.subjectAltName || "").split(/,\s*/);
  return {
    dns: san.filter((s) => s.startsWith("DNS:")).map((s) => s.slice(4)),
    ip: san.filter((s) => s.startsWith("IP Address:")).map((s) => s.slice(11)),
  };
}

function berlakuUntuk(cert, nama) {
  return net.isIP(nama) ? cert.checkIP(nama) !== undefined : cert.checkHost(nama) !== undefined;
}

function urlHttps(nama, port) {
  const host = net.isIP(nama) === 6 ? `[${nama}]` : nama;
  return "https://" + host + (port === "443" ? "" : ":" + port);
}

async function pasangHttps(args) {
  harusAdmin();
  if (!fs.existsSync(BACKEND_ENV) || !fs.existsSync(DASBOR_ENV)) gagal("Jalankan 'oo pasang' dulu.");
  if (args.includes("--lepas")) return lepasHttps();

  const sumber = args[0] && !args[0].startsWith("--") ? path.resolve(args[0]) : null;
  if (!sumber || !fs.existsSync(sumber)) gagal("Berkas .pfx tidak ditemukan: " + (args[0] || "(kosong)")
    + "\nPemakaian: oo https D:\\oo.pfx [--port 8443] [--nama oo.kantor.local,10.1.2.3]");
  const port = opsi(args, "--port") || (adaHttps() ? bacaEnv(HTTPS_ENV).HTTPS_PORT : "") || "8443";
  if (!/^\d{1,5}$/.test(port)) gagal("--port harus angka: " + port);

  info("\n[1/4] Sertifikat");
  const pfx = fs.readFileSync(sumber);
  const sandi = await tanyaRahasia("  Sandi berkas .pfx: ");
  const { cert, rantai } = await bacaSertifikat(pfx, sandi);
  const san = alamatSertifikat(cert);
  const selfSigned = cert.issuer === cert.subject;
  info("  untuk    " + ([...san.dns, ...san.ip].join(", ") || "(SAN kosong) " + namaUmum(cert.subject)));
  info("  penerbit " + cert.issuer.replace(/\n/g, ", "));
  info("  berlaku  sampai " + cert.validTo);
  info("  sidik    SHA-256 " + cert.fingerprint256);
  info("  rantai   " + rantai.map((c) => namaUmum(c.subject)).join(" → ") + "  (yang dikirim ke klien)");
  const sisaHari = Math.floor((new Date(cert.validTo) - Date.now()) / 86_400_000);
  if (sisaHari < 0) gagal("Sertifikat ini sudah kedaluwarsa.");
  if (sisaHari < 30) info(`  PERINGATAN: sertifikat habis dalam ${sisaHari} hari.`);
  if (selfSigned) {
    info("  PERINGATAN: sertifikat ini ditandatangani sendiri (self-signed). Setiap PC (peramban,");
    info("             robot, Studio) harus memercayainya secara manual. Lebih baik minta dari CA kantor.");
  } else if (rantai.length === 1) {
    // Tanpa sertifikat penerbit (intermediate), PC yang tidak bisa mengunduhnya
    // sendiri — jaringan tertutup — menolak rantainya walau root-nya dipercaya.
    info("  PERINGATAN: .pfx hanya berisi sertifikat server, tanpa sertifikat penerbitnya. Kalau");
    info("             penerbitnya intermediate CA, ekspor ulang .pfx dengan 'Include all certificates");
    info("             in the certification path if possible'.");
  }
  if (Array.isArray(cert.keyUsage) && !cert.keyUsage.includes("1.3.6.1.5.5.7.3.1")) {
    info("  PERINGATAN: sertifikat ini tidak untuk 'Server Authentication'; peramban bisa menolaknya.");
  }

  // Alamat yang dipakai pengguna dan robot: --nama (dipisah koma), atau semua
  // nama DNS dan IP di SAN. Masing-masing harus berlaku di sertifikat.
  let nama = (opsi(args, "--nama") || "").split(",").map((n) => n.trim()).filter(Boolean);
  if (!nama.length) nama = [...san.dns.filter((d) => !d.startsWith("*.")), ...san.ip];
  if (!nama.length && san.dns[0]) nama = [os.hostname().toLowerCase() + san.dns[0].slice(1)];
  const tidakBerlaku = nama.filter((n) => !berlakuUntuk(cert, n));
  if (!nama.length || tidakBerlaku.length) {
    gagal(`Sertifikat tidak berlaku untuk ${tidakBerlaku.length ? "'" + tidakBerlaku.join("', '") + "'" : "alamat mana pun"}. `
      + `Alamat di sertifikat (SAN): ${[...san.dns, ...san.ip].join(", ") || "-"}.\n`
      + "Pakai --nama dengan alamat yang ada di sertifikat (pisahkan dengan koma), atau minta sertifikat\n"
      + "yang SAN-nya memuat alamat itu.");
  }
  const alamatSemua = nama.map((n) => urlHttps(n, port));
  const alamat = alamatSemua[0];

  info("\n[2/4] Port " + port);
  // Diperiksa sebelum apa pun dimatikan, supaya kegagalan di sini tidak
  // meninggalkan layanan mati. Port yang dipegang gerbang kita sendiri boleh.
  const milikSendiri = berjalan("gerbang") && adaHttps() && bacaEnv(HTTPS_ENV).HTTPS_PORT === port;
  if (!milikSendiri) {
    const sibuk = await portBebas(port);
    if (sibuk) gagal(`Port ${port} sudah dipakai program lain (${sibuk}). Pilih port lain dengan --port.`);
  }
  info("  bebas");
  hentikan();

  info("\n[3/4] Setelan");
  const tujuan = path.join(DATA, "oo.pfx");
  if (path.resolve(sumber).toLowerCase() !== tujuan.toLowerCase()) fs.copyFileSync(sumber, tujuan);
  fs.writeFileSync(HTTPS_ENV, [
    "# Gerbang HTTPS OpenOrchestrator ('oo https'). RAHASIA: sandi .pfx.",
    "HTTPS_PORT=" + port,
    "HTTPS_PFX=" + garisMiring(tujuan),
    "HTTPS_PFX_PASSWORD=" + sandi,
    "HTTPS_NAMA=" + nama.join(","),
    "",
  ].join("\r\n"));
  const be = bacaEnv(BACKEND_ENV);
  // Semua alamat: peramban yang membuka dasbor lewat IP mana pun diterima.
  ubahEnv(BACKEND_ENV, "CORS_ORIGINS", alamatSemua.join(","));
  // Backend dan dasbor hanya bisa dicapai lewat gerbang. Kunci bertitik:
  // server.address tidak punya placeholder di application.yml.
  ubahEnv(BACKEND_ENV, "server.address", "127.0.0.1");
  ubahEnv(DASBOR_ENV, "HOSTNAME", "127.0.0.1");
  ubahEnv(DASBOR_ENV, "API_INTERNAL_URL", "http://127.0.0.1:" + (be.SERVER_PORT || "8080"));
  kunciFolderData();
  info("  data\\oo.pfx, data\\https.env; CORS_ORIGINS=" + alamatSemua.join(","));

  info("\n[4/4] Firewall, Task Scheduler, menyalakan");
  firewall();
  daftarkanTugas();
  mulai();
  const baik = await periksa({ tunggu: 300 });
  info(`\n${baik ? "Selesai" : "Belum siap — lihat log\\"}. Dasbor dan API untuk robot/Studio/Open Assistant: `
    + alamatSemua.join(" atau "));
  if (!baik) process.exit(1);
}

async function lepasHttps() {
  hentikan();
  const be = bacaEnv(BACKEND_ENV);
  fs.rmSync(HTTPS_ENV, { force: true });
  fs.rmSync(path.join(DATA, "oo.pfx"), { force: true });
  ubahEnv(BACKEND_ENV, "server.address", null);
  ubahEnv(BACKEND_ENV, "CORS_ORIGINS", "http://" + os.hostname().toLowerCase() + ":" + (bacaEnv(DASBOR_ENV).PORT || "3000"));
  ubahEnv(DASBOR_ENV, "HOSTNAME", "0.0.0.0");
  ubahEnv(DASBOR_ENV, "API_INTERNAL_URL", "http://127.0.0.1:" + (be.SERVER_PORT || "8080"));
  info("  HTTPS dilepas; kembali ke HTTP. Periksa CORS_ORIGINS di data\\backend.env.");
  firewall();
  daftarkanTugas();
  mulai();
  await periksa({ tunggu: 300 });
}

// Header yang hanya berlaku untuk satu sambungan, tidak diteruskan.
const HOP = ["connection", "keep-alive", "proxy-connection", "transfer-encoding", "te", "trailer", "upgrade"];

function tanpaHop(headers) {
  const h = { ...headers };
  for (const k of HOP) delete h[k];
  return h;
}

// Gerbang: menerima HTTPS dan meneruskannya sebagai HTTP ke 127.0.0.1 —
// /api/ dan /actuator/ ke backend (robot, Studio, Open Assistant), sisanya ke
// dasbor. Dasbor sendiri meneruskan /api yang dipanggil peramban ke backend.
function gerbangProses() {
  const h = bacaEnv(HTTPS_ENV);
  const portBe = Number(bacaEnv(BACKEND_ENV).SERVER_PORT || 8080);
  const portDa = Number(bacaEnv(DASBOR_ENV).PORT || 3000);
  const agen = new http.Agent({ keepAlive: true, maxSockets: 256 });

  const server = https.createServer({ pfx: fs.readFileSync(h.HTTPS_PFX), passphrase: h.HTTPS_PFX_PASSWORD, minVersion: "TLSv1.2" },
    (req, res) => {
      const keBackend = /^\/(api|actuator)(\/|\?|$)/.test(req.url);
      const port = keBackend ? portBe : portDa;
      const asal = (req.socket.remoteAddress || "").replace(/^::ffff:/, "");
      const headers = tanpaHop(req.headers);
      headers["x-forwarded-for"] = headers["x-forwarded-for"] ? headers["x-forwarded-for"] + ", " + asal : asal;
      headers["x-forwarded-proto"] = "https";
      headers["x-forwarded-host"] = req.headers.host || "";

      const naik = http.request({ host: "127.0.0.1", port, method: req.method, path: req.url, headers, agent: agen }, (r) => {
        res.writeHead(r.statusCode, tanpaHop(r.headers));
        r.pipe(res);
      });
      naik.on("error", (e) => {
        console.log(`${new Date().toISOString()} 502 ${req.method} ${req.url} → ${keBackend ? "backend" : "dasbor"}: ${e.code || e.message}`);
        if (!res.headersSent) res.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
        res.end(`OpenOrchestrator: ${keBackend ? "backend" : "dasbor"} tidak menjawab (${e.code || e.message}).`);
      });
      res.on("close", () => { if (!res.writableFinished) naik.destroy(); });
      req.pipe(naik);
    });
  // Unggahan paket bisa lama; tidak ada batas waktu permintaan.
  server.requestTimeout = 0;
  server.headersTimeout = 60_000;
  server.keepAliveTimeout = 65_000;
  server.on("tlsClientError", () => {});
  server.listen(Number(h.HTTPS_PORT || 8443), () => {
    console.log(`${new Date().toISOString()} gerbang HTTPS di port ${h.HTTPS_PORT} → backend 127.0.0.1:${portBe}, dasbor 127.0.0.1:${portDa}`);
  });
}

// ---------------------------------------------------------------- periksa

function ambil(url, opsiTls) {
  return new Promise((selesai) => {
    const req = (opsiTls ? https : http).get(url, { timeout: 5000, ...opsiTls }, (res) => {
      let isi = "";
      res.on("data", (b) => (isi += b));
      res.on("end", () => selesai({ status: res.statusCode, isi }));
    });
    req.on("timeout", () => req.destroy(new Error("waktu habis")));
    req.on("error", (e) => selesai({ status: 0, isi: e.code || e.message || "tidak terhubung" }));
  });
}

async function periksa({ tunggu = 0 } = {}) {
  const be = bacaEnv(BACKEND_ENV);
  const da = bacaEnv(DASBOR_ENV);
  const urlBe = `http://127.0.0.1:${be.SERVER_PORT || "8080"}/actuator/health`;
  const urlDa = `http://127.0.0.1:${da.PORT || "3000"}/login`;
  const h = adaHttps() ? bacaEnv(HTTPS_ENV) : null;
  // Lewat gerbang di 127.0.0.1 dengan nama sertifikatnya. Hanya memeriksa
  // bahwa gerbang meneruskan; kepercayaan pada sertifikat diperiksa di PC
  // pengguna (Node tidak membaca penyimpanan sertifikat Windows).
  const namaGb = h && (h.HTTPS_NAMA || "").split(",")[0];
  const urlGb = h && `https://127.0.0.1:${h.HTTPS_PORT || "8443"}/api/health`;
  // SNI tidak boleh berupa IP; untuk sertifikat ber-IP nama server tidak dikirim.
  const tlsGb = h && { rejectUnauthorized: false, ...(namaGb && !net.isIP(namaGb) ? { servername: namaGb } : {}) };
  const sampai = Date.now() + tunggu * 1000;
  let a, b, c;
  const semuaBaik = () => a.status === 200 && a.isi.includes('"UP"') && b.status === 200 && (!h || c.status === 200);
  for (;;) {
    a = await ambil(urlBe);
    b = await ambil(urlDa);
    c = h ? await ambil(urlGb, tlsGb) : null;
    if (semuaBaik() || Date.now() > sampai) break;
    await new Promise((r) => setTimeout(r, 5000));
  }
  info(`  backend  ${a.status === 200 && a.isi.includes('"UP"') ? "UP" : "TIDAK SIAP (" + (a.status || a.isi) + ")"}  ${urlBe}`
    + `  ${berjalan("backend") ? "" : "[penjaga tidak berjalan]"}`);
  info(`  dasbor   ${b.status === 200 ? "OK" : "TIDAK SIAP (" + (b.status || b.isi) + ")"}  ${urlDa}`
    + `  ${berjalan("dasbor") ? "" : "[penjaga tidak berjalan]"}`);
  if (h) {
    info(`  gerbang  ${c.status === 200 ? "OK" : "TIDAK SIAP (" + (c.status || c.isi) + ")"}  ${urlHttps(namaGb, h.HTTPS_PORT || "8443")}`
      + `  ${berjalan("gerbang") ? "" : "[penjaga tidak berjalan]"}`);
    // Sertifikat yang SEDANG disajikan — bukan berkas yang kebetulan ada di
    // disk — supaya bisa dicocokkan dengan sidik jari yang dilihat robot.
    try {
      const { cert, rantai } = await bacaSertifikatDari(Number(h.HTTPS_PORT || 8443), namaGb);
      const sisa = Math.floor((new Date(cert.validTo) - Date.now()) / 86_400_000);
      info(`  sertifikat SHA-256 ${cert.fingerprint256}`);
      info(`             untuk ${[...alamatSertifikat(cert).dns, ...alamatSertifikat(cert).ip].join(", ") || "-"}; `
        + `penerbit ${namaUmum(cert.issuer)}; rantai ${rantai.length}; berlaku sampai ${cert.validTo}`
        + (sisa < 30 ? `  ← HABIS DALAM ${sisa} HARI` : ""));
    } catch (e) {
      info("  sertifikat tidak terbaca: " + (e.code || e.message));
    }
  }
  const baik = semuaBaik();
  if (!baik) info("  lihat log\\backend.log, log\\dasbor.log" + (h ? ", log\\gerbang.log" : ""));
  return baik;
}

// ---------------------------------------------------------------- perintah

function opsi(args, nama) {
  const i = args.indexOf(nama);
  return i >= 0 ? args[i + 1] : undefined;
}

async function utama() {
  const [perintah, ...args] = process.argv.slice(2);
  switch (perintah) {
    case "pasang": {
      harusAdmin();
      info("\n[1/5] Prasyarat");
      periksaPrasyarat();
      info("\n[2/5] Setelan");
      siapkanEnv();
      kunciFolderData();
      info("\n[3/5] Basis data");
      buatBasisData(opsi(args, "--superuser") || "postgres");
      if (opsi(args, "--dump")) pulihkan(opsi(args, "--dump"));
      info("\n[4/5] Firewall dan Task Scheduler");
      firewall();
      daftarkanTugas();
      info("\n[5/5] Menyalakan (menyala pertama, Flyway membuat tabel — bisa 1–2 menit)");
      hentikan();
      mulai();
      const baik = await periksa({ tunggu: 300 });
      if (!baik) process.exit(1);
      const be = bacaEnv(BACKEND_ENV);
      const dasbor = be.CORS_ORIGINS.split(",")[0].trim();
      info("\nSelesai. Dasbor: " + dasbor + "  ·  API untuk robot/Studio/Open Assistant: "
        + (adaHttps() ? dasbor : "http://" + os.hostname().toLowerCase() + ":" + (be.SERVER_PORT || "8080")));
      return;
    }
    case "setelan":
      siapkanEnv();
      return;
    case "mulai":
      mulai();
      await periksa({ tunggu: 300 });
      return;
    case "hentikan":
      hentikan();
      return;
    case "periksa":
      process.exit((await periksa()) ? 0 : 1);
      return;
    case "pulihkan":
      pulihkan(args[0]);
      return;
    case "https":
      await pasangHttps(args);
      return;
    case "jalan":
      jalan(args[0]);
      return;
    case "gerbang-proses":
      gerbangProses();
      return;
    default:
      info("Pemakaian: oo pasang [--dump <berkas.dump>] [--superuser <nama>] | setelan | mulai | hentikan | periksa"
        + " | pulihkan <berkas.dump> | https <berkas.pfx> [--port 8443] [--nama <host>] | https --lepas"
        + " | jalan backend|dasbor|gerbang");
      process.exit(perintah ? 1 : 0);
  }
}

utama().catch((e) => gagal(e.stack || String(e)));
