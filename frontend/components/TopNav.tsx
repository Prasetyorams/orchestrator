"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, Check, Globe, Menu, Monitor, Moon, Search, Sun } from "lucide-react";
import { OpenOrchestratorApi, type SearchHit } from "@/lib/api";
import { BAHASA, useT, type Bahasa } from "@/lib/i18n";
import { useFolder } from "@/lib/folder";
import { useIzin } from "@/lib/izin";
import { useTema, type Tema } from "@/lib/tema";
import { HALAMAN_CARI } from "@/lib/navigasi";
import { cn, dateTimeOf } from "@/lib/utils";
import { ProfileMenu } from "@/components/ProfileMenu";
import { Badge } from "@/components/ui/primitives";

const PILIHAN_TEMA: { kode: Tema; label: string; ikon: typeof Sun }[] = [
  { kode: "terang", label: "Terang", ikon: Sun },
  { kode: "gelap", label: "Gelap", ikon: Moon },
  { kode: "sistem", label: "Ikuti sistem", ikon: Monitor },
];

/**
 * Bilah paling atas: nama produk, pencarian menyeluruh, bahasa, tema,
 * peringatan, dan profil.
 *
 * Lonceng membaca ringkasan peringatan sendiri, BUKAN isi dasbor: dasbor kini
 * milik satu folder, sedangkan lonceng harus bekerja di halaman mana pun —
 * termasuk halaman Tenant yang tidak punya folder sama sekali.
 */
