import type { ReactNode } from "react";
import { Bagian } from "@/components/Bagian";
import { SUBTAB } from "@/lib/navigasi";

export default function LayoutRobot({ children }: { children: ReactNode }) {
  return (
    <Bagian judul="Robot" subtab={SUBTAB.tenantRobot} konteks="tenant">
      {children}
    </Bagian>
  );
}
