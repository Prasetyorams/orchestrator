import type { Package } from "./api";
import { bandingkanVersi } from "./utils";

/** Satu paket: versi tertingginya, dan seluruh versinya dari yang tertinggi. */
export type RingkasanPaket = { name: string; terbaru: Package; versi: Package[] };

/**
 * API mengembalikan SATU BARIS PER VERSI, sedangkan yang dicari orang di
 * daftar paket adalah PAKETNYA — jadi dikelompokkan per nama.
 *
 * "Terbaru" berarti versi TERTINGGI, bukan yang paling akhir diterbitkan:
 * perbaikan untuk versi lama bisa saja diterbitkan belakangan, dan Studio
 * sendiri menghitung versi berikutnya dari yang tertinggi. Tanggal terbit
 * hanya menjadi penentu kalau nomor versinya sama persis.
 */
export function kelompokkanPaket(semua: Package[]): RingkasanPaket[] {
  const perNama = new Map<string, Package[]>();

  for (const p of semua) {
    const daftar = perNama.get(p.name);
    if (daftar) daftar.push(p);
    else perNama.set(p.name, [p]);
  }

  return [...perNama.entries()]
    .map(([name, versi]) => {
      const urut = [...versi].sort(
        (a, b) => bandingkanVersi(b.version, a.version) || b.publishedAt.localeCompare(a.publishedAt),
      );
      return { name, terbaru: urut[0], versi: urut };
    })
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" }));
}
