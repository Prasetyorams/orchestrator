import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";
import { Providers } from "@/components/Providers";
import { I18nProvider } from "@/lib/i18n";
import { SKRIP_TEMA, TemaProvider } from "@/lib/tema";
import { Shell } from "@/components/Shell";

export const metadata: Metadata = {
  title: "Open Orchestrator",
  description: "Pusat kendali robot, proses, antrean, dan catatan automasi JakForge.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    // suppressHydrationWarning: SKRIP_TEMA menambahkan kelas .dark ke <html>
    // sebelum React mengambil alih, jadi atribut itu memang berbeda dari HTML
    // server. Hanya berlaku untuk elemen ini, bukan anak-anaknya.
    <html lang="id" suppressHydrationWarning>
      <head>
        {/* Sebelum apa pun digambar: tanpa ini halaman gelap berkedip putih setiap dimuat. */}
        <script dangerouslySetInnerHTML={{ __html: SKRIP_TEMA }} />
      </head>
      <body>
        <Providers>
          <TemaProvider>
            <I18nProvider>
              <Shell>{children}</Shell>
            </I18nProvider>
          </TemaProvider>
        </Providers>
      </body>
    </html>
  );
}
