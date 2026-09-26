"use client";

import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { DaftarPaket } from "@/components/DaftarPaket";
import { JudulHalaman } from "@/components/HalamanFolder";

/**
 * Umpan paket seluruh penyewa — satu untuk semua folder. Proses di folder
 * mana pun memilih paketnya dari sini.
 */
export default function PaketPenyewa() {
  const { t } = useT();
  const { boleh } = useIzin();

  return (
    <div>
      <JudulHalaman judul={t("Paket")} konteks="tenant" />
      <DaftarPaket bolehHapus={boleh("packages.delete")} />
    </div>
  );
}
