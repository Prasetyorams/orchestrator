import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";
import { lokalTanggal } from "@/lib/bahasa";

/** Gabungkan kelas Tailwind, yang belakangan menang saat bentrok. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Waktu ISO menjadi jam yang enak dibaca; tanda hubung kalau kosong.
 *
 * Formatnya mengikuti bahasa yang dipilih: "25 Sep 2026 14.30" untuk
 * Indonesia, "25 Sept 2026, 14:30" untuk Inggris.
 */
export function timeOf(iso: string | null | undefined) {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "-" : d.toLocaleTimeString(lokalTanggal(), { hour12: false });
}

export function dateTimeOf(iso: string | null | undefined) {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "-"
    : d.toLocaleString(lokalTanggal(), { dateStyle: "medium", timeStyle: "short" });
}

/**
 * Waktu yang ringkas untuk kolom sempit: jamnya saja kalau hari ini, tanggal
 * dan jam kalau tahun ini, dan lengkap kalau lebih lama dari itu. Tanggal
 * lengkapnya ada di petunjuk saat kursor diam di atasnya.
 */
export function waktuSingkat(iso: string | null | undefined) {
  if (!iso) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "-";

  const kini = new Date();
  const lokal = lokalTanggal();

  if (d.toDateString() === kini.toDateString()) {
    return d.toLocaleTimeString(lokal, { hour: "2-digit", minute: "2-digit", hour12: false });
  }

  if (d.getFullYear() === kini.getFullYear()) {
    return d.toLocaleString(lokal, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hour12: false });
  }

  return dateTimeOf(iso);
}

/** Bita menjadi satuan yang enak dibaca. */
export function ukuranBerkas(b: number) {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * Bandingkan dua nomor versi bagian demi bagian, sebagai ANGKA.
 *
 * Membandingkan teks menaruh "1.0.10" SEBELUM "1.0.9" — dan Studio menaikkan
 * angka ketiga setiap kali menerbitkan, jadi kekeliruan itu muncul tepat pada
 * penerbitan kesepuluh. Label pra-rilis mengikuti SemVer: 1.0.0-beta lebih
 * rendah daripada 1.0.0. Metadata build (+...) diabaikan.
 *
 * Hasilnya negatif kalau a lebih rendah, positif kalau lebih tinggi.
 */
export function bandingkanVersi(a: string, b: string): number {
  const [intiA, praA] = pecahVersi(a);
  const [intiB, praB] = pecahVersi(b);

  const inti = bandingkanBagian(intiA, intiB);
  if (inti !== 0) return inti;

  if (praA === null && praB !== null) return 1;
  if (praA !== null && praB === null) return -1;

  return bandingkanBagian(praA ?? "", praB ?? "");
}

/**
 * Kunci pengurutan untuk kolom tabel: urutan TEKS kunci ini sama dengan urutan
 * versinya. Bagian angka diberi nol di depan ("1.0.10" menjadi
 * "000000001.000000000.000000010"), dan rilis diberi "~" di belakang supaya
 * jatuh SESUDAH pra-rilisnya.
 */
export function kunciUrutVersi(v: string): string {
  const [inti, pra] = pecahVersi(v);
  const bagian = inti.split(".").map((x) => (/^\d+$/.test(x) ? x.padStart(9, "0") : x));

  while (bagian.length < 3) bagian.push("0".repeat(9));

  return bagian.join(".") + (pra === null ? "~" : `-${pra}`);
}

function pecahVersi(v: string): [string, string | null] {
  const tanpaBuild = v.trim().split("+")[0];
  const i = tanpaBuild.indexOf("-");

  return i < 0 ? [tanpaBuild, null] : [tanpaBuild.slice(0, i), tanpaBuild.slice(i + 1)];
}

function bandingkanBagian(a: string, b: string): number {
  const pa = a.split(".");
  const pb = b.split(".");

  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    // Bagian yang tidak ada dianggap nol: 1.0 sama dengan 1.0.0.
    const x = pa[i] ?? "0";
    const y = pb[i] ?? "0";

    const angkaX = /^\d+$/.test(x);
    const angkaY = /^\d+$/.test(y);

    if (angkaX && angkaY) {
      const selisih = Number(x) - Number(y);
      if (selisih !== 0) return selisih;
      continue;
    }

    // SemVer: bagian angka lebih rendah daripada bagian huruf (1.0.0-1 < 1.0.0-alpha).
    if (angkaX !== angkaY) return angkaX ? -1 : 1;

    const teks = x.localeCompare(y);
    if (teks !== 0) return teks;
  }

  return 0;
}
