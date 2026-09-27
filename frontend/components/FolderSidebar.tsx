"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Building2, ChevronRight, Folder, FolderLock, FolderOpen, FolderPlus, Search, X } from "lucide-react";
import type { FolderNode } from "@/lib/api";
import { OpenOrchestratorApi, errorText } from "@/lib/api";
import { useFolder } from "@/lib/folder";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { TAB_TENANT, jalurTerakhir, konteksDari, tabTerbuka } from "@/lib/navigasi";
import { cn } from "@/lib/utils";
import { IconButton } from "@/components/ui/primitives";
import { DialogFolder } from "@/components/DialogFolder";

const KUNCI_TERBUKA = "openorchestrator.folder.terbuka";

/**
 * Bilah kiri: penyewa di puncaknya, lalu Folder Saya, pencarian, dan pohon
 * folder bersama. Di sinilah KONTEKS dipilih, seperti Orchestrator:
 *
 * - Mengklik penyewa membuka pengelolaan penyewa (tab Tenant).
 * - Mengklik sebuah folder membuka isi folder itu. Memilih folder MENGGANTI
 *   isi seluruh halaman folder — dasbor, proses, antrean, dan seterusnya —
 *   karena semuanya membaca folder yang sama dari FolderProvider.
 *
 * Berpindah konteks kembali ke halaman terakhir di konteks itu: dari daftar
 * pengguna, mengklik sebuah folder membuka lagi halaman folder yang tadi
 * ditinggalkan, bukan selalu Beranda.
 *
 * Di layar sempit bilah ini menjadi laci yang dibuka tombol menu di bilah
 * atas, supaya isi halaman tidak terjepit selebar dua ratus piksel.
 */
