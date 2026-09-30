// Membangkitkan favicon.ico dan apple-icon.png untuk frontend OpenOrchestrator.
//
//   node tools/buat-ikon.mjs
//
// Bentuknya SAMA dengan frontend/app/icon.svg (dan components/Logo.tsx) —
// kotak oranye bergradien dengan cincin armada putus-putus, empat sumbu,
// inti berlian, dan empat simpul robot — dan digambar ulang di sini sebagai
// piksel, bukan diambil dari SVG-nya: tidak ada pengubah SVG ke PNG di Node
// tanpa memasang paket. Kalau bentuk di icon.svg berubah, ubah juga
// konstanta di bawah ini.
//
// Kenapa masih perlu .ico dan .png padahal sudah ada .svg:
//   - peramban tetap meminta /favicon.ico sendiri (dan mencatat 404 kalau
//     tidak ada), dan sebagian tidak memakai favicon SVG;
//   - iOS memakai apple-icon.png saat halaman disematkan ke layar utama.
//
// Tanpa ketergantungan apa pun: PNG ditulis dengan zlib bawaan Node, dan ICO
// cukup membungkus PNG-PNG itu (didukung sejak Windows Vista).

import { writeFileSync } from "node:fs";
import { deflateSync } from "node:zlib";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const APP = join(dirname(fileURLToPath(import.meta.url)), "..", "frontend", "app");

// ---------------------------------------------------------------------
// Bentuk — koordinat viewBox 120 x 120 berkas aslinya, persis icon.svg
// ---------------------------------------------------------------------

/** Yang digambar: kotak latar 8..112. Tepi transparan di luarnya dipangkas, seperti viewBox icon.svg. */
const TAMPIL = { dari: 8, lebar: 104 };

const LATAR = { x: 8, y: 8, lebar: 104, tinggi: 104, sudut: 28 };

/** Gradien linear diagonal (8,8) → (112,112), diinterpolasi di sRGB seperti peramban. */
const GRADIEN = [
  [0, [0xff, 0x6b, 0x3d]],
  [0.52, [0xea, 0x43, 0x17]],
  [1, [0xb8, 0x29, 0x05]],
];

/** Cincin putus-putus: garis 14, jeda 10, ujung bulat, mulai dari arah jam 3 searah jarum jam. */
const CINCIN = { cx: 60, cy: 60, r: 34, tebal: 4.5, opasitas: 0.45, garis: 14, jeda: 10 };

/** Empat sumbu, setebal 6 dengan ujung bulat. */
const SUMBU = [
  [[60, 24], [60, 42]],
  [[60, 78], [60, 96]],
  [[24, 60], [42, 60]],
  [[78, 60], [96, 60]],
];
const TEBAL_SUMBU = 6;

/** Kotak 34 x 34 bersudut 8, diputar 45° di pusat. */
const BERLIAN = { cx: 60, cy: 60, setengah: 17, sudut: 8 };

const R_TITIK = 6.5;
const SIMPUL = [[60, 24], [96, 60], [60, 96], [24, 60]];

const PUTIH = [255, 255, 255];
const INTI = [0xea, 0x43, 0x17];

// ---------------------------------------------------------------------
// Uji titik
// ---------------------------------------------------------------------

/** Titik (x, y) di dalam kotak bersudut bulat [x, y, lebar, tinggi, sudut]? */
function dalamKotak(x, y, kx, ky, lebar, tinggi, sudut) {
  if (x < kx || y < ky || x > kx + lebar || y > ky + tinggi) return false;

  // Hanya keempat sudut yang perlu diperiksa sebagai lingkaran.
  const cx = Math.min(Math.max(x, kx + sudut), kx + lebar - sudut);
  const cy = Math.min(Math.max(y, ky + sudut), ky + tinggi - sudut);

  return (x - cx) ** 2 + (y - cy) ** 2 <= sudut ** 2;
}

function jarakKeRuas(x, y, [ax, ay], [bx, by]) {
  const dx = bx - ax;
  const dy = by - ay;
  const t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
}

