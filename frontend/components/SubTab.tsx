"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { SubTab } from "@/lib/navigasi";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn } from "@/lib/utils";

/**
 * Tab kedua di dalam sebuah bagian — Proses, Pekerjaan, Pemicu, Paket di
 * bawah Automations — seperti tab halaman di Orchestrator.
 *
 * Dicocokkan PERSIS: /tenant/users adalah awalan dari /tenant/users/roles,
 * dan dengan awalan kedua tab akan tampak aktif bersamaan.
 */
export function SubTabs({ items }: { items: SubTab[] }) {
  const pathname = usePathname();
  const { t } = useT();
  const { boleh } = useIzin();

  // Sub-tab yang tidak boleh dibuka tidak ditawarkan. Yang sedang dibuka
  // tetap tampil — orangnya sampai ke sana lewat alamat langsung, dan tab
  // yang aktif harus tetap terlihat di atas pesan penolakannya.
  const tampil = items.filter((item) => !item.izin || boleh(item.izin) || pathname === item.href);

  return (
    <nav aria-label={t("Bagian")} className="tanpa-bilah-gulir mb-5 flex gap-1 overflow-x-auto overflow-y-hidden shadow-[inset_0_-1px_0_rgb(var(--c-line))]">
      {tampil.map((item) => {
        const aktif = pathname === item.href;

        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={aktif ? "page" : undefined}
            className={cn(
              "whitespace-nowrap border-b-2 px-3 py-2 text-sm transition",
              aktif
                ? "border-brand font-medium text-brand"
                : "border-transparent text-muted hover:border-slate-300 hover:text-ink",
            )}
          >
            {t(item.label)}
          </Link>
        );
      })}
    </nav>
  );
}
