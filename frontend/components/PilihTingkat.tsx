"use client";

import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { useT } from "@/lib/i18n";

/** Warna titik tiap tingkat; sama dengan warna lencananya di tabel. */
export const WARNA_TINGKAT: Record<string, string> = {
  INFO: "bg-info",
  WARN: "bg-warn",
  WARNING: "bg-warn",
  ERROR: "bg-danger",
  FATAL: "bg-red-800",
};

/**
 * Penyaring tingkat: satu tombol per tingkat, dan boleh memilih LEBIH DARI
 * SATU — WARN dan ERROR sekaligus adalah pertanyaan yang paling sering
 * diajukan orang yang sedang mencari masalah.
 *
 * Tidak memilih apa pun berarti SEMUA tingkat. Memilih semuanya satu per
 * satu juga dikembalikan ke keadaan itu, supaya tombol "Semua" tidak tampak
 * mati padahal yang terlihat memang semuanya.
 */
export function PilihTingkat({
  pilihan,
  terpilih,
  onUbah,
}: {
  pilihan: string[];
  terpilih: string[];
  onUbah: (terpilih: string[]) => void;
}) {
  const { t } = useT();

  function alih(x: string) {
    const baru = terpilih.includes(x) ? terpilih.filter((y) => y !== x) : [...terpilih, x];

    // Urutannya selalu urutan pilihan, bukan urutan klik: kunci kueri yang
    // sama untuk pilihan yang sama berarti cache-nya terpakai ulang.
    const urut = pilihan.filter((y) => baru.includes(y));

    onUbah(urut.length === pilihan.length ? [] : urut);
  }

  return (
    <div role="group" aria-label={t("Tingkat")} className="flex flex-wrap items-center gap-1">
      <Keping aktif={terpilih.length === 0} onClick={() => onUbah([])}>
        {t("Semua tingkat")}
      </Keping>

      {pilihan.map((x) => (
        <Keping key={x} aktif={terpilih.includes(x)} onClick={() => alih(x)}>
          <span className={cn("h-2 w-2 rounded-full", WARNA_TINGKAT[x.toUpperCase()] ?? "bg-slate-400")} />
          {x}
        </Keping>
      ))}
    </div>
  );
}

function Keping({ aktif, onClick, children }: { aktif: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={aktif}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition",
        aktif
          ? "border-brand bg-brand text-white"
          : "border-line bg-card text-muted hover:border-slate-300 hover:text-ink",
      )}
    >
      {children}
    </button>
  );
}
