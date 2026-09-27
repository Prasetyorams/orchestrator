// Membangkitkan favicon.ico dan apple-icon.png untuk frontend OpenOrchestrator.
//
//   node tools/buat-ikon.mjs
//
// Bentuknya SAMA dengan frontend/app/icon.svg — kotak biru dengan "OO" putih —
// dan digambar ulang di sini sebagai piksel, bukan diambil dari SVG-nya: tidak
// ada pengubah SVG ke PNG di Node tanpa memasang paket, dan ikon cukup
// sederhana untuk dihitung langsung. Kalau bentuk di icon.svg berubah, ubah
// juga BENTUK di bawah ini.
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

// Koordinat dalam kotak 64 x 64, persis seperti icon.svg.
const BENTUK = {
  latar: { warna: [0x2a, 0x6f, 0xdb], sudut: 14 },
  // Dua huruf O, masing-masing goresan setebal `tebal` di sepanjang tepi dalam
  // kotak [x, y, lebar, tinggi, sudut]. Kotak ini tepi LUAR goresan; di
  // icon.svg goresannya digambar di garis tengahnya (x + 3, sudut 8).
  huruf: {
    tebal: 6,
    kotak: [
      [8, 19, 22, 26, 11],
      [34, 19, 22, 26, 11],
    ],
  },
};

/** Titik (x, y) di dalam kotak bersudut bulat [x, y, lebar, tinggi, sudut]? */
function dalamKotak(x, y, [kx, ky, lebar, tinggi, sudut]) {
  if (x < kx || y < ky || x > kx + lebar || y > ky + tinggi) return false;

  // Hanya keempat sudut yang perlu diperiksa sebagai lingkaran.
  const cx = Math.min(Math.max(x, kx + sudut), kx + lebar - sudut);
  const cy = Math.min(Math.max(y, ky + sudut), ky + tinggi - sudut);

  return (x - cx) ** 2 + (y - cy) ** 2 <= sudut ** 2;
}

function dalamLatar(x, y, sudut) {
  return dalamKotak(x, y, [0, 0, 64, 64, sudut]);
}

/** Di goresan O: di dalam tepi luarnya, tetapi tidak di lubangnya. */
function dalamHuruf(x, y) {
  const { tebal, kotak } = BENTUK.huruf;

  return kotak.some(([kx, ky, lebar, tinggi, sudut]) =>
    dalamKotak(x, y, [kx, ky, lebar, tinggi, sudut]) &&
    !dalamKotak(x, y, [kx + tebal, ky + tebal, lebar - 2 * tebal, tinggi - 2 * tebal, Math.max(sudut - tebal, 0)]));
}

/**
 * Gambar ikon selebar `ukuran` piksel, RGBA.
 *
 * Tiap piksel dicuplik 8 x 8 kali lalu dirata-rata, supaya tepi huruf dan
 * sudut yang bulat halus — bukan bergerigi — di ukuran 16 dan 32 piksel.
 */
function gambar(ukuran, { sudut = BENTUK.latar.sudut } = {}) {
  const cuplik = 8;
  const piksel = Buffer.alloc(ukuran * ukuran * 4);

  for (let py = 0; py < ukuran; py++) {
    for (let px = 0; px < ukuran; px++) {
      let r = 0, g = 0, b = 0, a = 0;

      for (let sy = 0; sy < cuplik; sy++) {
        for (let sx = 0; sx < cuplik; sx++) {
          const x = ((px + (sx + 0.5) / cuplik) / ukuran) * 64;
          const y = ((py + (sy + 0.5) / cuplik) / ukuran) * 64;

          if (!dalamLatar(x, y, sudut)) continue;

          const [cr, cg, cb] = dalamHuruf(x, y) ? [255, 255, 255] : BENTUK.latar.warna;
          r += cr; g += cg; b += cb; a += 1;
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
