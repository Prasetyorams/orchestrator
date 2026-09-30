"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { X } from "lucide-react";
import { useT } from "@/lib/i18n";

/**
 * Laci: panel isian yang keluar dari tepi kanan, setinggi layar — untuk isian
 * yang terlalu panjang bagi dialog di tengah, seperti Start Job.
 *
 * Perilakunya sama dengan Dialog: keluar lewat X, Esc, atau klik di luar;
 * fokus pindah ke isian pertama saat dibuka dan kembali ke tempat semula saat
 * ditutup; halaman di belakangnya tidak ikut bergulir. Kepala dan kaki laci
 * tetap di tempatnya — tombol Jalankan tidak ikut hilang digulir.
 */
export function Laci({
  judul,
  kepala,
  onTutup,
  children,
  aksi,
}: {
  /** Nama laci untuk pembaca layar. */
  judul: string;
  /** Isi kepala laci, mis. remah roti. Tanpa ini, judulnya yang tampil. */
  kepala?: ReactNode;
  onTutup: () => void;
  children: ReactNode;
  aksi?: ReactNode;
}) {
  const { t } = useT();
  const panel = useRef<HTMLDivElement>(null);
  const fokusSebelumnya = useRef<HTMLElement | null>(null);

  // onTutup lewat ref: halaman yang menyegarkan datanya tiap beberapa detik
  // mengirim fungsi baru di setiap render, dan efek di bawah tidak boleh
  // berjalan ulang — kursor akan melompat ke isian pertama di tengah mengetik.
  const tutup = useRef(onTutup);
  useEffect(() => {
    tutup.current = onTutup;
  });

  useEffect(() => {
    fokusSebelumnya.current = document.activeElement as HTMLElement | null;

    const isian =
      panel.current?.querySelector<HTMLElement>("input:not([disabled]), textarea, select:not([disabled])") ??
      panel.current?.querySelector<HTMLElement>("button");
    isian?.focus();

    function padaTombol(e: KeyboardEvent) {
      if (e.key === "Escape" && !e.defaultPrevented) {
        e.stopPropagation();
        tutup.current();
      }
    }

    document.addEventListener("keydown", padaTombol);

    const gulirLama = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    return () => {
      document.removeEventListener("keydown", padaTombol);
      document.body.style.overflow = gulirLama;
      fokusSebelumnya.current?.focus();
    };
  }, []);

  return (
    <div
      className="fixed inset-0 z-50 flex justify-end bg-black/40 dark:bg-black/60"
      onMouseDown={(e) => {
        // onMouseDown, bukan onClick: menyeret teks dari dalam laci lalu
        // melepasnya di luar tidak boleh menutup laci beserta isiannya.
        if (e.target === e.currentTarget) onTutup();
      }}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-label={judul}
        className="flex h-full w-full max-w-2xl flex-col border-l border-line bg-card shadow-2xl"
      >
        <div className="flex items-center justify-between gap-3 border-b border-line px-6 py-3.5">
          <div className="min-w-0 text-sm">{kepala ?? <h2 className="font-semibold text-ink">{judul}</h2>}</div>

          <button
            type="button"
            onClick={onTutup}
            aria-label={t("Tutup")}
            title={t("Tutup")}
            className="rounded-lg p-1.5 text-muted transition hover:bg-slate-100 hover:text-ink"
          >
            <X size={18} />
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">{children}</div>

        {aksi ? (
          <div className="flex items-center justify-end gap-2 border-t border-line px-6 py-3.5">{aksi}</div>
        ) : null}
      </div>
    </div>
  );
}
