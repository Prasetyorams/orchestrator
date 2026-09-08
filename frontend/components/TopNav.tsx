"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, Globe, LogOut, Search } from "lucide-react";
import { ForgeHubApi, setToken, type SearchHit } from "@/lib/api";
import { BAHASA, useT, type Bahasa } from "@/lib/i18n";
import { cn } from "@/lib/utils";
import { dateTimeOf } from "@/lib/utils";

/** Ke mana tiap jenis hasil pencarian mengarah. */
const HALAMAN: Record<string, string> = {
  processes: "/automation/processes",
  packages: "/automation/packages",
  robots: "/robots/machines",
  queues: "/queues",
  assets: "/assets",
  triggers: "/monitoring/triggers",
};

export function TopNav() {
  const { t, bahasa, setBahasa } = useT();
  const router = useRouter();
  const klien = useQueryClient();

  const [kata, setKata] = useState("");
  const [tunda, setTunda] = useState("");
  const [bukaPeringatan, setBukaPeringatan] = useState(false);
  const [bukaBahasa, setBukaBahasa] = useState(false);
  const kotak = useRef<HTMLDivElement>(null);

  // Pencarian ditunda 250 ms. Tanpa jeda, mengetik "processes" mengirim
  // sembilan permintaan yang delapan di antaranya sudah tidak relevan begitu
  // jawabannya tiba.
  useEffect(() => {
    const id = setTimeout(() => setTunda(kata.trim()), 250);
    return () => clearTimeout(id);
  }, [kata]);

  const hasil = useQuery({
    queryKey: ["search", tunda],
    queryFn: () => ForgeHubApi.search(tunda),
    enabled: tunda.length >= 2,
  });

  const dasbor = useQuery({
    queryKey: ["dashboard"],
    queryFn: ForgeHubApi.dashboard,
    refetchInterval: 10_000,
  });

  const peringatan = dasbor.data?.recentAlerts ?? [];
  const belumDibaca = dasbor.data?.unreadAlerts ?? 0;
  const me = useQuery({ queryKey: ["me"], queryFn: ForgeHubApi.me });

  // Klik di luar menutup kedua menu. Menu yang hanya bisa ditutup dengan
  // mengklik tombolnya lagi akan tertinggal terbuka di belakang halaman.
  useEffect(() => {
    function padaKlik(e: MouseEvent) {
      if (!kotak.current?.contains(e.target as Node)) {
        setBukaPeringatan(false);
        setBukaBahasa(false);
      }
    }

    document.addEventListener("mousedown", padaKlik);
    return () => document.removeEventListener("mousedown", padaKlik);
  }, []);

  function buka(hit: SearchHit) {
    setKata("");
    setTunda("");
    router.push(HALAMAN[hit.page] ?? "/");
  }

  async function tandaiSemua() {
    await ForgeHubApi.readAllAlerts();
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }

  function keluar() {
    setToken(null);
    window.location.href = "/login";
  }

  const inisial = (me.data?.displayName ?? "FH").slice(0, 2).toUpperCase();

  return (
    <header ref={kotak} className="flex h-16 shrink-0 items-center gap-4 border-b border-line bg-card px-6">
      <div className="relative max-w-md flex-1">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
        <input
          type="search"
          value={kata}
          onChange={(e) => setKata(e.target.value)}
          placeholder={`${t("Cari")} proses, robot, antrean...`}
          className="w-full rounded-lg border border-line bg-canvas py-2 pl-9 pr-3 text-sm outline-none focus:border-info"
        />

        {tunda.length >= 2 ? (
          <div className="absolute left-0 right-0 top-full z-40 mt-1 max-h-80 overflow-y-auto rounded-lg border border-line bg-card shadow-lg">
            {hasil.data?.length ? (
              hasil.data.map((h, i) => (
                <button
                  key={`${h.kind}-${h.label}-${i}`}
                  type="button"
                  onClick={() => buka(h)}
                  className="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-slate-50"
                >
                  <span className="w-16 shrink-0 text-xs font-medium text-muted">{h.kind}</span>
                  <span className="truncate font-medium text-ink">{h.label}</span>
                  <span className="ml-auto truncate text-xs text-muted">{h.detail}</span>
                </button>
              ))
            ) : (
              <p className="px-4 py-3 text-sm text-muted">
                {hasil.isFetching ? t("Memuat...") : t("Tidak ada yang cocok.")}
              </p>
            )}
          </div>
        ) : null}
      </div>

      {/* Bahasa */}
      <div className="relative">
        <button
          type="button"
          onClick={() => {
            setBukaBahasa(!bukaBahasa);
            setBukaPeringatan(false);
          }}
          className="flex items-center gap-1.5 rounded-lg px-2.5 py-2 text-sm hover:bg-slate-100"
          aria-label={t("Bahasa")}
        >
          <Globe className="h-4 w-4 text-muted" />
          <span className="uppercase">{bahasa}</span>
        </button>

        {bukaBahasa ? (
          <div className="absolute right-0 z-40 mt-1 w-40 rounded-lg border border-line bg-card py-1 shadow-lg">
            {BAHASA.map((b) => (
              <button
                key={b.kode}
                type="button"
                onClick={() => {
                  setBahasa(b.kode as Bahasa);
                  setBukaBahasa(false);
                }}
                className={cn(
                  "block w-full px-4 py-2 text-left text-sm hover:bg-slate-50",
                  b.kode === bahasa && "font-semibold text-ink",
                )}
              >
                {b.nama}
              </button>
            ))}
          </div>
        ) : null}
      </div>

      {/* Peringatan */}
      <div className="relative">
        <button
          type="button"
          onClick={() => {
            setBukaPeringatan(!bukaPeringatan);
            setBukaBahasa(false);
          }}
          className="relative rounded-lg p-2 hover:bg-slate-100"
          aria-label={t("Peringatan Terbaru")}
        >
          <Bell className="h-5 w-5 text-muted" />
          {belumDibaca > 0 ? (
            <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-semibold text-white">
              {belumDibaca > 99 ? "99+" : belumDibaca}
            </span>
          ) : null}
        </button>

        {bukaPeringatan ? (
          <div className="absolute right-0 z-40 mt-1 w-96 rounded-lg border border-line bg-card shadow-lg">
            <div className="flex items-center justify-between border-b border-line px-4 py-2">
              <span className="text-sm font-semibold">{t("Peringatan Terbaru")}</span>
              {belumDibaca > 0 ? (
                <button
                  type="button"
                  onClick={tandaiSemua}
                  className="text-xs text-info hover:underline"
                >
                  {t("Tandai semua dibaca")}
                </button>
              ) : null}
            </div>

            <div className="max-h-80 overflow-y-auto">
              {peringatan.length ? (
                peringatan.map((a) => (
                  <div
                    key={a.id}
                    className={cn("border-b border-line/70 px-4 py-2.5 last:border-0", !a.isRead && "bg-blue-50/40")}
                  >
                    <p className="text-sm font-medium text-ink">{a.title}</p>
                    {a.message ? <p className="mt-0.5 text-xs text-muted">{a.message}</p> : null}
                    <p className="mt-1 text-[11px] text-muted">{dateTimeOf(a.createdAt)}</p>
                  </div>
                ))
              ) : (
                <p className="px-4 py-6 text-center text-sm text-muted">{t("Belum ada data.")}</p>
              )}
            </div>
          </div>
        ) : null}
      </div>

      <div className="flex items-center gap-2.5 border-l border-line pl-4">
        <div className="flex h-8 w-8 items-center justify-center rounded-full bg-sidebar text-xs font-semibold text-white">
          {inisial}
        </div>
        <div className="leading-tight">
          <p className="text-sm font-medium">{me.data?.username ?? "..."}</p>
          <p className="text-[11px] text-muted">{me.data?.role ?? ""}</p>
        </div>

        <button
          type="button"
          onClick={keluar}
          className="ml-1 rounded-lg p-2 text-muted hover:bg-slate-100 hover:text-ink"
          aria-label={t("Keluar")}
          title={t("Keluar")}
        >
          <LogOut className="h-4 w-4" />
        </button>
      </div>
    </header>
  );
}
