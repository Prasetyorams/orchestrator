"use client";

import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import { MoreVertical, type LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Menu tiga titik di ujung baris: daftar aksi untuk SATU baris, dengan ikon,
 * pemisah antarkelompok, dan aksi yang tidak berlaku tetap tampil tapi mati.
 *
 * Aksi yang mati tidak disembunyikan: menu yang berganti isi dari baris ke
 * baris membuat orang mencari-cari "Hentikan" yang tadi ada. Alasannya
 * ditampilkan saat kursor diam di atasnya, dan tetap bisa difokus lewat papan
 * ketik — itu cara WAI-ARIA untuk butir menu yang nonaktif.
 *
 * Menu dipasang di <body> lewat portal, bukan di dalam sel tabel: pembungkus
 * tabel menggulir ke samping (overflow-x-auto), dan menu di dalamnya akan
 * terpotong di tepi tabel.
 */

export type ItemMenu =
  | {
      label: string;
      ikon: LucideIcon;
      onPilih: () => void;
      /** Aksi ini tidak berlaku untuk baris ini. */
      nonaktif?: boolean;
      /** Petunjuk saat kursor diam di atasnya — terutama: mengapa nonaktif. */
      petunjuk?: string;
      /** Aksi yang tidak bisa dibatalkan: tulisannya merah. */
      bahaya?: boolean;
    }
  | { pemisah: true };

const LEBAR_MENU = 272;
const JARAK = 4;
const TEPI_LAYAR = 8;

export function MenuAksi({ label, item }: { label: string; item: ItemMenu[] }) {
  const [buka, setBuka] = useState(false);
  const [posisi, setPosisi] = useState<{ top: number; left: number } | null>(null);
  const pemicu = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const idMenu = useId();

  const aksi = item.filter((i): i is Extract<ItemMenu, { label: string }> => !("pemisah" in i));

  const tutup = useCallback((kembalikanFokus: boolean) => {
    setBuka(false);
    setPosisi(null);
    if (kembalikanFokus) pemicu.current?.focus();
  }, []);

  // Letak dihitung SESUDAH menunya tergambar (masih tak terlihat), karena
  // tingginya baru diketahui saat itu: menu di baris paling bawah dibuka ke
  // atas, bukan terpotong tepi jendela.
  useLayoutEffect(() => {
    if (!buka || !pemicu.current || !menu.current) return;

    const kotak = pemicu.current.getBoundingClientRect();
    const tinggi = menu.current.offsetHeight;

    const left = Math.max(TEPI_LAYAR, Math.min(kotak.right - LEBAR_MENU, window.innerWidth - LEBAR_MENU - TEPI_LAYAR));
    const muatDiBawah = kotak.bottom + JARAK + tinggi <= window.innerHeight - TEPI_LAYAR;
    const top = muatDiBawah ? kotak.bottom + JARAK : Math.max(TEPI_LAYAR, kotak.top - JARAK - tinggi);

    setPosisi({ top, left });
  }, [buka]);

  // Fokus ke butir pertama begitu menunya terletak.
  useEffect(() => {
    if (buka && posisi) menu.current?.querySelector<HTMLElement>("[role='menuitem']")?.focus();
  }, [buka, posisi]);

  // Klik di luar, gulir, dan ubah ukuran jendela menutup menu: menu yang
  // tertinggal di tempatnya sementara barisnya bergeser menunjuk baris lain.
  useEffect(() => {
    if (!buka) return;

    function klik(e: MouseEvent) {
      const sasaran = e.target as Node;
      if (menu.current?.contains(sasaran) || pemicu.current?.contains(sasaran)) return;
      tutup(false);
    }

    function gulir(e: Event) {
      if (menu.current?.contains(e.target as Node)) return;
      tutup(false);
    }

    const ubahUkuran = () => tutup(false);

    document.addEventListener("mousedown", klik);
    window.addEventListener("scroll", gulir, true);
    window.addEventListener("resize", ubahUkuran);

    return () => {
      document.removeEventListener("mousedown", klik);
      window.removeEventListener("scroll", gulir, true);
      window.removeEventListener("resize", ubahUkuran);
    };
  }, [buka, tutup]);

  function pindahFokus(e: KeyboardEvent<HTMLDivElement>) {
    const butir = Array.from(menu.current?.querySelectorAll<HTMLElement>("[role='menuitem']") ?? []);
    const sekarang = butir.indexOf(document.activeElement as HTMLElement);

    const tujuan = {
      ArrowDown: (sekarang + 1) % butir.length,
      ArrowUp: (sekarang - 1 + butir.length) % butir.length,
      Home: 0,
      End: butir.length - 1,
    }[e.key];

    if (tujuan !== undefined) {
      e.preventDefault();
      butir[tujuan]?.focus();
    } else if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      tutup(true);
    } else if (e.key === "Tab") {
      tutup(false);
    }
  }

  return (
    <>
      <button
        ref={pemicu}
        type="button"
        aria-label={label}
        title={label}
        aria-haspopup="menu"
        aria-expanded={buka}
        aria-controls={buka ? idMenu : undefined}
        onClick={() => (buka ? tutup(false) : setBuka(true))}
        onDoubleClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" && !buka) {
            e.preventDefault();
            setBuka(true);
          }
        }}
        className={cn(
          "inline-flex h-8 w-8 items-center justify-center rounded-lg text-muted transition hover:bg-slate-100 hover:text-ink",
          buka && "bg-slate-100 text-ink",
        )}
      >
        <MoreVertical size={16} />
      </button>

      {buka
        ? createPortal(
            <div
              ref={menu}
              id={idMenu}
              role="menu"
              aria-label={label}
              onKeyDown={pindahFokus}
              style={{
                top: posisi?.top ?? 0,
                left: posisi?.left ?? 0,
                width: LEBAR_MENU,
                visibility: posisi ? "visible" : "hidden",
              }}
              className="fixed z-50 rounded-lg border border-line bg-card py-1.5 text-sm shadow-xl"
            >
              {item.map((butir, i) =>
                "pemisah" in butir ? (
                  <div key={`p${i}`} role="separator" className="my-1.5 border-t border-line" />
                ) : (
                  <button
                    key={butir.label}
                    type="button"
                    role="menuitem"
                    tabIndex={-1}
                    aria-disabled={butir.nonaktif || undefined}
                    title={butir.petunjuk}
                    onClick={() => {
                      if (butir.nonaktif) return;
                      tutup(false);
                      butir.onPilih();
                    }}
                    className={cn(
                      "flex w-full items-center gap-3 px-3.5 py-2 text-left outline-none transition",
                      "focus-visible:bg-slate-100",
                      butir.nonaktif
                        ? "cursor-not-allowed text-muted opacity-60"
                        : cn("hover:bg-slate-100", butir.bahaya ? "text-danger" : "text-ink"),
                    )}
                  >
                    <butir.ikon size={16} className="shrink-0" aria-hidden="true" />
                    <span className="truncate">{butir.label}</span>
                  </button>
                ),
              )}
              {aksi.length === 0 ? <p className="px-3.5 py-2 text-muted">—</p> : null}
            </div>,
            document.body,
          )
        : null}
    </>
  );
}
