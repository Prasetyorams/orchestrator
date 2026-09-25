import { EN, PESAN_EN } from "./kamus/en";
import { JV } from "./kamus/jv";

/**
 * Bahasa antarmuka: Indonesia, Inggris, Jawa.
 *
 * KUNCINYA adalah teks Indonesianya sendiri, sama seperti di Studio dan
 * JakRunner. Teks yang belum diterjemahkan karena itu jatuh kembali ke bahasa
 * Indonesia yang BENAR — bukan menjadi kode seperti "nav.home" yang bocor ke
 * layar. Dan sumbernya tetap bisa dibaca tanpa membuka kamusnya.
 *
 * Kamus yang belum lengkap tidak pernah merusak antarmuka; ia hanya membuat
 * sebagian tetap berbahasa Indonesia.
 *
 * Berkas ini sengaja BUKAN komponen React: format tanggal di lib/utils dan
 * pesan galat di lib/api juga perlu tahu bahasanya, dan keduanya dipanggil
 * dari tempat yang tidak bisa memakai hook.
 */
export type Bahasa = "id" | "en" | "jv";

export const BAHASA: { kode: Bahasa; nama: string }[] = [
  { kode: "id", nama: "Indonesia" },
  { kode: "en", nama: "English" },
  { kode: "jv", nama: "Jawa" },
];

const KAMUS: Record<Bahasa, Record<string, string>> = { id: {}, en: EN, jv: JV };
const PESAN: Record<Bahasa, Record<string, string>> = { id: {}, en: PESAN_EN, jv: {} };

export function bahasaDikenal(b: string | null | undefined): b is Bahasa {
  return b === "id" || b === "en" || b === "jv";
}

// ---------------------------------------------------------------------
// Bahasa yang sedang dipakai
// ---------------------------------------------------------------------

let aktif: Bahasa = "id";

/** Dipanggil I18nProvider; yang lain cukup membacanya. */
export function setBahasaAktif(b: Bahasa) {
  aktif = b;
}

export function bahasaAktif(): Bahasa {
  return aktif;
}

/**
 * Lokal untuk tanggal dan angka.
 *
 * Inggris memakai en-GB, bukan en-US: jam 24 dan urutan tanggal-bulan sama
 * dengan yang dipakai orang di sini sehari-hari. Jawa memakai format
 * Indonesia — data lokal "jv" tidak tersedia di semua peramban, dan yang
 * tidak tersedia diam-diam jatuh ke format bawaan peramban.
 */
export function lokalTanggal(): string {
  return aktif === "en" ? "en-GB" : "id-ID";
}

// ---------------------------------------------------------------------
// Terjemahan
// ---------------------------------------------------------------------

/** Isi {0}, {1}, ... dengan nilainya; tempat yang tidak punya nilai dibiarkan. */
function isi(teks: string, nilai: (string | number)[]): string {
  if (nilai.length === 0) return teks;
  return teks.replace(/\{(\d+)\}/g, (asli, i) => {
    const v = nilai[Number(i)];
    return v === undefined ? asli : String(v);
  });
}

/**
 * Teks antarmuka.
 *
 * Kunci boleh membawa PENANDA KONTEKS sesudah "|": "Robot|satu" adalah kolom
 * yang berisi SATU robot, sedangkan "Robot" adalah nama menunya. Bahasa
 * Indonesia memakai kata yang sama untuk keduanya, bahasa Inggris tidak
 * ("Robot" dan "Robots"). Penandanya tidak pernah tampil: tanpa terjemahan,
 * yang tampil adalah bagian sebelum "|".
 */
export function terjemahkan(bahasa: Bahasa, teks: string, nilai: (string | number)[] = []): string {
  return isi(KAMUS[bahasa][teks] ?? tanpaPenanda(teks), nilai);
}

function tanpaPenanda(teks: string): string {
  const i = teks.indexOf("|");
  return i < 0 ? teks : teks.slice(0, i);
}

type Pola = { regex: RegExp; hasil: string; nomor: number[]; panjang: number };

const polaPerBahasa: Partial<Record<Bahasa, Pola[]>> = {};

function lolos(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Pola dari kunci yang memuat {0}, {1}, ...
 *
 * Diurutkan dari yang teks tetapnya paling panjang: pola yang lebih rinci
 * harus menang atas pola yang lebih umum, bukan kalah hanya karena letaknya
 * di kamus lebih belakang.
 */
function polaUntuk(bahasa: Bahasa): Pola[] {
  const ada = polaPerBahasa[bahasa];
  if (ada) return ada;

  const daftar = Object.entries(PESAN[bahasa])
    .filter(([kunci]) => /\{\d+\}/.test(kunci))
    .map(([kunci, hasil]) => {
      const potongan = kunci.split(/\{\d+\}/);
      return {
        regex: new RegExp("^" + potongan.map(lolos).join("(.*?)") + "$", "s"),
        hasil,
        nomor: [...kunci.matchAll(/\{(\d+)\}/g)].map((m) => Number(m[1])),
        panjang: potongan.join("").length,
      };
    })
    .sort((a, b) => b.panjang - a.panjang);

  polaPerBahasa[bahasa] = daftar;
  return daftar;
}

const ingatan = new Map<string, string>();

/**
 * Teks dari SERVER: pesan galat, peringatan, keterangan pekerjaan, catatan.
 *
 * Server menulis dalam bahasa Indonesia dan menyimpannya begitu — peringatan
 * kemarin sudah ada di basis data, jadi terjemahannya harus terjadi saat
 * DITAMPILKAN, bukan saat dibuat. Teks dengan nama di dalamnya dicocokkan
 * dengan pola ("Proses '{0}' tidak ada."), dan nilai yang tertangkap ikut
 * diterjemahkan kalau nilai itu sendiri teks yang dikenal ("tanpa keterangan").
 *
 * Hasilnya diingat: halaman Catatan menampilkan ratusan baris dan
 * menyegarkannya tiap tiga detik, dan sebagian besar barisnya berulang.
 */
export function terjemahkanPesan(bahasa: Bahasa, teks: string | null | undefined): string {
  if (!teks) return teks ?? "";
  if (bahasa === "id") return teks;

  const langsung = PESAN[bahasa][teks] ?? KAMUS[bahasa][teks];
  if (langsung !== undefined) return langsung;

  const kunci = bahasa + "\u0000" + teks;
  const diingat = ingatan.get(kunci);
  if (diingat !== undefined) return diingat;

  let hasil = teks;

  for (const p of polaUntuk(bahasa)) {
    const m = p.regex.exec(teks);
    if (!m) continue;

    const nilai: string[] = [];
    p.nomor.forEach((n, i) => {
      const tangkap = m[i + 1] ?? "";
      nilai[n] = PESAN[bahasa][tangkap] ?? tangkap;
    });

    hasil = isi(p.hasil, nilai);
    break;
  }

  // Dibatasi supaya halaman yang terbuka berhari-hari tidak menimbun
  // setiap baris catatan yang pernah lewat.
  if (ingatan.size > 5000) ingatan.clear();
  ingatan.set(kunci, hasil);

  return hasil;
}
