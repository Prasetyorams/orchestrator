import type { ReactNode } from "react";
import { Bagian } from "@/components/Bagian";
import { SUBTAB } from "@/lib/navigasi";

export default function LayoutPemantauan({ children }: { children: ReactNode }) {
  return (
    <Bagian judul="Pemantauan" subtab={SUBTAB.monitoring}>
      {children}
    </Bagian>
  );
}
