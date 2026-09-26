"use client";

import { useEffect, useState, type ReactNode } from "react";
import { usePathname } from "next/navigation";
import { FolderProvider, useFolder } from "@/lib/folder";
import { useIzin } from "@/lib/izin";
import { ingatJalur, izinUntukJalur, konteksDari } from "@/lib/navigasi";
import { TopNav } from "@/components/TopNav";
import { NavBar } from "@/components/NavBar";
import { FolderSidebar } from "@/components/FolderSidebar";
import { TanpaIzin } from "@/components/HalamanFolder";

/**
 * Kerangka halaman, seperti Orchestrator:
 *
 *   ┌──────────────── bilah atas: produk, cari, bahasa, tema, lonceng, profil ─┐
 *   ├──────────────────┬───────────────────────────────────────────────────────┤
 *   │ Penyewa          │ Beranda  Automations  Pemantauan  ...                 │
 *   │ Folder Saya      ├───────────────────────────────────────────────────────┤
 *   │ bilah folder     │ isi halaman                                           │
 *   └──────────────────┴───────────────────────────────────────────────────────┘
 *
 * Konteksnya dipilih di bilah kiri: penyewa di puncaknya membuka pengelolaan
 * penyewa, sebuah folder membuka isi folder itu. Baris tab di atas isi
 * mengikuti pilihan itu.
 *
 * Layar masuk TIDAK memakai kerangka ini: semuanya menampilkan nama pengguna,
 * peringatan, dan folder, yang berarti memanggil API sebelum ada token.
 */
export function Shell({ children }: { children: ReactNode }) {
  const path = usePathname();

  if (path === "/login") {
    return <main className="min-h-screen">{children}</main>;
  }

  return (
    <FolderProvider>
      <Kerangka>{children}</Kerangka>
    </FolderProvider>
  );
}

function Kerangka({ children }: { children: ReactNode }) {
  const path = usePathname();
  const { folderId } = useFolder();
  const { boleh } = useIzin();
  const [laci, setLaci] = useState(false);

  // Laci folder di layar sempit menutup sendiri begitu halamannya berganti.
  useEffect(() => setLaci(false), [path]);

  // Halaman terakhir tiap konteks, untuk kembali ke sana dari bilah folder.
  useEffect(() => ingatJalur(path), [path]);

  const konteks = konteksDari(path);

  // Halaman yang tidak boleh dibuka diganti pesan, bukan dibiarkan memanggil
  // API yang pasti menjawab 403 lalu tampil sebagai tabel kosong.
  const izinHalaman = izinUntukJalur(path);
  const terlarang = !!izinHalaman && !izinHalaman.some(boleh);

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-canvas">
      <TopNav onMenu={() => setLaci((x) => !x)} />

      <div className="relative flex min-h-0 flex-1">
        <FolderSidebar terbuka={laci} onTutup={() => setLaci(false)} />

        <div className="flex min-w-0 flex-1 flex-col">
          <NavBar />

          {/* Hanya bagian isi yang menggulir; bilah atas, tab, dan bilah
              folder tetap di tempat, supaya menu tidak ikut hilang saat tabel
              panjang digulir. */}
          <main className="thin-scroll min-h-0 flex-1 overflow-y-auto p-4 sm:p-6">
            {/* Kunci per folder: berganti folder memasang ulang halamannya, jadi
                baris terpilih, dialog yang terbuka, dan isian yang setengah
                diisi dari folder lama tidak terbawa ke folder baru. */}
            <div key={konteks === "folder" ? (folderId ?? "-") : "tenant"}>
              {terlarang ? <TanpaIzin /> : children}
            </div>
          </main>
        </div>
      </div>
    </div>
  );
}
