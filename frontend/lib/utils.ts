import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** Gabungkan kelas Tailwind, yang belakangan menang saat bentrok. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Waktu ISO menjadi jam yang enak dibaca; tanda hubung kalau kosong. */
export function timeOf(iso: string | null | undefined) {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "-" : d.toLocaleTimeString("id-ID", { hour12: false });
}

export function dateTimeOf(iso: string | null | undefined) {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "-"
    : d.toLocaleString("id-ID", { dateStyle: "medium", timeStyle: "short" });
}
