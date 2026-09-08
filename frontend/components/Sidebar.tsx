"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Activity,
  Bot,
  Boxes,
  Clock,
  Database,
  FolderOpen,
  Gauge,
  KeyRound,
  Layers,
  ListChecks,
  ScrollText,
  Settings,
  Users,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useT } from "@/lib/i18n";

type Item = { label: string; href: string; icon: typeof Gauge };
type Kelompok = { judul?: string; items: Item[] };

/**
 * Menu samping.
 *
 * Label ditulis dalam bahasa Indonesia dan diterjemahkan saat dirender, sama
 * seperti seluruh antarmuka: kunci kamusnya adalah teks Indonesianya sendiri,
 * jadi label yang belum diterjemahkan tetap terbaca, bukan berubah menjadi
 * kode.
 *
 * Dikelompokkan karena tanpa judul kelompok, empat belas menu berderet lurus
 * dan orang harus membaca semuanya untuk menemukan satu.
 */
const KELOMPOK: Kelompok[] = [
  {
    items: [{ label: "Beranda", href: "/", icon: Gauge }],
  },
  {
    judul: "Pemantauan",
    items: [
      { label: "Pekerjaan", href: "/monitoring/jobs", icon: Activity },
      { label: "Catatan", href: "/monitoring/logs", icon: ScrollText },
      { label: "Pemicu", href: "/monitoring/triggers", icon: Clock },
    ],
  },
  {
    judul: "Otomasi",
    items: [
      { label: "Proses", href: "/automation/processes", icon: Boxes },
      { label: "Paket", href: "/automation/packages", icon: Layers },
      { label: "Gudang", href: "/automation/libraries", icon: FolderOpen },
    ],
  },
  {
    judul: "Robot",
    items: [
      { label: "Robot", href: "/robots/machines", icon: Bot },
      { label: "Lingkungan", href: "/robots/environments", icon: Layers },
      { label: "Kredensial", href: "/robots/credentials", icon: KeyRound },
    ],
  },
  {
    items: [
      { label: "Antrean", href: "/queues", icon: ListChecks },
      { label: "Aset", href: "/assets", icon: Database },
      { label: "Penyewa", href: "/tenants", icon: Users },
      { label: "Setelan", href: "/settings", icon: Settings },
    ],
  },
];

export function Sidebar() {
  const pathname = usePathname();
  const { t } = useT();

  return (
    <aside className="flex h-screen w-60 shrink-0 flex-col bg-sidebar text-slate-300">
      <div className="flex items-center gap-2.5 px-5 py-5">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-info text-sm font-bold text-white">
          FH
        </div>
        <div>
          <p className="text-sm font-semibold text-white">ForgeHub</p>
          <p className="text-[11px] text-slate-400">JakForge Orchestrator</p>
        </div>
      </div>

      <nav className="thin-scroll flex-1 overflow-y-auto px-3 pb-6">
        {KELOMPOK.map((kelompok, i) => (
          <div key={i} className="mb-4">
            {kelompok.judul ? (
              <p className="px-2 pb-1.5 pt-2 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                {t(kelompok.judul)}
              </p>
            ) : null}

            {kelompok.items.map((item) => {
              // Cocok PERSIS, bukan startsWith: "/" adalah awalan dari setiap
              // jalur, jadi Beranda akan selalu tampak aktif.
              const aktif = pathname === item.href;
              const Ikon = item.icon;

              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={cn(
                    "mb-0.5 flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] transition",
                    aktif
                      ? "bg-sidebarHover font-medium text-white"
                      : "hover:bg-sidebarHover/60 hover:text-white",
                  )}
                >
                  <Ikon className="h-4 w-4 shrink-0" />
                  {t(item.label)}
                </Link>
              );
            })}
          </div>
        ))}
      </nav>
    </aside>
  );
}
