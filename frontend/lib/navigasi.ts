import {
  AppWindow,
  Bell,
  Bot,
  ChartNoAxesColumn,
  Cog,
  CopyPlus,
  Folders,
  History,
  House,
  List,
  Package,
  Settings,
  Users,
  type LucideIcon,
} from "lucide-react";

/**
 * Susunan menu, seperti Orchestrator: dua KONTEKS — Tenant dan folder — dan
 * tab fitur yang isinya bergantung pada konteks itu. Konteksnya dipilih di
 * bilah kiri: penyewa di puncaknya, folder di bawahnya.
 *
 * - Konteks folder: semua yang berhubungan dengan menjalankan pekerjaan,
 *   datanya milik folder yang dipilih di bilah folder.
 * - Konteks Tenant: pengelolaan seluruh penyewa — folder itu sendiri,
 *   pengguna, robot dan mesin, umpan paket, peringatan, audit, setelan.
 *
 * Setiap tab menyebut izin yang membukanya (lihat lib/izin.ts); tab tanpa
 * izin terbuka untuk siapa pun yang masuk. Tab dengan sub-tab cukup terbuka
 * oleh SALAH SATU izin sub-tabnya, dan membuka sub-tab pertama yang boleh.
 *
 * Label ditulis dalam bahasa Indonesia dan diterjemahkan saat dirender, sama
 * seperti seluruh antarmuka.
 */

export type SubTab = {
  label: string;
  href: string;
  /** Izin yang membuka sub-tab ini. */
  izin?: string;
};

export type Tab = {
  label: string;
  href: string;
  ikon: LucideIcon;
  /** Awalan jalur yang membuat tab ini tampak aktif. */
  awalan: string;
  /** Izin yang membukanya — cukup salah satu. */
  izin?: string[];
  /** Sub-tabnya; tautan tab menuju sub-tab pertama yang boleh dibuka. */
  sub?: SubTab[];
};

export type Konteks = "tenant" | "folder";

/** Tab di dalam halaman, di bawah tab utama. */
export const SUBTAB: Record<string, SubTab[]> = {
  automation: [
    { label: "Proses", href: "/automation/processes", izin: "processes.read" },
    { label: "Pekerjaan", href: "/automation/jobs", izin: "jobs.read" },
    { label: "Pemicu", href: "/automation/triggers", izin: "triggers.read" },
    { label: "Paket", href: "/automation/packages", izin: "packages.read" },
  ],
  monitoring: [
    { label: "Robot", href: "/monitoring/robots", izin: "robots.read" },
    { label: "Catatan", href: "/monitoring/logs", izin: "logs.read" },
  ],
  tenantPengguna: [
    { label: "Pengguna", href: "/tenant/users", izin: "users.read" },
    { label: "Peran", href: "/tenant/users/roles", izin: "roles.read" },
  ],
  tenantRobot: [
    { label: "Robot", href: "/tenant/robots", izin: "robots.read" },
    { label: "Mesin", href: "/tenant/robots/machines", izin: "machines.read" },
    { label: "Lingkungan", href: "/tenant/robots/environments", izin: "environments.read" },
  ],
};

/** Izin yang membuka salah satu sub-tab. */
const izinSub = (sub: SubTab[]) => sub.flatMap((s) => (s.izin ? [s.izin] : []));

export const TAB_FOLDER: Tab[] = [
  { label: "Beranda", href: "/", ikon: House, awalan: "/" },
  {
    label: "Automations",
    href: "/automation/processes",
    ikon: Cog,
    awalan: "/automation",
    izin: izinSub(SUBTAB.automation),
    sub: SUBTAB.automation,
  },
  {
    label: "Pemantauan",
    href: "/monitoring/robots",
    ikon: ChartNoAxesColumn,
    awalan: "/monitoring",
    izin: izinSub(SUBTAB.monitoring),
    sub: SUBTAB.monitoring,
  },
  { label: "Queues", href: "/queues", ikon: CopyPlus, awalan: "/queues", izin: ["queues.read"] },
  { label: "Assets", href: "/assets", ikon: AppWindow, awalan: "/assets", izin: ["assets.read"] },
  { label: "Ember Penyimpanan", href: "/buckets", ikon: List, awalan: "/buckets", izin: ["buckets.read"] },
  { label: "Setelan", href: "/folder-settings", ikon: Settings, awalan: "/folder-settings" },
];

