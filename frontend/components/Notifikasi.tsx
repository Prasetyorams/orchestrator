"use client";

import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react";
import { CheckCircle2, X } from "lucide-react";
import { useT } from "@/lib/i18n";

/**
 * Pemberitahuan singkat di pojok kanan bawah — "Job berhasil dijalankan." —
 * yang hilang sendiri.
 *
 * Untuk KABAR BAIK saja. Kegagalan tetap tampil di tempat kejadiannya (Galat
 * di dialog atau halamannya): pesan galat yang hilang sendiri sebelum selesai
 * dibaca adalah galat yang tidak pernah dibaca.
 */

type Pesan = { id: number; teks: string };

const Konteks = createContext<(teks: string) => void>(() => {});

/** Berapa lama satu pemberitahuan terlihat. */
const LAMA_TAMPIL_MS = 4500;

/** Paling banyak sekian sekaligus; yang lama tergeser. */
const PALING_BANYAK = 3;

export function PenyediaNotifikasi({ children }: { children: ReactNode }) {
  const { t } = useT();
  const [daftar, setDaftar] = useState<Pesan[]>([]);
  const nomor = useRef(0);

  const tutup = useCallback((id: number) => setDaftar((lama) => lama.filter((p) => p.id !== id)), []);

  const tampilkan = useCallback(
    (teks: string) => {
      const id = ++nomor.current;
      setDaftar((lama) => [...lama.slice(-(PALING_BANYAK - 1)), { id, teks }]);
      window.setTimeout(() => tutup(id), LAMA_TAMPIL_MS);
    },
    [tutup],
  );

  return (
    <Konteks.Provider value={tampilkan}>
      {children}

      {/* Wilayah aria-live selalu ada, kosong pun: pembaca layar hanya
          mengumumkan perubahan di wilayah yang sudah ada sebelumnya. */}
      <div
        role="status"
        aria-live="polite"
        className="pointer-events-none fixed bottom-4 right-4 z-[60] flex max-w-[calc(100vw-2rem)] flex-col items-end gap-2"
      >
        {daftar.map((p) => (
          <div
            key={p.id}
            className="pointer-events-auto flex items-center gap-2.5 rounded-lg border border-line bg-card py-2.5 pl-3.5 pr-2 text-sm text-ink shadow-lg"
          >
            <CheckCircle2 className="h-4 w-4 shrink-0 text-ok" aria-hidden="true" />
            <span>{p.teks}</span>
            <button
              type="button"
              onClick={() => tutup(p.id)}
              aria-label={t("Tutup")}
              title={t("Tutup")}
              className="rounded p-1 text-muted transition hover:bg-slate-100 hover:text-ink"
            >
              <X size={14} />
            </button>
          </div>
        ))}
      </div>
    </Konteks.Provider>
  );
}

/** Tampilkan pemberitahuan singkat. Teksnya sudah diterjemahkan pemanggil. */
export function useNotifikasi() {
  return useContext(Konteks);
}
