"use client";

import { useMemo, useState, type ReactNode } from "react";
import { ChevronDown, ChevronUp, ChevronsUpDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { useT } from "@/lib/i18n";
import { Button } from "@/components/ui/primitives";

/**
 * Tabel yang bisa diurut, dihalaman, dan dipilih.
 *
 * Satu komponen untuk semua tabel di ForgeHub, bukan tabel yang ditulis ulang
 * di tiap halaman. Alasannya bukan hemat baris: perilaku pengurutan dan
 * penomoran halaman yang ditulis ulang sembilan kali akan berbeda di sembilan
 * tempat, dan bedanya baru terasa oleh orang yang memakai keduanya.
 */

export type Kolom<T> = {
  /** Judul kolom. Diterjemahkan otomatis. */
  judul: string;
  /** Isi selnya. */
  sel: (baris: T) => ReactNode;
  /**
   * Nilai untuk PENGURUTAN, kalau kolomnya bisa diurut.
   *
   * Terpisah dari `sel` dengan sengaja: yang tampil bisa berupa lencana atau
   * waktu yang sudah diformat, sedangkan yang diurut harus nilai aslinya.
   * Mengurutkan berdasarkan teks yang sudah diformat menghasilkan "10 Sep"
   * sebelum "9 Sep".
   */
  urut?: (baris: T) => string | number | null | undefined;
  /** Kelas tambahan untuk sel dan kepalanya. */
  kelas?: string;
};

type Props<T> = {
  data: T[];
  kolom: Kolom<T>[];
  kunci: (baris: T) => string;
  /** Ukuran halaman. 0 berarti tanpa penomoran halaman. */
  perHalaman?: number;
  /** Kalau diberikan, baris bisa dipilih dan yang terpilih dilaporkan ke sini. */
  onPilih?: (terpilih: T[]) => void;
  /** Klik ganda pada baris. Dipakai untuk membuka detail. */
  onBuka?: (baris: T) => void;
  kosong?: string;
  /** Bilah aksi yang muncul HANYA saat ada baris terpilih. */
  aksiTerpilih?: (terpilih: T[]) => ReactNode;
};

export function DataTable<T>({
  data,
  kolom,
  kunci,
  perHalaman = 25,
  onPilih,
  onBuka,
  kosong,
  aksiTerpilih,
}: Props<T>) {
  const { t } = useT();

  const [urutKe, setUrutKe] = useState<number | null>(null);
  const [naik, setNaik] = useState(true);
  const [halaman, setHalaman] = useState(0);
  const [terpilih, setTerpilih] = useState<Set<string>>(new Set());

  const terurut = useMemo(() => {
    if (urutKe === null) return data;

    const ambil = kolom[urutKe]?.urut;
    if (!ambil) return data;

    // Salinan, bukan urut di tempat: data datang dari cache react-query, dan
    // mengurutnya langsung mengubah isi cache untuk pemakai lain di layar yang
    // sama tanpa ada yang memintanya.
    return [...data].sort((a, b) => {
      const x = ambil(a);
      const y = ambil(b);

      // Nilai kosong SELALU di bawah, ke arah mana pun urutannya. Robot tanpa
      // denyut terakhir tidak berguna di puncak daftar.
      if (x == null && y == null) return 0;
      if (x == null) return 1;
      if (y == null) return -1;

      const hasil =
        typeof x === "number" && typeof y === "number"
          ? x - y
          : String(x).localeCompare(String(y), undefined, { numeric: true });

      return naik ? hasil : -hasil;
    });
  }, [data, kolom, urutKe, naik]);

  const jumlahHalaman = perHalaman > 0 ? Math.max(1, Math.ceil(terurut.length / perHalaman)) : 1;

  // Halaman dijepit, bukan disimpan apa adanya: menyaring daftar sampai
  // tinggal satu halaman sementara pengguna ada di halaman lima akan
  // menampilkan tabel kosong yang terlihat seperti kegagalan memuat.
  const halamanAman = Math.min(halaman, jumlahHalaman - 1);

  const terlihat =
    perHalaman > 0
      ? terurut.slice(halamanAman * perHalaman, halamanAman * perHalaman + perHalaman)
      : terurut;

  function ubahUrut(i: number) {
    if (!kolom[i].urut) return;

    if (urutKe === i) setNaik(!naik);
    else {
      setUrutKe(i);
      setNaik(true);
    }
  }

  function lapor(kunciBaru: Set<string>) {
    setTerpilih(kunciBaru);
    onPilih?.(data.filter((b) => kunciBaru.has(kunci(b))));
  }

  function alihkanSatu(k: string) {
    const baru = new Set(terpilih);
    if (baru.has(k)) baru.delete(k);
    else baru.add(k);
    lapor(baru);
  }

  // Centang di kepala hanya berlaku untuk HALAMAN INI. Memilih seluruh 2000
  // baris dengan satu klik yang tampak memilih 25 adalah cara paling mudah
  // menghapus sesuatu yang tidak dimaksud.
  const kunciHalaman = terlihat.map(kunci);
  const semuaTerpilih = kunciHalaman.length > 0 && kunciHalaman.every((k) => terpilih.has(k));

  function alihkanHalaman() {
    const baru = new Set(terpilih);
    if (semuaTerpilih) kunciHalaman.forEach((k) => baru.delete(k));
    else kunciHalaman.forEach((k) => baru.add(k));
    lapor(baru);
  }

  const barisTerpilih = data.filter((b) => terpilih.has(kunci(b)));

  return (
    <div>
      {aksiTerpilih && barisTerpilih.length > 0 ? (
        <div className="flex items-center gap-3 border-b border-line bg-slate-50 px-5 py-2.5 text-sm">
          <span className="font-medium text-ink">
            {barisTerpilih.length} {t("dipilih")}
          </span>
          {aksiTerpilih(barisTerpilih)}
          <Button variant="ghost" className="ml-auto" onClick={() => lapor(new Set())}>
            {t("Batal")}
          </Button>
        </div>
      ) : null}

      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-line">
              {onPilih ? (
                <th className="w-10 px-5 py-2.5">
                  <input
                    type="checkbox"
                    checked={semuaTerpilih}
                    onChange={alihkanHalaman}
                    aria-label="Pilih semua di halaman ini"
                    className="h-4 w-4 rounded border-line"
                  />
                </th>
              ) : null}

              {kolom.map((k, i) => (
                <th
                  key={k.judul}
                  onClick={() => ubahUrut(i)}
                  className={cn(
                    "px-5 py-2.5 text-xs font-medium uppercase tracking-wide text-muted",
                    k.urut && "cursor-pointer select-none hover:text-ink",
                    k.kelas,
                  )}
                >
                  <span className="inline-flex items-center gap-1">
                    {t(k.judul)}
                    {k.urut ? (
                      urutKe === i ? (
                        naik ? <ChevronUp size={13} /> : <ChevronDown size={13} />
                      ) : (
                        <ChevronsUpDown size={13} className="opacity-40" />
                      )
                    ) : null}
                  </span>
                </th>
              ))}
            </tr>
          </thead>

          <tbody>
            {terlihat.map((baris) => {
              const k = kunci(baris);

              return (
                <tr
                  key={k}
                  onDoubleClick={() => onBuka?.(baris)}
                  className={cn(
                    "border-b border-line/70 last:border-0 hover:bg-slate-50/60",
                    onBuka && "cursor-pointer",
                    terpilih.has(k) && "bg-blue-50/50",
                  )}
                >
                  {onPilih ? (
                    <td className="px-5 py-3">
                      <input
                        type="checkbox"
                        checked={terpilih.has(k)}
                        onChange={() => alihkanSatu(k)}
                        aria-label="Pilih baris"
                        className="h-4 w-4 rounded border-line"
                      />
                    </td>
                  ) : null}

                  {kolom.map((kol) => (
                    <td key={kol.judul} className={cn("px-5 py-3 align-middle", kol.kelas)}>
                      {kol.sel(baris)}
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {terurut.length === 0 ? (
        <p className="px-5 py-8 text-center text-sm text-muted">{t(kosong ?? "Belum ada data.")}</p>
      ) : null}

      {perHalaman > 0 && jumlahHalaman > 1 ? (
        <div className="flex items-center justify-between border-t border-line px-5 py-2.5 text-sm text-muted">
          <span>
            {terurut.length} {t("baris")}
          </span>

          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              disabled={halamanAman === 0}
              onClick={() => setHalaman(halamanAman - 1)}
            >
              ‹
            </Button>

            <span className="tabular-nums">
              {t("Halaman")} {halamanAman + 1} {t("dari")} {jumlahHalaman}
            </span>

            <Button
              variant="ghost"
              disabled={halamanAman >= jumlahHalaman - 1}
              onClick={() => setHalaman(halamanAman + 1)}
            >
              ›
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
