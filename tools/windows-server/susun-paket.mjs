// Menyusun paket OpenOrchestrator untuk server Windows tanpa internet dan tanpa
// Docker. Dijalankan di laptop yang punya internet dan toolchain-nya (JDK 25,
// Maven, Node 22); hasilnya satu folder yang tinggal disalin ke server.
//
//   node tools/windows-server/susun-paket.mjs [--tujuan <folder>] [--tanpa-build] [--uji]
//
//   --tujuan       bawaan: dist\OpenOrchestrator di repo ini
//   --tanpa-build  pakai jar dan .next yang sudah ada
//   --uji          jalankan uji backend saat membangun (bawaan dilewati)
//
// Yang TIDAK ikut: installer Java/Node/PostgreSQL (unduh sendiri, lihat
// PASANG-WINDOWS-SERVER.md), signing.key, dan .env — rahasia dibuat di server.

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const SINI = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(SINI, "..", "..");
const args = process.argv.slice(2);
const opsi = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : undefined; };
const TUJUAN = path.resolve(opsi("--tujuan") || path.join(REPO, "dist", "OpenOrchestrator"));

function gagal(pesan) {
  console.error("\nGAGAL: " + pesan);
  process.exit(1);
}

function jalankan(perintah, argumen, cwd, env = {}) {
  console.log(`\n> ${perintah} ${argumen.join(" ")}   (${path.relative(REPO, cwd) || "."})`);
  // shell: mvn dan npm adalah berkas .cmd, yang sejak Node 20.12 tidak boleh
  // dijalankan spawn tanpa shell.
  const r = spawnSync(perintah, argumen, { cwd, stdio: "inherit", shell: true, env: { ...process.env, ...env } });
  if (r.status !== 0) gagal(`${perintah} ${argumen.join(" ")} gagal (kode ${r.status}).`);
}

function salin(dari, ke) {
  if (!fs.existsSync(dari)) gagal("Tidak ada: " + dari);
  fs.cpSync(dari, ke, { recursive: true, dereference: true });
}

// .cmd dengan akhir baris LF saja kadang salah membaca label goto.
function salinCrlf(dari, ke) {
  fs.writeFileSync(ke, fs.readFileSync(dari, "utf8").replace(/\r?\n/g, "\r\n"));
}

// ------------------------------------------------------------------ bangun

if (!args.includes("--tanpa-build")) {
  const backend = path.join(REPO, "backend");
  jalankan("mvn", ["-q", "package", ...(args.includes("--uji") ? [] : ["-DskipTests"])], backend);

  const frontend = path.join(REPO, "frontend");
  if (!fs.existsSync(path.join(frontend, "node_modules"))) jalankan("npm", ["ci", "--no-audit", "--no-fund"], frontend);
  // Kosong = dasbor memanggil API lewat alamatnya sendiri (/api), diteruskan
  // ke API_INTERNAL_URL saat berjalan; paket yang sama berlaku di server mana
  // pun. Dikosongkan terang-terangan supaya nilai di lingkungan laptop tidak
  // ikut tertanam.
  jalankan("npm", ["run", "build"], frontend, { NEXT_PUBLIC_API_URL: "" });
}

// ------------------------------------------------------------------ susun

const target = path.join(REPO, "backend", "target");
const jar = fs.existsSync(target) && fs.readdirSync(target).find((f) => /^openorchestrator-.*\.jar$/.test(f));
if (!jar) gagal("Jar backend tidak ada di backend\\target. Jalankan tanpa --tanpa-build.");

const next = path.join(REPO, "frontend", ".next");
let standalone = path.join(next, "standalone");
// Next menaruh server.js di subfolder kalau menganggap akar proyeknya di atas
// frontend\ (mis. ada package-lock.json lain di atasnya).
if (!fs.existsSync(path.join(standalone, "server.js")) && fs.existsSync(path.join(standalone, "frontend", "server.js"))) {
  standalone = path.join(standalone, "frontend");
}
if (!fs.existsSync(path.join(standalone, "server.js"))) gagal("frontend\\.next\\standalone\\server.js tidak ada. Bangun ulang dasbor.");

// Hanya folder program yang dikosongkan; data\, log\, dan runtime\ (kalau
// tujuan ini kebetulan sudah berisi) dibiarkan.
for (const d of ["backend", "dasbor"]) fs.rmSync(path.join(TUJUAN, d), { recursive: true, force: true });
fs.mkdirSync(path.join(TUJUAN, "backend"), { recursive: true });

console.log("\nMenyusun " + TUJUAN);
fs.copyFileSync(path.join(target, jar), path.join(TUJUAN, "backend", jar));
salin(standalone, path.join(TUJUAN, "dasbor"));
// Dua yang tidak ikut ke standalone dan harus disalin sendiri — tanpa ini
// dasbor tampil tanpa CSS dan JavaScript.
salin(path.join(next, "static"), path.join(TUJUAN, "dasbor", ".next", "static"));
salin(path.join(REPO, "frontend", "public"), path.join(TUJUAN, "dasbor", "public"));

fs.copyFileSync(path.join(SINI, "oo.cjs"), path.join(TUJUAN, "oo.cjs"));
salinCrlf(path.join(SINI, "oo.cmd"), path.join(TUJUAN, "oo.cmd"));
fs.copyFileSync(path.join(REPO, "PASANG-WINDOWS-SERVER.md"), path.join(TUJUAN, "PASANG-WINDOWS-SERVER.md"));
fs.mkdirSync(path.join(TUJUAN, "runtime"), { recursive: true });

// Bukan rahasia pun jangan ikut terbawa dari laptop: setelan dibuat di server.
for (const p of ["dasbor/.env", "dasbor/.env.local"]) fs.rmSync(path.join(TUJUAN, p), { force: true });

function ukuran(p) {
  const s = fs.statSync(p);
  if (!s.isDirectory()) return s.size;
  return fs.readdirSync(p).reduce((n, f) => n + ukuran(path.join(p, f)), 0);
}

console.log(`
Selesai: ${TUJUAN}  (${(ukuran(TUJUAN) / 1024 / 1024).toFixed(0)} MB)
  backend\\${jar}
  dasbor\\server.js (+ .next\\static, public)
  oo.cmd, oo.cjs, PASANG-WINDOWS-SERVER.md

Salin folder ini ke server (mis. C:\\OpenOrchestrator), lalu ikuti PASANG-WINDOWS-SERVER.md.
Kalau membawa data dari laptop: salin juga signing.key ke data\\ di server — lewat
jalur aman, bukan chat atau email.`);
