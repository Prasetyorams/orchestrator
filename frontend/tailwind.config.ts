import type { Config } from "tailwindcss";

/**
 * Warna dibaca dari variabel CSS di app/globals.css, bukan ditulis di sini:
 * tema gelap cukup mengganti nilai variabelnya di bawah kelas .dark, dan
 * setiap kelas yang sudah ada — bg-card, text-muted, ring-brand/20 — ikut
 * berganti tanpa satu pun komponen yang perlu tahu tema sedang apa.
 *
 * Variabelnya berisi tiga kanal "R G B" tanpa rgb(), supaya pengubah
 * keburaman Tailwind (/20, /70) tetap bekerja.
 */
const v = (nama: string) => `rgb(var(--${nama}) / <alpha-value>)`;

const LANGKAH = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950];

/**
 * Skala Tailwind yang ADAPTIF: slate, red, amber, blue, dan emerald dipakai
 * untuk permukaan tipis (baris disorot, lencana, kotak peringatan), dan di
 * tema gelap urutannya dibalik — slate-50 tetap "sedikit lebih terang dari
 * kartu", slate-600 tetap "teks kedua". Warna yang harus SAMA di kedua tema
 * (tirai dialog, blok kode) memakai black, white, atau neutral, yang tidak
 * diubah di sini.
 */
const skala = (nama: string) => Object.fromEntries(LANGKAH.map((l) => [l, v(`${nama}-${l}`)]));

const config: Config = {
  darkMode: "class",
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // Tema terang ala Orchestrator: bilah atas dan bilah folder putih,
        // isi abu-abu dingin, dan SATU biru untuk semua yang aktif — tab
        // terpilih, folder terpilih, angka kartu, tombol utama. Nilainya
        // untuk kedua tema ada di globals.css.
        brand: v("c-brand"),
        brandHover: v("c-brand-hover"),
        brandSoft: v("c-brand-soft"),
        brandLine: v("c-brand-line"),
        canvas: v("c-canvas"),
        card: v("c-card"),
        line: v("c-line"),
        ink: v("c-ink"),
        muted: v("c-muted"),
        ok: v("c-ok"),
        info: v("c-info"),
        warn: v("c-warn"),
        danger: v("c-danger"),

        slate: skala("slate"),
        red: skala("red"),
        amber: skala("amber"),
        blue: skala("blue"),
        emerald: skala("emerald"),
      },

      // Warna yang dipakai sebagai TEKS punya nilai sendiri. Satu biru tidak
      // bisa sekaligus menjadi latar tombol bertulisan putih dan tulisan di
      // atas kartu gelap: yang cukup gelap untuk tulisan putih terlalu redup
      // sebagai tulisan di atas #171c23, dan sebaliknya. Semuanya ≥ 4,5:1 di
      // atas kartu dan kanvas temanya masing-masing.
      textColor: {
        brand: v("t-brand"),
        ok: v("t-ok"),
        info: v("t-info"),
        warn: v("t-warn"),
        danger: v("t-danger"),
      },
    },
  },
  plugins: [],
};

export default config;
