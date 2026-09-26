"use client";

import Link from "next/link";
import type { ComponentType, ReactNode } from "react";
import { useT } from "@/lib/i18n";
import { cn } from "@/lib/utils";

/**
 * Sekumpulan komponen dasar bergaya shadcn/ui, ditulis langsung di sini.
 *
 * shadcn/ui bekerja dengan cara MENYALIN komponennya ke dalam proyek, bukan
 * dipasang sebagai dependensi. Jadi yang ada di berkas ini adalah bentuk
 * akhir yang sama — Card, Badge, Button, Table — tanpa menuntut langkah
 * "npx shadcn add" lebih dulu sebelum proyek ini bisa dijalankan.
 */

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <div className={cn("rounded-lg border border-line bg-card shadow-sm", className)}>
      {children}
    </div>
  );
}

export function CardHeader({
  title,
  action,
  subtitle,
}: {
  title: string;
  action?: ReactNode;
  /** Satu baris kecil di bawah judul: rentang, cakupan, atau keterangan singkat. */
  subtitle?: ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
      <div className="min-w-0">
        <h2 className="truncate text-[15px] font-semibold text-ink">{title}</h2>
        {subtitle ? <p className="mt-0.5 truncate text-xs text-muted">{subtitle}</p> : null}
      </div>
      {action}
    </div>
  );
}

export function CardBody({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn("p-5", className)}>{children}</div>;
}

/**
 * Kartu angka di baris atas dasbor: ikon di kiri, label dan angka di kanan.
 *
 * Kartu yang punya tujuan bisa diklik dan membuka halamannya — angka "3
 * antrean" hampir selalu diikuti keinginan melihat ketiga antrean itu.
 */
export function KpiCard({
  label,
  value,
  icon: Ikon,
  href,
  hint,
}: {
  label: string;
  value: number | string;
  icon: ComponentType<{ className?: string; strokeWidth?: number }>;
  href?: string;
  /** Keterangan kecil di bawah angka. */
  hint?: string;
}) {
  const isi = (
    <>
      <Ikon className="h-8 w-8 shrink-0 text-brand 2xl:h-9 2xl:w-9" strokeWidth={1.6} />
      <div className="min-w-0 flex-1 text-right">
        <p className="truncate text-sm text-ink">{label}</p>
        <p className="mt-1 text-[28px] font-semibold leading-none text-brand">{value}</p>
        {/* Baris keterangan selalu ada, kosong pun: tanpa itu angka di kartu
            berketerangan naik sebaris dan keenam angka tidak lagi sejajar. */}
        <p className="mt-1.5 h-4 truncate text-[11px] text-muted">{hint}</p>
      </div>
    </>
  );

  const kelas = "flex items-center gap-3 rounded-lg border border-line bg-card px-4 py-4 shadow-sm 2xl:gap-4 2xl:px-5";

  if (!href) return <div className={kelas}>{isi}</div>;

  return (
    <Link
      href={href}
      className={cn(
        kelas,
        "transition hover:border-brandLine hover:shadow-md",
        "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand",
      )}
    >
      {isi}
    </Link>
  );
}

/** Kotak galat merah di atas isi halaman. Kosong berarti tidak digambar. */
export function Galat({ pesan, className }: { pesan?: string | null; className?: string }) {
  if (!pesan) return null;

  return (
    <p role="alert" className={cn("rounded-lg bg-red-50 px-4 py-2 text-sm text-danger", className)}>
      {pesan}
    </p>
  );
}

const badgeTone: Record<string, string> = {
  RUNNING: "bg-blue-50 text-info ring-blue-200",
  PENDING: "bg-amber-50 text-warn ring-amber-200",
  SUCCESSFUL: "bg-emerald-50 text-ok ring-emerald-200",
  FAULTED: "bg-red-50 text-danger ring-red-200",
  STOPPING: "bg-amber-50 text-warn ring-amber-200",
  STOPPED: "bg-slate-100 text-slate-600 ring-slate-200",
  AVAILABLE: "bg-emerald-50 text-ok ring-emerald-200",
  BUSY: "bg-blue-50 text-info ring-blue-200",
  DISCONNECTED: "bg-slate-100 text-slate-600 ring-slate-200",
  OFFLINE: "bg-slate-100 text-slate-600 ring-slate-200",
  NEW: "bg-blue-50 text-info ring-blue-200",
  IN_PROGRESS: "bg-amber-50 text-warn ring-amber-200",
  FAILED: "bg-red-50 text-danger ring-red-200",
  FATAL: "bg-red-100 text-danger ring-red-300",
  ERROR: "bg-red-50 text-danger ring-red-200",
  // WARN dan WARNING satu tingkat dengan dua ejaan; JakRunner mengirim WARN.
  WARN: "bg-amber-50 text-warn ring-amber-200",
  WARNING: "bg-amber-50 text-warn ring-amber-200",
  INFO: "bg-blue-50 text-info ring-blue-200",
};

