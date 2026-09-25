"use client";

import { cn } from "@/lib/utils";
import type { ReactNode } from "react";

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
    <div className={cn("rounded-xl border border-line bg-card shadow-sm", className)}>
      {children}
    </div>
  );
}

export function CardHeader({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
      <h2 className="text-sm font-semibold text-ink">{title}</h2>
      {action}
    </div>
  );
}

export function CardBody({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn("p-5", className)}>{children}</div>;
}

export function StatCard({
  label,
  value,
  hint,
  tone = "default",
  action,
  className,
}: {
  label: string;
  value: number | string;
  hint?: string;
  tone?: "default" | "ok" | "warn" | "danger" | "info";
  /** Kendali kecil di kanan label, misalnya pilihan rentang waktu. */
  action?: ReactNode;
  className?: string;
}) {
  const toneClass = {
    default: "text-ink",
    ok: "text-ok",
    warn: "text-warn",
    danger: "text-danger",
    info: "text-info",
  }[tone];

  return (
    <Card className={cn("p-5", className)}>
      <div className="flex items-center justify-between gap-3">
        <p className="text-xs font-medium uppercase tracking-wide text-muted">{label}</p>
        {action}
      </div>
      <p className={cn("mt-2 text-3xl font-semibold tabular-nums", toneClass)}>{value}</p>
      {hint ? <p className="mt-1 text-xs text-muted">{hint}</p> : null}
    </Card>
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
  OFFLINE: "bg-slate-100 text-slate-600 ring-slate-200",
  FATAL: "bg-red-100 text-danger ring-red-300",
  ERROR: "bg-red-50 text-danger ring-red-200",
  // WARN dan WARNING satu tingkat dengan dua ejaan; JakRunner mengirim WARN.
  WARN: "bg-amber-50 text-warn ring-amber-200",
  WARNING: "bg-amber-50 text-warn ring-amber-200",
  INFO: "bg-blue-50 text-info ring-blue-200",
};

export function Badge({ value }: { value: string }) {
  const tone = badgeTone[value?.toUpperCase()] ?? "bg-slate-100 text-slate-600 ring-slate-200";

  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset",
        tone,
      )}
    >
      {value}
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
    primary: "bg-sidebar text-white hover:bg-sidebarHover",
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
