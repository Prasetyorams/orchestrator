"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import { useT } from "@/lib/i18n";

/**
 * Dialog dengan TIGA jalan keluar: tombol X, tombol Esc, dan klik di luar.
 *
 * Ketiganya ada karena orang mencari jalan keluar di tempat yang berbeda, dan
 * yang tidak menemukannya akan menekan tombol Kembali peramban — yang bukan
 * menutup dialog, melainkan meninggalkan halamannya.
 *
 * Fokus dipindahkan ke dalam saat dibuka dan DIKEMBALIKAN saat ditutup. Tanpa
 * itu, orang yang memakai papan ketik kehilangan tempatnya di halaman setiap
 * kali sebuah dialog lewat.
 */
export function Dialog({
  judul,
  terbuka,
  onTutup,
  children,
  aksi,
  lebar = "max-w-lg",
}: {
  judul: string;
  terbuka: boolean;
  onTutup: () => void;
  children: ReactNode;
  aksi?: ReactNode;
  lebar?: string;
}) {
  const { t } = useT();
  const panel = useRef<HTMLDivElement>(null);
  const fokusSebelumnya = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!terbuka) return;

    fokusSebelumnya.current = document.activeElement as HTMLElement | null;

    // Elemen yang bisa difokus PERTAMA, bukan panelnya: orang yang membuka
    // dialog isian biasanya langsung mengetik.
    const isian = panel.current?.querySelector<HTMLElement>(
      "input, textarea, select, button, [tabindex]:not([tabindex='-1'])",
    );
    isian?.focus();

    function padaTombol(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.stopPropagation();
        onTutup();
      }
    }

    document.addEventListener("keydown", padaTombol);

    // Halaman di belakangnya tidak boleh ikut bergulir saat dialog terbuka:
    // menggulir yang terlihat bergerak bukan yang dimaksud pembacanya.
    const gulirLama = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    return () => {
      document.removeEventListener("keydown", padaTombol);
      document.body.style.overflow = gulirLama;
      fokusSebelumnya.current?.focus();
    };
  }, [terbuka, onTutup]);

  if (!terbuka) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      onMouseDown={(e) => {
        // onMouseDown, bukan onClick: menyeret teks dari dalam dialog dan
        // melepasnya di luar akan menghitung sebagai klik di luar, lalu
        // menutup dialog beserta isian yang belum tersimpan.
        if (e.target === e.currentTarget) onTutup();
      }}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-label={judul}
        className={cn(
          "w-full rounded-xl border border-line bg-card shadow-xl",
          "max-h-[90vh] overflow-y-auto",
          lebar,
        )}
      >
        <div className="flex items-center justify-between border-b border-line px-5 py-3">
          <h2 className="text-sm font-semibold text-ink">{judul}</h2>

          <button
            type="button"
            onClick={onTutup}
            aria-label={t("Tutup")}
            title={t("Tutup")}
            className="rounded-lg p-1.5 text-muted transition hover:bg-slate-100 hover:text-ink"
          >
            <X size={16} />
          </button>
        </div>

        <div className="px-5 py-4">{children}</div>

        {aksi ? (
          <div className="flex items-center justify-end gap-2 border-t border-line px-5 py-3">
            {aksi}
          </div>
        ) : null}
      </div>
    </div>
  );
}

/** Isian berlabel, dipakai di seluruh dialog supaya bentuknya seragam. */
export function Isian({
  label,
  children,
  petunjuk,
}: {
  label: string;
  children: ReactNode;
  petunjuk?: string;
}) {
  return (
    <label className="mb-3 block">
      <span className="mb-1 block text-xs font-medium text-muted">{label}</span>
      {children}
      {petunjuk ? <span className="mt-1 block text-xs text-muted">{petunjuk}</span> : null}
    </label>
  );
}

export const kelasIsian =
  "w-full rounded-lg border border-line bg-card px-3 py-2 text-sm text-ink " +
  "outline-none transition focus:border-sidebar focus:ring-2 focus:ring-sidebar/20";
