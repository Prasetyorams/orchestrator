"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Activity,
  Bot,
  Boxes,
  Database,
  Gauge,
  Layers,
  ListChecks,
  Settings,
  Users,
} from "lucide-react";
import { cn } from "@/lib/utils";

type Item = { label: string; href: string; icon: typeof Gauge };
type Group = { title?: string; items: Item[] };

/**
 * Menu samping.
 *
 * Dikelompokkan seperti yang diminta: Robots, Automation, dan Monitoring
 * masing-masing membawa anak-anaknya. Pengelompokan itu bukan hiasan — tanpa
 * judul kelompok, sebelas menu berderet lurus dan orang harus membaca
 * semuanya untuk menemukan satu.
 */
const groups: Group[] = [
  {
    items: [{ label: "Dashboard", href: "/", icon: Gauge }],
  },
  {
    title: "Robots",
    items: [
      { label: "Machines", href: "/robots/machines", icon: Bot },
      { label: "Environments", href: "/robots/environments", icon: Layers },
      { label: "Credentials", href: "/robots/credentials", icon: Database },
    ],
  },
  {
    title: "Automation",
    items: [
      { label: "Processes", href: "/automation/processes", icon: Boxes },
      { label: "Packages", href: "/automation/packages", icon: Boxes },
      { label: "Libraries", href: "/automation/libraries", icon: Boxes },
    ],
  },
  {
    title: "Monitoring",
    items: [
      { label: "Jobs", href: "/monitoring/jobs", icon: Activity },
      { label: "Logs", href: "/monitoring/logs", icon: ListChecks },
      { label: "Triggers", href: "/monitoring/triggers", icon: Activity },
    ],
  },
  {
    items: [
      { label: "Queues", href: "/queues", icon: ListChecks },
      { label: "Assets", href: "/assets", icon: Database },
      { label: "Tenant Management", href: "/tenants", icon: Users },
      { label: "Settings", href: "/settings", icon: Settings },
    ],
  },
];

export function Sidebar() {
  const pathname = usePathname();

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
        {groups.map((group, index) => (
          <div key={index} className="mb-4">
            {group.title ? (
              <p className="px-2 pb-1.5 pt-2 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                {group.title}
              </p>
            ) : null}

            {group.items.map((item) => {
              const active = pathname === item.href;
              const Icon = item.icon;

              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={cn(
                    "mb-0.5 flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] transition",
                    active
                      ? "bg-sidebarHover font-medium text-white"
                      : "hover:bg-sidebarHover/60 hover:text-white",
                  )}
                >
                  <Icon className="h-4 w-4 shrink-0" />
                  {item.label}
                </Link>
              );
            })}
          </div>
        ))}
      </nav>
    </aside>
  );
}
