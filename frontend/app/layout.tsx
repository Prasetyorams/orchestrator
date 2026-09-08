import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";
import { Providers } from "@/components/Providers";
import { I18nProvider } from "@/lib/i18n";
import { Shell } from "@/components/Shell";

export const metadata: Metadata = {
  title: "ForgeHub — JakForge Orchestrator",
  description: "Pusat kendali robot, proses, antrean, dan catatan automasi JakForge.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="id">
      <body>
        <Providers>
          <I18nProvider>
            <Shell>{children}</Shell>
          </I18nProvider>
        </Providers>
      </body>
    </html>
  );
}