export function FolderSidebar({ terbuka, onTutup }: { terbuka: boolean; onTutup: () => void }) {
  const { t, tp } = useT();
  const pathname = usePathname();
  const router = useRouter();
  const { pohon, folder, pilih, bukaPribadi, memuat, galat, bolehKelola, jalur } = useFolder();
  const { boleh } = useIzin();
  const me = useQuery({ queryKey: ["me"], queryFn: OpenOrchestratorApi.me, staleTime: 30_000 });

  const konteks = konteksDari(pathname);

  // Penyewa bisa dibuka kalau setidaknya satu tab Tenant boleh dibuka. Yang
  // tidak boleh tetap melihat NAMA penyewanya — itu tetap keterangan yang
  // berguna — tapi sebagai tulisan, bukan tombol.
  const tabTenant = tabTerbuka(TAB_TENANT, boleh);
  const namaPenyewa = me.data?.tenantDisplayName || me.data?.tenantName || "Tenant";

  const [cari, setCari] = useState("");
  const [dibuka, setDibuka] = useState<Set<string> | null>(null);
  const [baru, setBaru] = useState(false);
  const [galatPribadi, setGalatPribadi] = useState("");

  const folders = useMemo(() => pohon?.folders ?? [], [pohon?.folders]);

  const anak = useMemo(() => {
    const ada = new Set(folders.map((f) => f.id));
    const peta = new Map<string | null, FolderNode[]>();

    for (const f of folders) {
      const induk = f.parentId && ada.has(f.parentId) ? f.parentId : null;
      const daftar = peta.get(induk);
      if (daftar) daftar.push(f);
      else peta.set(induk, [f]);
    }

    for (const daftar of peta.values()) {
      daftar.sort(
        (a, b) =>
          Number(b.isDefault) - Number(a.isDefault) ||
          a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" }),
      );
    }

    return peta;
  }, [folders]);

  // Cabang yang terbuka diingat per peramban. Belum ada ingatan berarti
  // semuanya terbuka: pohon yang tertutup rapat pada kunjungan pertama
  // menyembunyikan justru folder yang sedang dicari.
  useEffect(() => {
    try {
      const tersimpan = window.localStorage.getItem(KUNCI_TERBUKA);
      if (tersimpan) setDibuka(new Set(JSON.parse(tersimpan) as string[]));
    } catch {
      /* diabaikan */
    }
  }, []);

  // Leluhur folder yang terpilih selalu dibuka SEKALI saat foldernya
  // berganti, supaya pilihannya terlihat — tapi tetap boleh ditutup sesudahnya.
  useEffect(() => {
    if (!folder || !dibuka) return;

    const leluhur = jalur(folder.id).slice(0, -1).map((f) => f.id);
    if (leluhur.every((id) => dibuka.has(id))) return;

    setDibuka(new Set([...dibuka, ...leluhur]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [folder?.id]);

  function alihkan(id: string) {
    const kini = dibuka ?? new Set(folders.map((f) => f.id));
    const berikut = new Set(kini);

    if (berikut.has(id)) berikut.delete(id);
    else berikut.add(id);

    setDibuka(berikut);
    try {
      window.localStorage.setItem(KUNCI_TERBUKA, JSON.stringify([...berikut]));
    } catch {
      /* diabaikan */
    }
  }

  function pilihFolder(id: string) {
    pilih(id);
    onTutup();
    if (konteks === "tenant") router.push(jalurTerakhir("folder"));
  }

  async function pilihPribadi() {
    setGalatPribadi("");
    try {
      await bukaPribadi();
      onTutup();
      if (konteks === "tenant") router.push(jalurTerakhir("folder"));
    } catch (e) {
      setGalatPribadi(errorText(e));
    }
  }

  function bukaPenyewa() {
    onTutup();
    if (konteks === "tenant") return;

    // Halaman Tenant terakhir kalau pernah ada; "/tenant" berarti belum, dan
    // yang dibuka tab pertama yang boleh.
    const terakhir = jalurTerakhir("tenant");
    router.push(terakhir !== "/tenant" ? terakhir : (tabTenant[0]?.href ?? "/tenant"));
  }

  // Pencarian menampilkan folder yang cocok BESERTA leluhurnya, supaya
  // "Tagihan" yang ditemukan tetap terlihat berada di dalam "Keuangan".
  const kata = cari.trim().toLowerCase();

  const tampil = useMemo(() => {
    if (!kata) return null;

    const perId = new Map(folders.map((f) => [f.id, f]));
    const hasil = new Set<string>();

    for (const f of folders) {
      if (!f.name.toLowerCase().includes(kata)) continue;

      for (let x: FolderNode | undefined = f; x && !hasil.has(x.id); x = x.parentId ? perId.get(x.parentId) : undefined) {
        hasil.add(x.id);
      }
    }

    return hasil;
  }, [folders, kata]);

  const pribadiTerpilih = konteks === "folder" && !!folder?.personal;

  // Fungsi biasa, BUKAN komponen: komponen yang ditulis di dalam komponen lain
  // menjadi jenis baru di setiap render, dan React membongkar lalu memasang
  // ulang seluruh pohonnya — fokus papan ketik ikut hilang.
  function simpul(node: FolderNode, kedalaman: number): ReactNode {
    const turunan = (anak.get(node.id) ?? []).filter((f) => !tampil || tampil.has(f.id));
    const punyaAnak = turunan.length > 0;
    const terbukaIni = tampil ? true : dibuka ? dibuka.has(node.id) : true;
    const terpilih = konteks === "folder" && folder?.id === node.id;
    const bisa = node.accessible !== false;
    const cocok = !!kata && node.name.toLowerCase().includes(kata);

    return (
      <li key={node.id} role="treeitem" aria-expanded={punyaAnak ? terbukaIni : undefined} aria-selected={terpilih}>
        <div
          className={cn(
            "flex items-center gap-0.5 border-l-[3px] pr-2",
            terpilih ? "border-brand bg-brandSoft" : "border-transparent hover:bg-slate-50",
          )}
          style={{ paddingLeft: 6 + kedalaman * 14 }}
        >
          {punyaAnak ? (
            <button
              type="button"
              onClick={() => alihkan(node.id)}
              aria-label={terbukaIni ? t("Tutup cabang") : t("Buka cabang")}
              className="rounded p-0.5 text-muted hover:bg-slate-200/70 hover:text-ink"
              disabled={!!tampil}
            >
              <ChevronRight className={cn("h-3.5 w-3.5 transition-transform", terbukaIni && "rotate-90")} />
            </button>
          ) : (
            <span className="w-[18px] shrink-0" aria-hidden="true" />
          )}

          <button
            type="button"
            onClick={() => bisa && pilihFolder(node.id)}
            disabled={!bisa}
            title={bisa ? tp(node.description) || node.name : t("Anda tidak ditugaskan ke folder ini.")}
            className={cn(
              "flex min-w-0 flex-1 items-center gap-2 py-[7px] pl-1 text-left text-sm",
              terpilih ? "font-semibold text-brand" : "text-ink",
              !bisa && "cursor-default text-muted",
            )}
          >
            {terpilih ? (
              <FolderOpen className="h-4 w-4 shrink-0" />
            ) : (
              <Folder className={cn("h-4 w-4 shrink-0", bisa ? "text-ink/70" : "text-muted/60")} />
            )}
            <span className={cn("truncate", cocok && "rounded bg-amber-100 px-0.5")}>{node.name}</span>
          </button>
        </div>

        {punyaAnak && terbukaIni ? (
          <ul role="group">
            {turunan.map((f) => simpul(f, kedalaman + 1))}
          </ul>
        ) : null}
      </li>
    );
  }

  const akar = (anak.get(null) ?? []).filter((f) => !tampil || tampil.has(f.id));

  return (
    <>
      {terbuka ? (
        <div className="fixed inset-0 z-30 bg-black/30 dark:bg-black/50 lg:hidden" onClick={onTutup} aria-hidden="true" />
      ) : null}

      <aside
        aria-label={t("Folder")}
        className={cn(
          "z-40 flex w-64 shrink-0 flex-col border-r border-line bg-card",
          "max-lg:fixed max-lg:inset-y-0 max-lg:left-0 max-lg:shadow-xl max-lg:transition-transform",
          terbuka ? "max-lg:translate-x-0" : "max-lg:-translate-x-full",
        )}
      >
        <div className="flex items-center gap-1 border-b border-line px-1 py-2">
          {tabTenant.length ? (
            <button
              type="button"
              onClick={bukaPenyewa}
              aria-current={konteks === "tenant" ? "page" : undefined}
              title={t("Pengelolaan penyewa: folder, pengguna, robot, paket, peringatan, audit, setelan.")}
              className={cn(
                "flex min-w-0 flex-1 items-center gap-2.5 rounded-md border-l-[3px] px-3 py-2 text-left text-sm",
                konteks === "tenant"
                  ? "border-brand bg-brandSoft font-semibold text-brand"
                  : "border-transparent text-ink hover:bg-slate-50",
              )}
            >
              <Building2 className="h-4 w-4 shrink-0" />
              <span className="min-w-0 flex-1 truncate">{namaPenyewa}</span>
              <span className="shrink-0 text-[10px] font-medium uppercase tracking-wide text-muted">Tenant</span>
            </button>
          ) : (
            <p className="flex min-w-0 flex-1 items-center gap-2.5 px-4 py-2 text-sm text-muted">
              <Building2 className="h-4 w-4 shrink-0" />
              <span className="truncate">{namaPenyewa}</span>
            </p>
          )}

          <span className="lg:hidden">
            <IconButton label={t("Tutup")} onClick={onTutup}>
              <X size={17} />
            </IconButton>
          </span>
        </div>

        <div className="flex items-center justify-between px-4 pb-1 pt-3">
          <h2 className="text-[15px] font-semibold text-ink">{t("Folder")}</h2>

          {bolehKelola ? (
            <IconButton label={t("Folder baru")} onClick={() => setBaru(true)}>
              <FolderPlus size={17} />
            </IconButton>
          ) : null}
        </div>

        <div className="border-l-[3px] border-transparent px-1">
          <button
            type="button"
            onClick={pilihPribadi}
            aria-pressed={pribadiTerpilih}
            title={t("Folder pribadi: hanya Anda yang melihatnya.")}
            className={cn(
              "flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm",
              pribadiTerpilih ? "bg-brandSoft font-semibold text-brand" : "text-ink hover:bg-slate-50",
            )}
          >
            <FolderLock className="h-4 w-4 shrink-0" />
            {t("Folder Saya")}
          </button>
          {galatPribadi ? <p className="px-3 pb-1 text-xs text-danger">{galatPribadi}</p> : null}
        </div>

        <div className="px-3 pb-2 pt-2">
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <input
              type="search"
              value={cari}
              onChange={(e) => setCari(e.target.value)}
              placeholder={t("Cari folder...")}
              aria-label={t("Cari folder...")}
              className="w-full rounded-md border border-line bg-card py-1.5 pl-8 pr-2 text-sm outline-none transition focus:border-brand focus:ring-2 focus:ring-brand/15"
            />
          </div>
        </div>

        <nav className="thin-scroll min-h-0 flex-1 overflow-y-auto pb-4">
          {memuat ? (
            <p className="px-4 py-2 text-sm text-muted">{t("Memuat...")}</p>
          ) : galat ? (
            <p className="px-4 py-2 text-sm text-danger">{t("Daftar folder tidak bisa diambil.")}</p>
          ) : akar.length ? (
            <ul role="tree" aria-label={t("Folder")}>
              {akar.map((f) => simpul(f, 0))}
            </ul>
          ) : (
            <p className="px-4 py-2 text-sm text-muted">
              {kata ? t("Tidak ada folder yang cocok.") : t("Anda belum ditugaskan ke folder bersama mana pun.")}
            </p>
          )}
        </nav>
      </aside>

      {baru ? (
        <DialogFolder
          indukAwal={null}
          onTutup={() => setBaru(false)}
          onSelesai={(id) => pilihFolder(id)}
        />
      ) : null}
    </>
  );
}