export const TAB_TENANT: Tab[] = [
  { label: "Folder|tab", href: "/tenant/folders", ikon: Folders, awalan: "/tenant/folders", izin: ["folders.read"] },
  {
    label: "Pengguna",
    href: "/tenant/users",
    ikon: Users,
    awalan: "/tenant/users",
    izin: izinSub(SUBTAB.tenantPengguna),
    sub: SUBTAB.tenantPengguna,
  },
  {
    label: "Robot",
    href: "/tenant/robots",
    ikon: Bot,
    awalan: "/tenant/robots",
    izin: izinSub(SUBTAB.tenantRobot),
    sub: SUBTAB.tenantRobot,
  },
  { label: "Paket", href: "/tenant/packages", ikon: Package, awalan: "/tenant/packages", izin: ["packages.read"] },
  { label: "Peringatan", href: "/tenant/alerts", ikon: Bell, awalan: "/tenant/alerts", izin: ["alerts.read"] },
  { label: "Audit", href: "/tenant/audit", ikon: History, awalan: "/tenant/audit", izin: ["audit.read"] },
  { label: "Setelan", href: "/tenant/settings", ikon: Settings, awalan: "/tenant/settings", izin: ["settings.read"] },
];

/**
 * Tab yang boleh dibuka, dengan tautan ke sub-tab pertama yang boleh — orang
 * tanpa izin membaca proses yang mengklik Automations langsung sampai di
 * Pekerjaan, bukan di halaman yang menolaknya.
 */
export function tabTerbuka(tabs: Tab[], boleh: (izin: string) => boolean): Tab[] {
  return tabs
    .filter((tab) => !tab.izin || tab.izin.some(boleh))
    .map((tab) => {
      const pertama = tab.sub?.find((s) => !s.izin || boleh(s.izin));
      return pertama ? { ...tab, href: pertama.href } : tab;
    });
}

/**
 * Izin yang membuka sebuah halaman — cukup salah satu — atau null kalau
 * halaman itu terbuka untuk siapa pun yang masuk. Diambil dari susunan menu
 * di atas, jadi menu dan penjaga halaman tidak bisa berbeda pendapat.
 */
export function izinUntukJalur(pathname: string): string[] | null {
  for (const daftar of Object.values(SUBTAB)) {
    const sub = daftar.find((s) => s.href === pathname);
    if (sub) return sub.izin ? [sub.izin] : null;
  }

  const tab = [...TAB_FOLDER, ...TAB_TENANT].find((x) => tabAktif(x, pathname));

  return tab?.izin ?? null;
}

/**
 * Halaman terakhir yang dibuka di tiap konteks, selama tab ini terbuka.
 *
 * Berpindah konteks lewat bilah folder kembali ke halaman TERAKHIR di konteks
 * itu, bukan ke halaman pertamanya: orang yang menengok daftar pengguna lalu
 * mengklik sebuah folder mengharapkan antrean yang tadi ditinggalkannya.
 */
const terakhir: Record<Konteks, string> = { tenant: "/tenant", folder: "/" };

export function ingatJalur(pathname: string) {
  terakhir[konteksDari(pathname)] = pathname;
}

export function jalurTerakhir(konteks: Konteks): string {
  return terakhir[konteks];
}

export function konteksDari(pathname: string): Konteks {
  return pathname === "/tenant" || pathname.startsWith("/tenant/") ? "tenant" : "folder";
}

/**
 * Tab utama yang aktif untuk sebuah jalur.
 *
 * Beranda cocok PERSIS, bukan lewat awalan: "/" adalah awalan dari setiap
 * jalur, dan Beranda akan selalu tampak aktif.
 */
export function tabAktif(tab: Tab, pathname: string): boolean {
  if (tab.awalan === "/") return pathname === "/";

  return pathname === tab.awalan || pathname.startsWith(tab.awalan + "/");
}

/** Ke mana tiap jenis hasil pencarian mengarah. */
export const HALAMAN_CARI: Record<string, string> = {
  processes: "/automation/processes",
  triggers: "/automation/triggers",
  queues: "/queues",
  assets: "/assets",
  buckets: "/buckets",
  folders: "/",
  packages: "/tenant/packages",
  robots: "/tenant/robots",
};
