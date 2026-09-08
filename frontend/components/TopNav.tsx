"use client";

import { Bell, Search } from "lucide-react";

/**
 * Bilah atas: pencarian, lonceng notifikasi, dan profil.
 *
 * Jumlah notifikasi diambil dari angka peringatan di Dashboard, bukan
 * dihitung sendiri di sini — dua tempat yang menghitung hal yang sama akan
 * berbeda cepat atau lambat, dan yang di pojok kanan atas justru yang paling
 * dipercaya orang.
 */
export function TopNav({ alerts = 0 }: { alerts?: number }) {
  return (
    <header className="flex h-16 shrink-0 items-center gap-4 border-b border-line bg-card px-6">
      <div className="relative flex-1 max-w-md">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
        <input
          type="search"
          placeholder="Cari proses, robot, atau job..."
          className="w-full rounded-lg border border-line bg-canvas py-2 pl-9 pr-3 text-sm outline-none focus:border-info"
        />
      </div>

      <button
        type="button"
        className="relative rounded-lg p-2 hover:bg-slate-100"
        aria-label="Notifikasi"
      >
        <Bell className="h-5 w-5 text-muted" />
        {alerts > 0 ? (
          <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-semibold text-white">
            {alerts > 99 ? "99+" : alerts}
          </span>
        ) : null}
      </button>

      <div className="flex items-center gap-2.5 border-l border-line pl-4">
        <div className="flex h-8 w-8 items-center justify-center rounded-full bg-sidebar text-xs font-semibold text-white">
          FH
        </div>
        <div className="leading-tight">
          <p className="text-sm font-medium">FH_Admin</p>
          <p className="text-[11px] text-muted">Administrator</p>
        </div>
      </div>
    </header>
  );
}