/**
 * Nama keadaan dalam bahasa antarmuka. Yang tidak ada di sini — tingkat
 * catatan, tipe aset, nama peran — tampil apa adanya: itu istilah teknis
 * yang juga tertulis begitu di Studio dan JakRunner.
 */
const LABEL_KEADAAN: Record<string, string> = {
  PENDING: "Menunggu",
  RUNNING: "Berjalan",
  SUCCESSFUL: "Berhasil",
  FAULTED: "Gagal",
  STOPPING: "Menghentikan",
  STOPPED: "Dihentikan",
  AVAILABLE: "Tersedia",
  BUSY: "Sibuk",
  DISCONNECTED: "Terputus",
  NEW: "Baru",
  IN_PROGRESS: "Diproses",
  FAILED: "Gagal",
  RETRIED: "Dicoba ulang",
};

/** Nama keadaan pekerjaan, robot, atau butir antrean — belum diterjemahkan. */
export function labelKeadaan(value: string): string {
  return LABEL_KEADAAN[value?.toUpperCase()] ?? value;
}

export function Badge({ value, label }: { value: string; label?: string }) {
  const { t } = useT();
  const tone = badgeTone[value?.toUpperCase()] ?? "bg-slate-100 text-slate-600 ring-slate-200";

  return (
    <span
      className={cn(
        "inline-flex items-center whitespace-nowrap rounded-full px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide ring-1 ring-inset",
        tone,
      )}
    >
      {label ?? t(labelKeadaan(value))}
    </span>
  );
}

export function Button({
  children,
  onClick,
  variant = "default",
  type = "button",
  disabled,
  className,
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: "default" | "primary" | "ghost";
  type?: "button" | "submit";
  disabled?: boolean;
  className?: string;
}) {
  const variantClass = {
    default: "border border-line bg-card hover:bg-slate-50",
    primary: "bg-brand text-white hover:bg-brandHover",
    ghost: "hover:bg-slate-100",
  }[variant];

  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={cn(
        "inline-flex items-center justify-center rounded-lg px-3.5 py-2 text-sm font-medium transition",
        "disabled:cursor-not-allowed disabled:opacity-50",
        variantClass,
        className,
      )}
    >
      {children}
    </button>
  );
}

/**
 * Tombol berisi ikon saja.
 *
 * Label WAJIB: dipakai sebagai title (petunjuk saat kursor diam di atasnya)
 * dan aria-label (untuk pembaca layar). Ikon tanpa label adalah tombol yang
 * artinya harus ditebak.
 *
 * Petunjuknya dipasang di pembungkus, bukan di tombolnya: tombol yang
 * dinonaktifkan tidak menerima kejadian tetikus di sebagian peramban, dan
 * justru saat nonaktif itulah orang paling butuh tahu alasannya.
 */
export function IconButton({
  label,
  onClick,
  disabled,
  tone = "default",
  children,
}: {
  label: string;
  onClick?: () => void;
  disabled?: boolean;
  tone?: "default" | "ok" | "danger";
  children: ReactNode;
}) {
  const toneClass = {
    default: "text-muted hover:bg-slate-100 hover:text-ink",
    ok: "text-ok hover:bg-emerald-50",
    danger: "text-muted hover:bg-red-50 hover:text-danger",
  }[tone];

  return (
    <span title={label} className="inline-flex">
      <button
        type="button"
        onClick={onClick}
        disabled={disabled}
        aria-label={label}
        className={cn(
          "inline-flex h-8 w-8 items-center justify-center rounded-lg transition",
          "disabled:cursor-not-allowed disabled:text-slate-300 disabled:hover:bg-transparent",
          toneClass,
        )}
      >
        {children}
      </button>
    </span>
  );
}

export function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead>
          <tr className="border-b border-line">
            {head.map((h) => (
              <th key={h} className="px-5 py-2.5 text-xs font-medium uppercase tracking-wide text-muted">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

export function Row({ children }: { children: ReactNode }) {
  return <tr className="border-b border-line/70 last:border-0 hover:bg-slate-50/60">{children}</tr>;
}

export function Cell({ children, className }: { children: ReactNode; className?: string }) {
  return <td className={cn("px-5 py-3 align-middle", className)}>{children}</td>;
}

export function EmptyState({ message }: { message: string }) {
  return <p className="px-5 py-8 text-center text-sm text-muted">{message}</p>;
}
