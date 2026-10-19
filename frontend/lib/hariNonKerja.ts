/**
 * Tanggal kalender hari non-kerja (V16): pemeriksaan, rentang, dan pembacaan
 * daftar yang ditempel atau diunggah sebagai CSV.
 *
 * Murni — tanpa React dan tanpa impor — supaya bisa diuji langsung dengan
 * Node. Alasan penolakan ditulis sebagai teks Indonesia dan diterjemahkan
 * halamannya, sama seperti teks antarmuka lain.
 */

export type HariLibur = { date: string; note: string | null };

/** Satu baris yang ditolak; `baris` dihitung dari 1, sama seperti di Excel. */
export type BarisSalah = { baris: number; isi: string; alasan: string };

/** Rentang terpanjang dalam satu baris: setahun kabisat. */
export const RENTANG_MAKS = 366;
/** Sama dengan batas server (CalendarService.MAX_NOTE_LENGTH). */
export const CATATAN_MAKS = 200;
/** Berkas CSV terbesar yang dibaca; kalender penuh (3660 tanggal) jauh di bawahnya. */
export const CSV_MAKS_BYTE = 1024 * 1024;

const ALASAN_TANGGAL = "tanggal tidak dikenali";
const ALASAN_TANGGAL_AKHIR = "tanggal akhir tidak dikenali";
const ALASAN_KOSONG = "tanggal kosong";
const ALASAN_TERBALIK = "tanggal akhir sebelum tanggal awal";
const ALASAN_PANJANG = "rentang lebih dari 366 hari";
const ALASAN_CATATAN = "catatan lebih dari 200 karakter";

const POLA_ISO = /^\d{4}-\d{2}-\d{2}$/;
const SEHARI = 24 * 60 * 60 * 1000;

