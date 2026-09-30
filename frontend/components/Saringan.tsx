"use client";

import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { Check, ChevronDown, Search } from "lucide-react";
import { cn } from "@/lib/utils";
import { useT } from "@/lib/i18n";
import { kelasIsian } from "@/components/Dialog";

/**
 * Bahan saringan halaman tabel: tombol yang membuka panel kecil di bawahnya
 * (Munculan), pil "Label: Nilai" (PilSaringan), dan daftar pilihan dengan
 * pencarian (DaftarPilihan, DaftarCentang).
 *
 * Panelnya menempel pada tombolnya (bukan portal): baris saringan tidak
 * berada di dalam wadah yang menggulir ke samping, jadi panel tidak
 * terpotong — dan kalau terlalu dekat tepi kanan layar, ia rata kanan.
 */

export function Munculan({
  label,
  tombol,
  kelasTombol,
  lebar = "w-72",
  children,
}: {
  /** Nama panel untuk pembaca layar. */
  label: string;
  /** Isi tombol pembukanya. */
  tombol: ReactNode;
  kelasTombol: (buka: boolean) => string;
  lebar?: string;
  /** Isi panel; {@code tutup} menutupnya — dipanggil sesudah memilih. */
  children: (tutup: () => void) => ReactNode;
}) {
  const [buka, setBuka] = useState(false);
  const [rataKanan, setRataKanan] = useState(false);
  const wadah = useRef<HTMLDivElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const pemicu = useRef<HTMLButtonElement>(null);
  const idPanel = useId();

  function tutup(kembalikanFokus = true) {
    setBuka(false);
    if (kembalikanFokus) pemicu.current?.focus();
  }

  // Panel yang akan melewati tepi kanan layar dibuka rata kanan. Diukur
  // sesudah tergambar rata kiri (lihat onClick), sebelum terlihat.
  useLayoutEffect(() => {
    if (!buka || !panel.current) return;
    if (panel.current.getBoundingClientRect().right > window.innerWidth - 8) setRataKanan(true);
  }, [buka]);

  // Isian pertama di panel — kotak cari, kalau ada — langsung bisa diketik.
  useEffect(() => {
    if (!buka) return;
    panel.current?.querySelector<HTMLElement>("input, [role='option'], button")?.focus();
  }, [buka]);

  useEffect(() => {
    if (!buka) return;

    function klik(e: MouseEvent) {
      if (!wadah.current?.contains(e.target as Node)) setBuka(false);
    }

    document.addEventListener("mousedown", klik);
    return () => document.removeEventListener("mousedown", klik);
  }, [buka]);

  return (
    <div ref={wadah} className="relative">
      <button
        ref={pemicu}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={buka}
        aria-controls={buka ? idPanel : undefined}
        onClick={() => {
          setRataKanan(false);
          setBuka(!buka);
        }}
        className={kelasTombol(buka)}
      >
        {tombol}
      </button>

      {buka ? (
        <div
          ref={panel}
          id={idPanel}
          role="dialog"
          aria-label={label}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              e.stopPropagation();
              tutup();
            }
          }}
          className={cn(
            "absolute top-full z-40 mt-1.5 rounded-lg border border-line bg-card p-1.5 shadow-xl",
            rataKanan ? "right-0" : "left-0",
            lebar,
          )}
        >
          {children(() => tutup())}
        </div>
      ) : null}
    </div>
  );
}

/**
 * Pil saringan: "Label: Nilai" dengan panah. Saringan yang aktif (bukan
 * "Semua") diberi latar biru muda, supaya terlihat sekilas bahwa data sedang
 * disaring.
 */
