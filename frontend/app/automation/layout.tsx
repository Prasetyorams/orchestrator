import type { ReactNode } from "react";
import { Bagian } from "@/components/Bagian";
import { SUBTAB } from "@/lib/navigasi";

export default function LayoutAutomations({ children }: { children: ReactNode }) {
  return (
    <Bagian judul="Automations" subtab={SUBTAB.automation}>
      {children}
    </Bagian>
  );
}
