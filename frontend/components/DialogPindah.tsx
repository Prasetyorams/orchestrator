"use client";

import { useMemo, useState } from "react";
import { errorText } from "@/lib/api";
import { useFolder } from "@/lib/folder";
import { useT } from "@/lib/i18n";
import { Button, Galat } from "@/components/ui/primitives";
import { Dialog, Isian } from "@/components/Dialog";
import { PilihFolder } from "@/components/PilihFolder";

/**
 * Pindahkan satu barang — proses, antrean, aset, ember — ke folder lain.
 *
 * Folder asalnya tidak ada di pilihan: memindah ke tempat yang sama bukan
 * pindahan, dan tombol yang tidak melakukan apa-apa membuat orang mengira
 * ada yang rusak.
 */
export function DialogPindah({
  judul,
  keterangan,
  folderSekarang,
  onPindah,
  onTutup,
}: {
  judul: string;
  /** Akibat yang perlu diketahui sebelum memindah, mis. "Pemicu dan riwayatnya ikut pindah." */
  keterangan?: string;
  folderSekarang: string | null;
  onPindah: (folderId: string) => Promise<unknown>;
  onTutup: () => void;
}) {
  const { t } = useT();
  const { bolehDibuka } = useFolder();

  const sembunyikan = useMemo(() => new Set(folderSekarang ? [folderSekarang] : []), [folderSekarang]);
  const pertama = bolehDibuka.find((f) => f.id !== folderSekarang)?.id ?? "";

  const [tujuan, setTujuan] = useState(pertama);
  const [galat, setGalat] = useState("");
  const [sibuk, setSibuk] = useState(false);

  async function kirim() {
    if (!tujuan) return setGalat(t("Pilih folder tujuannya dulu."));

    setSibuk(true);
    setGalat("");

    try {
      await onPindah(tujuan);
      onTutup();
    } catch (e) {
      setGalat(errorText(e));
    } finally {
      setSibuk(false);
    }
  }

  return (
    <Dialog
      judul={judul}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" onClick={kirim} disabled={sibuk || !tujuan}>
            {t("Pindahkan")}
          </Button>
        </>
      }
    >
      {pertama ? (
        <Isian label={t("Folder tujuan")} petunjuk={keterangan}>
          <PilihFolder nilai={tujuan} onUbah={setTujuan} pilihan="buka" sembunyikan={sembunyikan} />
        </Isian>
      ) : (
        <p className="text-sm text-muted">{t("Tidak ada folder lain yang bisa Anda buka.")}</p>
      )}

      <Galat pesan={galat} className="mt-2" />
    </Dialog>
  );
}
