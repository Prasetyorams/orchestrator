"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { Sidebar } from "@/components/Sidebar";
import { TopNav } from "@/components/TopNav";

/**
 * Kerangka halaman.
 *
 * Layar masuk TIDAK memakai sidebar dan bilah atas: keduanya menampilkan nama
 * pengguna dan jumlah peringatan, yang berarti memanggil API sebelum ada token
 * — dan yang terlihat adalah kerangka kosong dengan angka nol di sekeliling
 * kotak masuk.
 */
export function Shell({ children }: { children: ReactNode }) {
  const path = usePathname();

  if (path === "/login") {
    return <main className="min-h-screen">{children}</main>;
  }

  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar />

      <div className="flex min-w-0 flex-1 flex-col">
        <TopNav />
        {/* Hanya bagian isi yang menggulir; sidebar dan bilah atas tetap di
            tempat, supaya menu tidak ikut hilang saat tabel panjang digulir. */}
        <main className="thin-scroll flex-1 overflow-y-auto p-6">{children}</main>
      </div>
    </div>
  );
}
