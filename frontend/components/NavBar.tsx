"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { TAB_FOLDER, TAB_TENANT, konteksDari, tabAktif, tabTerbuka } from "@/lib/navigasi";
import { cn } from "@/lib/utils";

/**
 * Baris tab di atas isi halaman: tab fitur milik konteks yang sedang dibuka.
 *
 * Konteksnya dipilih di bilah kiri, bukan di sini — seperti Orchestrator:
 * mengklik sebuah folder membuka isi folder itu, mengklik penyewa di puncak
 * bilah membuka pengelolaan penyewa. Dua tombol konteks di baris ini hanya
 * mengulang pilihan yang sudah terlihat di sebelahnya.
 *
 * Tab yang tidak boleh dibuka peran orangnya tidak ditampilkan sama sekali.
 */
export function NavBar() {
  const pathname = usePathname();
  const { t } = useT();
  const { boleh } = useIzin();

  const tabs = tabTerbuka(konteksDari(pathname) === "tenant" ? TAB_TENANT : TAB_FOLDER, boleh);

  return (
    <nav
      aria-label={t("Menu utama")}
      className="tanpa-bilah-gulir flex h-12 shrink-0 items-stretch overflow-x-auto border-b border-line bg-card px-1 sm:px-2"
    >
      {tabs.map((tab) => {
        const aktif = tabAktif(tab, pathname);
        const Ikon = tab.ikon;

        return (
          <Link
            key={tab.awalan}
            href={tab.href}
            aria-current={aktif ? "page" : undefined}
            className={cn(
              "flex shrink-0 items-center gap-2 whitespace-nowrap border-b-[3px] px-3 pt-[3px] text-sm transition",
              aktif
                ? "border-brand font-semibold text-brand"
                : "border-transparent text-ink/80 hover:bg-slate-50 hover:text-ink",
            )}
          >
            <Ikon className="h-[18px] w-[18px] shrink-0" strokeWidth={aktif ? 2.2 : 1.9} />
            {t(tab.label)}
          </Link>
        );
      })}
    </nav>
  );
}