export function PilSaringan({
  label,
  nilai,
  aktif,
  lebar,
  children,
}: {
  label: string;
  nilai: string;
  aktif: boolean;
  lebar?: string;
  children: (tutup: () => void) => ReactNode;
}) {
  return (
    <Munculan
      label={label}
      lebar={lebar}
      kelasTombol={(buka) =>
        cn(
          "inline-flex max-w-[20rem] items-center gap-1.5 rounded-lg border px-3 py-1.5 text-sm transition",
          aktif
            ? "border-brandLine bg-brandSoft text-ink"
            : "border-transparent bg-slate-50 text-ink hover:bg-slate-100",
          buka && "ring-2 ring-brand/20",
        )
      }
      tombol={
        <>
          <span className="shrink-0 font-semibold">{label}:</span>
          <span className={cn("truncate", aktif ? "font-medium text-brand" : "text-muted")}>{nilai}</span>
          <ChevronDown size={14} className="shrink-0 text-muted" aria-hidden="true" />
        </>
      }
    >
      {children}
    </Munculan>
  );
}

/** Kotak cari tampil hanya kalau pilihannya cukup banyak untuk perlu dicari. */
const BATAS_TANPA_CARI = 7;

/**
 * Satu pilihan dari daftar, dengan "Semua" di atas. Nilai kosong ("") berarti
 * Semua. Panah atas/bawah berpindah pilihan, Enter memilih.
 */
export function DaftarPilihan({
  pilihan,
  nilai,
  labelSemua,
  onPilih,
  label = (x) => x,
  kosong,
}: {
  pilihan: string[];
  nilai: string;
  labelSemua: string;
  onPilih: (nilai: string) => void;
  label?: (x: string) => string;
  /** Teks saat belum ada pilihan sama sekali. */
  kosong?: string;
}) {
  const { t } = useT();
  const [cari, setCari] = useState("");
  const daftar = useRef<HTMLDivElement>(null);

  const kata = cari.trim().toLowerCase();
  const cocok = pilihan.filter((p) => !kata || label(p).toLowerCase().includes(kata));
  const tampil = kata ? cocok : ["", ...cocok];

  return (
    <div>
      {pilihan.length > BATAS_TANPA_CARI ? (
        <KotakCari
          nilai={cari}
          onUbah={setCari}
          onTurun={() => daftar.current?.querySelector<HTMLElement>("[role='option']")?.focus()}
        />
      ) : null}

      <div
        ref={daftar}
        role="listbox"
        aria-label={labelSemua}
        onKeyDown={pindahFokus}
        className="max-h-64 overflow-y-auto"
      >
        {tampil.map((p) => (
          <Pilihan key={p || "(semua)"} terpilih={p === nilai} onPilih={() => onPilih(p)}>
            {p ? label(p) : labelSemua}
          </Pilihan>
        ))}

        {kata && cocok.length === 0 ? <p className="px-2.5 py-2 text-sm text-muted">{t("Tidak ada yang cocok.")}</p> : null}
        {!kata && pilihan.length === 0 && kosong ? <p className="px-2.5 py-2 text-xs text-muted">{kosong}</p> : null}
      </div>
    </div>
  );
}

/**
 * Beberapa pilihan sekaligus, dengan "Semua" di atas. Tidak memilih apa pun
 * berarti Semua; memilih semuanya satu per satu juga kembali ke Semua —
 * aturan yang sama dengan PilihTingkat.
 */
export function DaftarCentang({
  pilihan,
  terpilih,
  labelSemua,
  onUbah,
  label = (x) => x,
  tanda,
}: {
  pilihan: string[];
  terpilih: string[];
  labelSemua: string;
  onUbah: (terpilih: string[]) => void;
  label?: (x: string) => string;
  /** Tanda kecil di depan tiap pilihan, mis. titik warna tingkat. */
  tanda?: (x: string) => ReactNode;
}) {
  function alih(x: string) {
    const baru = terpilih.includes(x) ? terpilih.filter((y) => y !== x) : [...terpilih, x];

    // Urutan pilihan, bukan urutan klik: kunci kueri yang sama untuk pilihan
    // yang sama berarti cache-nya terpakai ulang.
    const urut = pilihan.filter((y) => baru.includes(y));
    onUbah(urut.length === pilihan.length ? [] : urut);
  }

  return (
    <div role="listbox" aria-multiselectable="true" aria-label={labelSemua} onKeyDown={pindahFokus}>
      <Pilihan terpilih={terpilih.length === 0} onPilih={() => onUbah([])}>
        {labelSemua}
      </Pilihan>
      {pilihan.map((p) => (
        <Pilihan key={p} terpilih={terpilih.includes(p)} centang onPilih={() => alih(p)}>
          {tanda ? tanda(p) : null}
          {label(p)}
        </Pilihan>
      ))}
    </div>
  );
}