/**
 * Di salah satu ruas garis putus-putus cincin?
 *
 * Ujung bulat SVG = cakram setengah tebal di kedua ujung ruas, jadi semua
 * titiknya tetap berjarak paling jauh setengah tebal dari lingkaran — itu
 * saringan pertamanya. Pola tidak habis dibagi keliling, jadi ruas terakhir
 * disusul jeda yang lebih panjang sebelum kembali ke ruas pertama.
 */
function dalamCincin(x, y) {
  const { cx, cy, r, tebal, garis, jeda } = CINCIN;
  const setengah = tebal / 2;
  const dx = x - cx;
  const dy = y - cy;

  if (Math.abs(Math.hypot(dx, dy) - r) > setengah) return false;

  let sudut = Math.atan2(dy, dx);
  if (sudut < 0) sudut += 2 * Math.PI;

  const keliling = 2 * Math.PI * r;
  const posisi = sudut * r;

  for (let mulai = 0; mulai < keliling; mulai += garis + jeda) {
    const akhir = Math.min(mulai + garis, keliling);
    if (posisi >= mulai && posisi <= akhir) return true;

    for (const ujung of [mulai, akhir]) {
      const a = ujung / r;
      if (Math.hypot(x - (cx + r * Math.cos(a)), y - (cy + r * Math.sin(a))) <= setengah) return true;
    }
  }

  return false;
}

function dalamBerlian(x, y) {
  const { cx, cy, setengah, sudut } = BERLIAN;
  const cos = Math.SQRT1_2;
  const dx = x - cx;
  const dy = y - cy;

  // Putar balik 45°, lalu uji sebagai kotak biasa di sekitar titik pusat.
  const rx = dx * cos + dy * cos;
  const ry = -dx * cos + dy * cos;

  return dalamKotak(rx, ry, -setengah, -setengah, 2 * setengah, 2 * setengah, sudut);
}

function warnaGradien(x, y) {
  const t = Math.max(0, Math.min(1, (x - 8 + (y - 8)) / 208));

  for (let i = 1; i < GRADIEN.length; i++) {
    const [t1, w1] = GRADIEN[i];
    const [t0, w0] = GRADIEN[i - 1];
    if (t <= t1) return campur(w0, w1, (t - t0) / (t1 - t0));
  }

  return GRADIEN.at(-1)[1];
}

function campur([r0, g0, b0], [r1, g1, b1], t) {
  return [r0 + (r1 - r0) * t, g0 + (g1 - g0) * t, b0 + (b1 - b0) * t];
}

/**
 * Warna satu cuplikan, atau null kalau di luar latar. Lapisannya berurutan
 * sama dengan icon.svg: latar, cincin (putih 45%), sumbu, berlian, inti,
 * simpul.
 */
function warnaCuplikan(x, y, sudutLatar) {
  if (!dalamKotak(x, y, LATAR.x, LATAR.y, LATAR.lebar, LATAR.tinggi, sudutLatar)) return null;

  let warna = warnaGradien(x, y);

  if (dalamCincin(x, y)) warna = campur(warna, PUTIH, CINCIN.opasitas);
  if (SUMBU.some(([a, b]) => jarakKeRuas(x, y, a, b) <= TEBAL_SUMBU / 2)) warna = PUTIH;
  if (dalamBerlian(x, y)) warna = PUTIH;
  if (Math.hypot(x - 60, y - 60) <= R_TITIK) warna = INTI;
  if (SIMPUL.some(([sx, sy]) => Math.hypot(x - sx, y - sy) <= R_TITIK)) warna = PUTIH;

  return warna;
}

/**
 * Gambar ikon selebar `ukuran` piksel, RGBA.
 *
 * Tiap piksel dicuplik 8 x 8 kali lalu dirata-rata, supaya tepi lingkaran,
 * garis, dan sudut yang bulat halus — bukan bergerigi — di ukuran 16 dan 32
 * piksel.
 */
