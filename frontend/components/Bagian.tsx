"use client";

import type { ReactNode } from "react";
import type { SubTab } from "@/lib/navigasi";
import { useT } from "@/lib/i18n";
import { SubTabs } from "@/components/SubTab";
import { JudulHalaman } from "@/components/HalamanFolder";

/**
 * Kepala sebuah bagian yang punya beberapa halaman — Automations,
 * Pemantauan, Pengguna, Robot: tempatnya, judulnya, lalu tab halamannya.
 * Halaman di dalamnya cukup menggambar isinya sendiri.
 */
export function Bagian({
  judul,
  subtab,
  konteks = "folder",
  children,
}: {
  judul: string;
  subtab: SubTab[];
  konteks?: "folder" | "tenant";
  children: ReactNode;
}) {
  const { t } = useT();

  return (
    <>
      <JudulHalaman judul={t(judul)} konteks={konteks} />
      <SubTabs items={subtab} />
      {children}
    </>
  );
}
