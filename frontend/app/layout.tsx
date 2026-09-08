import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";
import { Providers } from "@/components/Providers";
import { Sidebar } from "@/components/Sidebar";
import { TopNav } from "@/components/TopNav";

export const metadata: Metadata = {
  title: "ForgeHub — JakForge Orchestrator",
  description: "Pusat kendali robot, proses, antrean, dan catatan automasi JakForge.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="id">
      <body>
        <Providers>
          <div className="flex h-screen overflow-hidden">
            <Sidebar />

            <div className="flex min-w-0 flex-1 flex-col">
              <TopNav />
              {/* Hanya bagian isi yang menggulir; sidebar dan bilah atas
                  tetap di tempat, supaya menu tidak ikut hilang saat tabel
                  panjang digulir. */}
              <main className="thin-scroll flex-1 overflow-y-auto p-6">{children}</main>
            </div>
          </div>
        </Providers>
      </body>
    </html>
  );
}
