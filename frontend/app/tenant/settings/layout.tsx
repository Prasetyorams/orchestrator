import type { ReactNode } from "react";
import { Bagian } from "@/components/Bagian";
import { SUBTAB } from "@/lib/navigasi";

export default function LayoutSetelan({ children }: { children: ReactNode }) {
  return (
    <Bagian judul="Setelan" subtab={SUBTAB.tenantSetelan} konteks="tenant">
      {children}
    </Bagian>
  );
}
