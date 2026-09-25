"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, Globe, Search } from "lucide-react";
import { ForgeHubApi, type SearchHit } from "@/lib/api";
import { BAHASA, useT, type Bahasa } from "@/lib/i18n";
import { cn } from "@/lib/utils";
import { dateTimeOf } from "@/lib/utils";
import { ProfileMenu } from "@/components/ProfileMenu";
import { Badge } from "@/components/ui/primitives";

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
  const { t, tp, bahasa, setBahasa } = useT();
  const router = useRouter();
  const klien = useQueryClient();

  const [kata, setKata] = useState("");
  const [tunda, setTunda] = useState("");

  // SATU keadaan untuk ketiga menu, bukan satu boolean per menu: dengan
  // boolean terpisah, setiap menu baru harus ingat menutup semua menu lain,
  // dan menu yang lupa melakukannya tertinggal terbuka di bawah yang baru.
  const [menu, setMenu] = useState<"bahasa" | "peringatan" | "profil" | null>(null);
  const alih = (m: "bahasa" | "peringatan" | "profil") => setMenu((kini) => (kini === m ? null : m));

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

  // Klik di luar dan Esc menutup menu. Menu yang hanya bisa ditutup dengan
  // mengklik tombolnya lagi akan tertinggal terbuka di belakang halaman.
  useEffect(() => {
    function padaKlik(e: MouseEvent) {
      if (!kotak.current?.contains(e.target as Node)) setMenu(null);
    }

    function padaTombol(e: KeyboardEvent) {
      if (e.key === "Escape") setMenu(null);
    }

    document.addEventListener("mousedown", padaKlik);
    document.addEventListener("keydown", padaTombol);
    return () => {
      document.removeEventListener("mousedown", padaKlik);
      document.removeEventListener("keydown", padaTombol);
    };
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

  return (
    <header ref={kotak} className="flex h-16 shrink-0 items-center gap-4 border-b border-line bg-card px-6">
      <div className="relative max-w-md flex-1">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
        <input
          type="search"
          value={kata}
          onChange={(e) => setKata(e.target.value)}
          placeholder={t("Cari proses, robot, antrean...")}
          aria-label={t("Cari")}
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
                  {/* Jenisnya (Proses, Robot, ...) dari server berbahasa Indonesia,
                      dan kebetulan sama dengan label menu — jadi kamus yang sama
                      menerjemahkannya. */}
                  <span className="w-20 shrink-0 text-xs font-medium text-muted">{t(h.kind)}</span>
                  <span className="truncate font-medium text-ink">{h.label}</span>
                  <span className="ml-auto truncate text-xs text-muted">{tp(h.detail)}</span>
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

      {/* ml-auto mendorong seluruh kelompok ini ke pojok kanan, seberapa pun
          lebar layarnya — tanpa itu ia menempel di belakang kotak cari dan
          menyisakan ruang kosong di kanan. */}
      <div className="ml-auto flex items-center gap-1">
        {/* Bahasa */}
        <div className="relative">
          <button
            type="button"
            onClick={() => alih("bahasa")}
            className="flex items-center gap-1.5 rounded-lg px-2.5 py-2 text-sm hover:bg-slate-100"
            aria-label={t("Bahasa")}
          >
            <Globe className="h-4 w-4 text-muted" />
            <span className="uppercase">{bahasa}</span>
          </button>

          {menu === "bahasa" ? (
            <div className="absolute right-0 z-40 mt-1 w-40 rounded-lg border border-line bg-card py-1 shadow-lg">
              {BAHASA.map((b) => (
                <button
                  key={b.kode}
                  type="button"
                  onClick={() => {
                    setBahasa(b.kode as Bahasa);
                    setMenu(null);
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
            onClick={() => alih("peringatan")}
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

          {menu === "peringatan" ? (
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
                      <p className="flex items-center gap-2 text-sm font-medium text-ink">
                        <Badge value={a.severity.toUpperCase()} />
                        <span className="min-w-0">{tp(a.title)}</span>
                      </p>
                      {a.message ? <p className="mt-0.5 text-xs text-muted">{tp(a.message)}</p> : null}
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

        <div className="mx-2 h-8 w-px bg-line" aria-hidden="true" />

        <ProfileMenu
          terbuka={menu === "profil"}
          onAlih={() => alih("profil")}
          onTutup={() => setMenu(null)}
        />
      </div>
    </header>
  );
}