export function TopNav({ onMenu }: { onMenu: () => void }) {
  const { t, tp, bahasa, setBahasa } = useT();
  const router = useRouter();
  const klien = useQueryClient();
  const folders = useFolder();
  const { tema, setTema } = useTema();
  const { boleh } = useIzin();

  const [kata, setKata] = useState("");
  const [tunda, setTunda] = useState("");

  // SATU keadaan untuk semua menu, bukan satu boolean per menu: dengan
  // boolean terpisah, setiap menu baru harus ingat menutup semua menu lain,
  // dan menu yang lupa melakukannya tertinggal terbuka di bawah yang baru.
  type Menu = "bahasa" | "tema" | "peringatan" | "profil";
  const [menu, setMenu] = useState<Menu | null>(null);
  const alih = (m: Menu) => setMenu((kini) => (kini === m ? null : m));

  const IkonTema = PILIHAN_TEMA.find((p) => p.kode === tema)?.ikon ?? Monitor;
  const lihatPeringatan = boleh("alerts.read");

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
    queryFn: () => OpenOrchestratorApi.search(tunda),
    enabled: tunda.length >= 2,
  });

  // Peran tanpa izin membaca peringatan tidak diberi lonceng sama sekali —
  // dan tidak ditanyai tiap sepuluh detik untuk dijawab 403.
  const ringkasan = useQuery({
    queryKey: ["alerts", "ringkasan"],
    queryFn: OpenOrchestratorApi.alertSummary,
    refetchInterval: 10_000,
    enabled: lihatPeringatan,
  });

  const peringatan = ringkasan.data?.recent ?? [];
  const belumDibaca = ringkasan.data?.unread ?? 0;

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

  /**
   * Hasil yang tinggal di folder membuka foldernya LEBIH DULU: proses
   * "Tagihan" di folder Keuangan tidak akan terlihat di halaman Proses
   * selama yang terbuka masih folder Shared.
   */
  function buka(hit: SearchHit) {
    setKata("");
    setTunda("");
    if (hit.folderId) folders.pilih(hit.folderId);
    router.push(HALAMAN_CARI[hit.page] ?? "/");
  }

  async function tandaiSemua() {
    await OpenOrchestratorApi.readAllAlerts();
    klien.invalidateQueries({ queryKey: ["alerts"] });
  }

  return (
    <header ref={kotak} className="flex h-14 shrink-0 items-center gap-3 border-b border-line bg-card px-3 sm:px-4">
      <button
        type="button"
        onClick={onMenu}
        aria-label={t("Folder")}
        title={t("Folder")}
        className="rounded-md p-2 text-muted hover:bg-slate-100 hover:text-ink lg:hidden"
      >
        <Menu className="h-5 w-5" />
      </button>

      <Link href="/" className="flex shrink-0 items-center gap-2.5 rounded-md pr-2">
        <span className="flex h-8 w-8 items-center justify-center rounded-md bg-brand text-sm font-bold text-white">
          OO
        </span>
        <span className="hidden text-[17px] font-semibold tracking-tight text-ink md:block">
          Open Orchestrator
        </span>
      </Link>

      <div className="relative ml-1 min-w-0 max-w-xl flex-1 sm:ml-3">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
        <input
          type="search"
          value={kata}
          onChange={(e) => setKata(e.target.value)}
          placeholder={t("Cari proses, robot, antrean...")}
          aria-label={t("Cari")}
          className="w-full rounded-md border border-line bg-canvas py-2 pl-9 pr-3 text-sm outline-none transition focus:border-brand focus:bg-card focus:ring-2 focus:ring-brand/15"
        />

        {tunda.length >= 2 ? (
          <div className="absolute left-0 right-0 top-full z-50 mt-1 max-h-96 overflow-y-auto rounded-lg border border-line bg-card shadow-lg">
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
                  <span className="w-24 shrink-0 truncate text-xs font-medium text-muted">{t(h.kind)}</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium text-ink">{h.label}</span>
                    {h.folderId && h.kind !== "Folder" ? (
                      <span className="block truncate text-[11px] text-muted">{folders.namaJalur(h.folderId)}</span>
                    ) : null}
                  </span>
                  <span className="max-w-[40%] truncate text-xs text-muted">{tp(h.detail)}</span>
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
          lebar layarnya. */}
      <div className="ml-auto flex shrink-0 items-center gap-0.5">
        {/* Bahasa */}
        <div className="relative">
          <button
            type="button"
            onClick={() => alih("bahasa")}
            className="flex items-center gap-1.5 rounded-md px-2.5 py-2 text-sm text-ink hover:bg-slate-100"
            aria-label={t("Bahasa")}
            aria-expanded={menu === "bahasa"}
          >
            <Globe className="h-4 w-4 text-muted" />
            {/* Kodenya hanya di layar lebar: di ponsel bilah atas terlalu sempit, dan globe saja sudah cukup dikenali. */}
            <span className="hidden uppercase sm:inline">{bahasa}</span>
          </button>

          {menu === "bahasa" ? (
            <div className="absolute right-0 z-50 mt-1 w-40 rounded-lg border border-line bg-card py-1 shadow-lg">
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
                    b.kode === bahasa && "font-semibold text-brand",
                  )}
                >
                  {b.nama}
                </button>
              ))}
            </div>
          ) : null}
        </div>

        {/* Tema */}
        <div className="relative">
          <button
            type="button"
            onClick={() => alih("tema")}
            className="rounded-md p-2 text-muted hover:bg-slate-100 hover:text-ink"
            aria-label={t("Tampilan")}
            title={t("Tampilan")}
            aria-expanded={menu === "tema"}
          >
            <IkonTema className="h-[18px] w-[18px]" />
          </button>

          {menu === "tema" ? (
            <div role="menu" className="absolute right-0 z-50 mt-1 w-56 rounded-lg border border-line bg-card py-1 shadow-lg">
              <p className="px-4 pb-1 pt-1.5 text-[11px] font-medium uppercase tracking-wide text-muted">{t("Tampilan")}</p>
              {PILIHAN_TEMA.map((p) => (
                <button
                  key={p.kode}
                  type="button"
                  role="menuitemradio"
                  aria-checked={tema === p.kode}
                  onClick={() => {
                    setTema(p.kode);
                    setMenu(null);
                  }}
                  className={cn(
                    "flex w-full items-center gap-2.5 px-4 py-2 text-left text-sm hover:bg-slate-50",
                    tema === p.kode ? "font-semibold text-brand" : "text-ink",
                  )}
                >
                  <p.ikon className="h-4 w-4 shrink-0" />
                  <span className="flex-1">{t(p.label)}</span>
                  {tema === p.kode ? <Check className="h-4 w-4 shrink-0" /> : null}
                </button>
              ))}
            </div>
          ) : null}
        </div>

        {/* Peringatan */}
        {lihatPeringatan ? (
          <div className="relative">
            <button
              type="button"
              onClick={() => alih("peringatan")}
              className="relative rounded-md p-2 hover:bg-slate-100"
              aria-label={t("Peringatan Terbaru")}
              aria-expanded={menu === "peringatan"}
            >
              <Bell className="h-5 w-5 text-muted" />
              {belumDibaca > 0 ? (
                <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-semibold text-white">
                  {belumDibaca > 99 ? "99+" : belumDibaca}
                </span>
              ) : null}
            </button>

            {menu === "peringatan" ? (
              <div className="absolute right-0 z-50 mt-1 w-96 max-w-[calc(100vw-2rem)] rounded-lg border border-line bg-card shadow-lg">
                <div className="flex items-center justify-between border-b border-line px-4 py-2">
                  <span className="text-sm font-semibold">{t("Peringatan Terbaru")}</span>
                  {belumDibaca > 0 ? (
                    <button type="button" onClick={tandaiSemua} className="text-xs text-brand hover:underline">
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

                <Link
                  href="/tenant/alerts"
                  onClick={() => setMenu(null)}
                  className="block border-t border-line px-4 py-2 text-center text-xs font-medium text-brand hover:bg-slate-50"
                >
                  {t("Lihat semua peringatan")}
                </Link>
              </div>
            ) : null}
          </div>
        ) : null}

        <div className="mx-1.5 hidden h-8 w-px bg-line sm:block" aria-hidden="true" />

        <ProfileMenu terbuka={menu === "profil"} onAlih={() => alih("profil")} onTutup={() => setMenu(null)} />
      </div>
    </header>
  );
}
