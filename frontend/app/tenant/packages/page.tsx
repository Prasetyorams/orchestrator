"use client";

import { useCallback, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { CloudUpload } from "lucide-react";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { DaftarPaket } from "@/components/DaftarPaket";
import { DialogUnggahPaket } from "@/components/DialogUnggahPaket";
import { JudulHalaman } from "@/components/HalamanFolder";
import { Button } from "@/components/ui/primitives";

/**
 * Umpan paket seluruh penyewa — satu untuk semua folder. Proses di folder
 * mana pun memilih paketnya dari sini.
 *
 * Dua jalan masuk ke umpan yang sama: Terbitkan dari OpenStudio, dan Unggah
 * Paket di sini (V17) untuk paket yang sudah jadi.
 */
export default function PaketPenyewa() {
  const { t } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();
  const [unggah, setUnggah] = useState(false);

  const tutup = useCallback(() => setUnggah(false), []);
  // Proses ikut berubah: unggahan membuat proses baru atau memindah versinya.
  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["packages"] });
    klien.invalidateQueries({ queryKey: ["processes"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }, [klien]);

  return (
    <div>
      <JudulHalaman
        judul={t("Paket")}
        konteks="tenant"
        aksi={
          boleh("packages.create") ? (
            <Button variant="primary" onClick={() => setUnggah(true)}>
              <CloudUpload size={14} className="mr-1.5" />
              {t("Unggah Paket")}
            </Button>
          ) : undefined
        }
      />
      <DaftarPaket bolehHapus={boleh("packages.delete")} />
      {unggah ? <DialogUnggahPaket onTutup={tutup} onSelesai={segarkan} /> : null}
    </div>
  );
}