function gambar(ukuran, { sudut = LATAR.sudut } = {}) {
  const cuplik = 8;
  const piksel = Buffer.alloc(ukuran * ukuran * 4);

  for (let py = 0; py < ukuran; py++) {
    for (let px = 0; px < ukuran; px++) {
      let r = 0, g = 0, b = 0, a = 0;

      for (let sy = 0; sy < cuplik; sy++) {
        for (let sx = 0; sx < cuplik; sx++) {
          const x = TAMPIL.dari + ((px + (sx + 0.5) / cuplik) / ukuran) * TAMPIL.lebar;
          const y = TAMPIL.dari + ((py + (sy + 0.5) / cuplik) / ukuran) * TAMPIL.lebar;

          const warna = warnaCuplikan(x, y, sudut);
          if (!warna) continue;

          r += warna[0]; g += warna[1]; b += warna[2]; a += 1;
        }
      }

      const i = (py * ukuran + px) * 4;
      const n = cuplik * cuplik;

      // Warnanya rata-rata dari cuplikan yang KENA saja; yang tidak kena
      // hanya mengurangi alfa. Merata-ratakan dengan hitam akan menggelapkan
      // tepi ikon di atas tab yang terang.
      piksel[i] = a ? Math.round(r / a) : 0;
      piksel[i + 1] = a ? Math.round(g / a) : 0;
      piksel[i + 2] = a ? Math.round(b / a) : 0;
      piksel[i + 3] = Math.round((a / n) * 255);
    }
  }

  return piksel;
}

// ---------------------------------------------------------------------
// PNG
// ---------------------------------------------------------------------

const TABEL_CRC = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(buf) {
  let c = 0xffffffff;
  for (const byte of buf) c = TABEL_CRC[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function potongan(jenis, isi) {
  const panjang = Buffer.alloc(4);
  panjang.writeUInt32BE(isi.length);

  const jenisIsi = Buffer.concat([Buffer.from(jenis, "ascii"), isi]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(jenisIsi));

  return Buffer.concat([panjang, jenisIsi, crc]);
}

function png(ukuran, piksel) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(ukuran, 0);
  ihdr.writeUInt32BE(ukuran, 4);
  ihdr[8] = 8; // bit per kanal
  ihdr[9] = 6; // RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  // Setiap baris diawali bita penyaring 0 (tanpa penyaring).
  const baris = Buffer.alloc(ukuran * (ukuran * 4 + 1));
  for (let y = 0; y < ukuran; y++) {
    piksel.copy(baris, y * (ukuran * 4 + 1) + 1, y * ukuran * 4, (y + 1) * ukuran * 4);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    potongan("IHDR", ihdr),
    potongan("IDAT", deflateSync(baris, { level: 9 })),
    potongan("IEND", Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------------
// ICO: kepala, satu entri per ukuran, lalu PNG-nya berurutan.
// ---------------------------------------------------------------------

function ico(daftarUkuran) {
  const gambarPng = daftarUkuran.map((u) => png(u, gambar(u)));

  const kepala = Buffer.alloc(6);
  kepala.writeUInt16LE(0, 0); // cadangan
  kepala.writeUInt16LE(1, 2); // 1 = ikon
  kepala.writeUInt16LE(daftarUkuran.length, 4);

  let letak = 6 + 16 * daftarUkuran.length;

  const entri = daftarUkuran.map((u, i) => {
    const e = Buffer.alloc(16);
    e[0] = u >= 256 ? 0 : u;
    e[1] = u >= 256 ? 0 : u;
    e[2] = 0; // tanpa palet
    e[3] = 0;
    e.writeUInt16LE(1, 4); // bidang warna
    e.writeUInt16LE(32, 6); // bit per piksel
    e.writeUInt32LE(gambarPng[i].length, 8);
    e.writeUInt32LE(letak, 12);
    letak += gambarPng[i].length;
    return e;
  });

  return Buffer.concat([kepala, ...entri, ...gambarPng]);
}

writeFileSync(join(APP, "favicon.ico"), ico([16, 32, 48]));

// iOS memberi sudut bulatnya sendiri; ikon yang sudah bulat akan tampak
// bersudut dua lapis. Jadi latarnya penuh sampai ke tepi.
writeFileSync(join(APP, "apple-icon.png"), png(180, gambar(180, { sudut: 0 })));

console.log("favicon.ico dan apple-icon.png ditulis ke", APP);