/**
 * Kotak centang biasa: tiap pilihan menyala dan mati sendiri, tanpa "Semua".
 * Untuk memilih kolom — di sana mengeklik satu kolom berarti menyembunyikan
 * kolom itu, bukan menyisakan kolom itu saja seperti di DaftarCentang.
 */
export function DaftarSakelar({
  pilihan,
  terpilih,
  labelDaftar,
  onUbah,
  label = (x) => x,
}: {
  pilihan: string[];
  terpilih: string[];
  labelDaftar: string;
  onUbah: (terpilih: string[]) => void;
  label?: (x: string) => string;
}) {
  return (
    <div role="listbox" aria-multiselectable="true" aria-label={labelDaftar} onKeyDown={pindahFokus}>
      {pilihan.map((p) => (
        <Pilihan
          key={p}
          centang
          terpilih={terpilih.includes(p)}
          onPilih={() => onUbah(pilihan.filter((y) => (y === p ? !terpilih.includes(p) : terpilih.includes(y))))}
        >
          {label(p)}
        </Pilihan>
      ))}
    </div>
  );
}

export function KotakCari({
  nilai,
  onUbah,
  onTurun,
  placeholder,
}: {
  nilai: string;
  onUbah: (nilai: string) => void;
  /** Panah bawah dari kotak cari: pindah ke pilihan pertama. */
  onTurun?: () => void;
  placeholder?: string;
}) {
  const { t } = useT();

  return (
    <div className="relative mb-1.5">
      <Search size={14} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted" aria-hidden="true" />
      <input
        type="search"
        value={nilai}
        onChange={(e) => onUbah(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" && onTurun) {
            e.preventDefault();
            onTurun();
          }
        }}
        placeholder={placeholder ?? t("Cari...")}
        aria-label={placeholder ?? t("Cari...")}
        className={cn(kelasIsian, "py-1.5 pl-8 text-sm")}
      />
    </div>
  );
}

export function Pilihan({
  terpilih,
  centang = false,
  onPilih,
  children,
}: {
  terpilih: boolean;
  /** Tampil sebagai kotak centang (pilihan jamak), bukan tanda terpilih. */
  centang?: boolean;
  onPilih: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      role="option"
      aria-selected={terpilih}
      onClick={onPilih}
      className={cn(
        "flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-sm outline-none transition",
        "hover:bg-slate-100 focus-visible:bg-slate-100",
        terpilih ? "font-medium text-ink" : "text-ink",
      )}
    >
      {centang ? (
        <span
          aria-hidden="true"
          className={cn(
            "flex h-4 w-4 shrink-0 items-center justify-center rounded border",
            terpilih ? "border-brand bg-brand text-white" : "border-line bg-card",
          )}
        >
          {terpilih ? <Check size={12} strokeWidth={3} /> : null}
        </span>
      ) : (
        <Check size={14} aria-hidden="true" className={cn("shrink-0 text-brand", !terpilih && "invisible")} />
      )}
      <span className="flex min-w-0 items-center gap-1.5 truncate">{children}</span>
    </button>
  );
}

/** Panah atas/bawah, Home, End di antara pilihan sebuah daftar. */
function pindahFokus(e: KeyboardEvent<HTMLDivElement>) {
  const butir = Array.from(e.currentTarget.querySelectorAll<HTMLElement>("[role='option']"));
  const sekarang = butir.indexOf(document.activeElement as HTMLElement);

  const tujuan = {
    ArrowDown: Math.min(sekarang + 1, butir.length - 1),
    ArrowUp: Math.max(sekarang - 1, 0),
    Home: 0,
    End: butir.length - 1,
  }[e.key];

  if (tujuan !== undefined && butir.length) {
    e.preventDefault();
    butir[tujuan]?.focus();
  }
}
