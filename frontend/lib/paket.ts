import type { Library, Package } from "./api";
import { bandingkanVersi } from "./utils";

/** Satu paket atau library: versi tertingginya, dan seluruh versinya dari yang tertinggi. */
export type Ringkasan<T> = { name: string; terbaru: T; versi: T[] };
export type RingkasanPaket = Ringkasan<Package>;
export type RingkasanLibrary = Ringkasan<Library>;

/**
 * API mengembalikan SATU BARIS PER VERSI, sedangkan yang dicari orang di
 * daftar paket adalah PAKETNYA — jadi dikelompokkan per nama.
 *
 * "Terbaru" berarti versi TERTINGGI, bukan yang paling akhir diterbitkan:
 * perbaikan untuk versi lama bisa saja diterbitkan belakangan, dan Studio
 * sendiri menghitung versi berikutnya dari yang tertinggi. Tanggal terbit
 * hanya menjadi penentu kalau nomor versinya sama persis.
 *
 * @param kunci nama kelompok: nama paket, atau Id library (huruf kecil — Id NuGet tidak peka huruf besar-kecil)
 * @param nama  nama yang ditampilkan, dari versi tertinggi
 */
export function kelompokkanVersi<T extends { version: string; publishedAt: string }>(
  semua: T[],
  kunci: (x: T) => string,
  nama: (x: T) => string,
): Ringkasan<T>[] {
  const perNama = new Map<string, T[]>();

  for (const p of semua) {
    const daftar = perNama.get(kunci(p));
    if (daftar) daftar.push(p);
    else perNama.set(kunci(p), [p]);
  }

  return [...perNama.values()]
    .map((versi) => {
      const urut = [...versi].sort(
        (a, b) => bandingkanVersi(b.version, a.version) || b.publishedAt.localeCompare(a.publishedAt),
      );
      return { name: nama(urut[0]), terbaru: urut[0], versi: urut };
    })
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" }));
}

export function kelompokkanPaket(semua: Package[]): RingkasanPaket[] {
  return kelompokkanVersi(semua, (p) => p.name, (p) => p.name);
}

export function kelompokkanLibrary(semua: Library[]): RingkasanLibrary[] {
  return kelompokkanVersi(semua, (l) => l.id.toLowerCase(), (l) => l.id);
}
