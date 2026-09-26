import type { ReactNode } from "react";
import { Bagian } from "@/components/Bagian";
import { SUBTAB } from "@/lib/navigasi";

export default function LayoutPengguna({ children }: { children: ReactNode }) {
  return (
    <Bagian judul="Pengguna" subtab={SUBTAB.tenantPengguna} konteks="tenant">
      {children}
    </Bagian>
  );
}