/** Tanggal yang sah menurut kalender, bukan hanya bentuknya (2026-02-30 ditolak). */
export function tanggalSah(s: string): boolean {
  if (!POLA_ISO.test(s)) return false;
  const d = new Date(`${s}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s;
}

/** Jumlah tanggal dari a sampai b, inklusif. */
function panjangRentang(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / SEHARI) + 1;
}

/** Semua tanggal dari a sampai b (inklusif); panjangnya diperiksa pemanggil. */
export function rentang(a: string, b: string): string[] {
  const hasil: string[] = [];
  const d = new Date(`${a}T00:00:00Z`);
  const akhir = new Date(`${b}T00:00:00Z`);
  while (d <= akhir && hasil.length < RENTANG_MAKS) {
    hasil.push(d.toISOString().slice(0, 10));
    d.setUTCDate(d.getUTCDate() + 1);
  }
  return hasil;
}

/** Gabungkan, urutkan; tanggal kembar memakai catatan terakhir yang terisi. */
export function gabung(lama: HariLibur[], tambahan: HariLibur[]): HariLibur[] {
  const peta = new Map<string, string | null>();
  for (const h of [...lama, ...tambahan]) peta.set(h.date, h.note || peta.get(h.date) || null);
  return [...peta.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([date, note]) => ({ date, note }));
}

/** Satu baris: tanggal awal, tanggal akhir (boleh null), catatan → tanggalnya, atau alasan ditolak. */
function bacaBaris(awal: string | null, akhir: string | null, catatan: string): HariLibur[] | string {
  if (!awal) return ALASAN_TANGGAL;
  if (akhir && akhir < awal) return ALASAN_TERBALIK;
  if (akhir && panjangRentang(awal, akhir) > RENTANG_MAKS) return ALASAN_PANJANG;

  const note = catatan.trim() || null;
  if (note && note.length > CATATAN_MAKS) return ALASAN_CATATAN;

  return (akhir ? rentang(awal, akhir) : [awal]).map((date) => ({ date, note }));
}

const potong = (s: string) => (s.length > 60 ? s.slice(0, 57) + "..." : s);

/**
 * Daftar yang ditempel, satu baris per tanggal atau rentang:
 *   2026-12-25 Natal
 *   2026-12-24..2026-12-26  Cuti bersama
 * Pemisah catatan: spasi, tab, titik koma, atau koma.
 */
export function uraiTempelan(teks: string): { hari: HariLibur[]; salah: BarisSalah[] } {
  const hari: HariLibur[] = [];
  const salah: BarisSalah[] = [];

  teks.split(/\r?\n/).forEach((mentah, i) => {
    const isi = mentah.trim();
    if (!isi) return;

    const m = /^(\d{4}-\d{2}-\d{2})(?:\s*\.\.\s*(\d{4}-\d{2}-\d{2}))?(?:[\s;,]+(.*))?$/.exec(isi);
    if (!m) return salah.push({ baris: i + 1, isi: potong(isi), alasan: ALASAN_TANGGAL });

    const akhir = m[2] ?? null;
    if (akhir && !tanggalSah(akhir)) return salah.push({ baris: i + 1, isi: potong(isi), alasan: ALASAN_TANGGAL_AKHIR });

    const hasil = bacaBaris(tanggalSah(m[1]) ? m[1] : null, akhir, m[3] ?? "");
    if (typeof hasil === "string") return salah.push({ baris: i + 1, isi: potong(isi), alasan: hasil });
    hari.push(...hasil);
  });

  return { hari, salah };
}

// ---------------------------------------------------------------------
// CSV
// ---------------------------------------------------------------------

/**
 * Isi berkas sebagai teks.
 *
 * Excel menyimpan "CSV (Comma delimited)" dalam kode halaman Windows, bukan
 * UTF-8, dan "Unicode Text" dalam UTF-16. Tanpa ini "Hari Raya Nyepi" tetap
 * terbaca, tetapi catatan dengan huruf seperti "é" menjadi tanda tanya.
 */
export function bacaTeks(byte: Uint8Array): string {
  if (byte[0] === 0xff && byte[1] === 0xfe) return new TextDecoder("utf-16le").decode(byte.subarray(2));
  if (byte[0] === 0xfe && byte[1] === 0xff) return new TextDecoder("utf-16be").decode(byte.subarray(2));

  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(byte).replace(/^﻿/, "");
  } catch {
    return new TextDecoder("windows-1252").decode(byte);
  }
}

type Rekaman = { baris: number; sel: string[] };

/** CSV menurut RFC 4180: sel bertanda kutip boleh memuat pemisah, "" dan baris baru. */
function bacaRekaman(teks: string, pemisah: string): Rekaman[] {
  const hasil: Rekaman[] = [];
  let sel: string[] = [];
  let isi = "";
  let kutip = false;
  let baris = 1;
  let mulai = 1;

  for (let i = 0; i < teks.length; i++) {
    const c = teks[i];

    if (kutip) {
      if (c === '"' && teks[i + 1] === '"') {
        isi += '"';
        i++;
      } else if (c === '"') {
        kutip = false;
      } else {
        if (c === "\n") baris++;
        isi += c;
      }
    } else if (c === '"' && isi.trim() === "") {
      kutip = true;
      isi = "";
    } else if (c === pemisah) {
      sel.push(isi);
      isi = "";
    } else if (c === "\r" || c === "\n") {
      if (c === "\r" && teks[i + 1] === "\n") i++;
      sel.push(isi);
      hasil.push({ baris: mulai, sel });
      sel = [];
      isi = "";
      baris++;
      mulai = baris;
    } else {
      isi += c;
    }
  }

  if (isi !== "" || sel.length) {
    sel.push(isi);
    hasil.push({ baris: mulai, sel });
  }

  return hasil;
}

const kosong = (r: Rekaman) => r.sel.every((s) => s.trim() === "");

/** Bentuk tanggal yang dikenali: 2026-12-25, 2026/12/25, 25/12/2026, 25-12-26, 25.12.2026. */
const POLA_TAHUN_DULU = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$/;
const POLA_TAHUN_AKHIR = /^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})$/;

type Urutan = "hari-bulan" | "bulan-hari";

function iso(y: number, m: number, d: number): string | null {
  const s = `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
  return tanggalSah(s) ? s : null;
}

function bacaTanggal(teks: string, urutan: Urutan): string | null {
  const s = teks.trim();
  let m = POLA_TAHUN_DULU.exec(s);
  if (m) return iso(Number(m[1]), Number(m[2]), Number(m[3]));

  m = POLA_TAHUN_AKHIR.exec(s);
  if (!m) return null;

  const y = m[3].length === 2 ? 2000 + Number(m[3]) : Number(m[3]);
  const [a, b] = [Number(m[1]), Number(m[2])];
  return urutan === "hari-bulan" ? iso(y, b, a) : iso(y, a, b);
}

const miripTanggal = (s: string) => POLA_TAHUN_DULU.test(s.trim()) || POLA_TAHUN_AKHIR.test(s.trim());

/**
 * Hari dulu atau bulan dulu, dari isi berkasnya sendiri.
 *
 * Excel menulis ulang tanggal menurut setelan wilayah komputernya: 25/12/2026
 * di setelan Indonesia, 12/25/2026 di setelan Amerika. Tanggal yang salah
 * satu bagiannya di atas 12 menunjukkan urutannya; tanpa petunjuk dipakai
 * hari/bulan, kebiasaan Indonesia.
 */
function tebakUrutan(sel: string[]): Urutan | "campur" {
  let hariDulu = false;
  let bulanDulu = false;

  for (const s of sel) {
    const m = POLA_TAHUN_AKHIR.exec(s.trim());
    if (!m) continue;
    if (Number(m[1]) > 12) hariDulu = true;
    if (Number(m[2]) > 12) bulanDulu = true;
  }

  if (hariDulu && bulanDulu) return "campur";
  return bulanDulu ? "bulan-hari" : "hari-bulan";
}

const normalJudul = (s: string) => s.toLowerCase().replace(/[_\-./]+/g, " ").replace(/\s+/g, " ").trim();

const JUDUL_TANGGAL = ["tanggal", "tgl", "date", "tanggal mulai", "tanggal awal", "mulai", "dari", "dari tanggal", "start", "start date", "from"];
const JUDUL_SAMPAI = ["sampai", "sampai tanggal", "hingga", "s d", "sd", "tanggal akhir", "tanggal selesai", "akhir", "selesai", "end", "end date", "to", "until"];
const JUDUL_CATATAN = ["catatan", "keterangan", "nama", "nama libur", "hari libur", "libur", "note", "notes", "name", "description", "deskripsi", "holiday"];

const kolom = (judul: string[], nama: string[]) => judul.findIndex((j) => nama.includes(normalJudul(j)));

/** Pemisah pertama yang memecah baris pertama menjadi sel tanggal atau judul kolom tanggal. */
function tebakPemisah(barisPertama: string): string {
  for (const p of [",", ";", "\t"]) {
    const sel = bacaRekaman(barisPertama, p)[0]?.sel ?? [];
    if (sel.some((s) => miripTanggal(s) || JUDUL_TANGGAL.includes(normalJudul(s)))) return p;
  }
  return ",";
}

export type HasilCsv = {
  hari: HariLibur[];
  salah: BarisSalah[];
  /** Masalah seluruh berkas (tanpa nomor baris); hari dan salah kosong. */
  masalah: string | null;
};

/**
 * CSV hari non-kerja. Kolomnya — dengan atau tanpa baris judul:
 *
 *   tanggal,sampai,catatan
 *   2026-08-17,,Hari Kemerdekaan RI
 *   2026-12-24,2026-12-26,Natal dan cuti bersama
 *
 * - Pemisah koma, titik koma (Excel bersetelan Indonesia), atau tab; baris
 *   `sep=;` dari Excel juga dikenali.
 * - Dengan baris judul, kolom dikenali dari namanya (tanggal/date,
 *   sampai/end, catatan/keterangan/nama/note) dan kolom lain diabaikan.
 *   Tanpa judul: tanggal, lalu tanggal akhir kalau sel kedua berupa tanggal
 *   atau kosong, lalu catatan.
 * - Tanggal 2026-12-25 atau 25/12/2026; lihat tebakUrutan untuk 12/25/2026.
 */
export function uraiCsv(teksMentah: string): HasilCsv {
  const kosongkan = (masalah: string): HasilCsv => ({ hari: [], salah: [], masalah });

  let teks = teksMentah.replace(/^﻿/, "");
  let petunjuk: string | null = null;

  // "sep=;" di baris pertama: petunjuk pemisah dari Excel. Barisnya diganti
  // baris kosong, supaya nomor baris lain tetap sama dengan di Excel.
  const sep = /^sep=(.)\r?\n/i.exec(teks);
  if (sep) {
    petunjuk = sep[1];
    teks = "\n" + teks.slice(sep[0].length);
  }

  const barisPertama = teks.split(/\r?\n/).find((b) => b.trim() !== "") ?? "";
  const pemisah = petunjuk ?? tebakPemisah(barisPertama);

  const rekaman = bacaRekaman(teks, pemisah).filter((r) => !kosong(r));
  if (!rekaman.length) return kosongkan("Berkas CSV tidak berisi tanggal.");

  // Baris judul: sel pertamanya bukan angka. Baris data yang tanggalnya
  // salah ketik (32/01/2026) tetap baris data, dan ditolak dengan nomornya.
  let data = rekaman;
  let ambil = (r: Rekaman): [string, string, string] => {
    const [awal = "", ...sisa] = r.sel;
    const adaAkhir = sisa.length > 0 && (sisa[0].trim() === "" ? sisa.length > 1 : miripTanggal(sisa[0]));
    return adaAkhir ? [awal, sisa[0], sisa.slice(1).join(pemisah)] : [awal, "", sisa.join(pemisah)];
  };

  if (!/^\d/.test(rekaman[0].sel[0]?.trim() ?? "")) {
    const judul = rekaman[0].sel;
    const iTanggal = kolom(judul, JUDUL_TANGGAL);
    const iSampai = kolom(judul, JUDUL_SAMPAI);
    const iCatatan = kolom(judul, JUDUL_CATATAN);
    if (iTanggal < 0) return kosongkan("Baris judul CSV tidak punya kolom tanggal (tanggal atau date).");

    data = rekaman.slice(1);
    ambil = (r) => [r.sel[iTanggal] ?? "", iSampai < 0 ? "" : r.sel[iSampai] ?? "", iCatatan < 0 ? "" : r.sel[iCatatan] ?? ""];
  }

  if (!data.length) return kosongkan("Berkas CSV tidak berisi tanggal.");

  const urutan = tebakUrutan(data.flatMap((r) => ambil(r).slice(0, 2)));
  if (urutan === "campur") {
    return kosongkan("Tanggal di berkas ini bercampur: sebagian hari/bulan, sebagian bulan/hari. Samakan bentuknya, mis. 2026-12-25.");
  }

  const hari: HariLibur[] = [];
  const salah: BarisSalah[] = [];

  for (const r of data) {
    const [awal, akhir, catatan] = ambil(r);
    const isi = potong(r.sel.join(pemisah).trim());

    if (!awal.trim()) {
      salah.push({ baris: r.baris, isi, alasan: ALASAN_KOSONG });
      continue;
    }

    const tanggalAkhir = akhir.trim() ? bacaTanggal(akhir, urutan) : null;
    if (akhir.trim() && !tanggalAkhir) {
      salah.push({ baris: r.baris, isi, alasan: ALASAN_TANGGAL_AKHIR });
      continue;
    }

    const hasil = bacaBaris(bacaTanggal(awal, urutan), tanggalAkhir, catatan);
    if (typeof hasil === "string") salah.push({ baris: r.baris, isi, alasan: hasil });
    else hari.push(...hasil);
  }

  return { hari: gabung([], hari), salah, masalah: null };
}

/**
 * Berkas contoh untuk tombol "Unduh contoh CSV": libur nasional bertanggal
 * tetap di tahun yang diminta, dan satu rentang sebagai contoh kolom sampai.
 * ASCII saja, supaya terbaca benar di Excel dengan setelan apa pun.
 */
export function contohCsv(tahun: number): string {
  const y = String(tahun);
  return [
    "tanggal,sampai,catatan",
    `${y}-01-01,,Tahun Baru Masehi`,
    `${y}-05-01,,Hari Buruh Internasional`,
    `${y}-06-01,,Hari Lahir Pancasila`,
    `${y}-08-17,,Hari Kemerdekaan RI`,
    `${y}-12-24,${y}-12-26,Natal dan cuti bersama (contoh rentang)`,
    "",
  ].join("\r\n");
}
