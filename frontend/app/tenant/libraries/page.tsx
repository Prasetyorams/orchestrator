"use client";

import { useCallback, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { CloudUpload } from "lucide-react";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn } from "@/lib/utils";
import { DaftarLibrary } from "@/components/DaftarLibrary";
import { DialogUnggahLibrary } from "@/components/DialogUnggahLibrary";
import { JudulHalaman } from "@/components/HalamanFolder";
import { kelasIsian } from "@/components/Dialog";
import { Button } from "@/components/ui/primitives";

/**
 * Feed library seluruh penyewa (V20) — padanan feed Libraries di UiPath
 * Orchestrator. Satu untuk semua folder, seperti umpan paket. Izinnya izin
 * paket: library juga kode yang dijalankan robot.
 */
export default function LibraryPenyewa() {
  const { t } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();
  const [unggah, setUnggah] = useState(false);
  const [cari, setCari] = useState("");

  const tutup = useCallback(() => setUnggah(false), []);
  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["libraries"] });
  }, [klien]);

  return (
    <div>
      <JudulHalaman
        judul={t("Library")}
        konteks="tenant"
        aksi={
          boleh("packages.create") ? (
            <Button variant="primary" onClick={() => setUnggah(true)}>
              <CloudUpload size={14} className="mr-1.5" />
              {t("Unggah Library")}
            </Button>
          ) : undefined
        }
      >
        <input
          type="search"
          value={cari}
          onChange={(e) => setCari(e.target.value)}
          placeholder={t("Cari Id atau keterangan...")}
          aria-label={t("Cari Id atau keterangan...")}
          className={cn(kelasIsian, "w-64")}
        />
      </JudulHalaman>
      <DaftarLibrary cari={cari} bolehHapus={boleh("packages.delete")} />
      {unggah ? <DialogUnggahLibrary onTutup={tutup} onSelesai={segarkan} /> : null}
    </div>
  );
}
