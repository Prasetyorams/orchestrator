"use client";

import Link from "next/link";
import { useT } from "@/lib/i18n";
import { DaftarPaket } from "@/components/DaftarPaket";
import { PerluFolder } from "@/components/HalamanFolder";

/**
 * Paket di dalam folder: yang DIPAKAI proses folder ini.
 *
 * Paket sendiri tidak tinggal di folder — umpannya satu untuk seluruh
 * penyewa, seperti umpan penyewa di Orchestrator — jadi daftar lengkapnya ada
 * di Tenant › Paket.
 */
export default function PaketFolder() {
  const { t } = useT();

  return (
    <PerluFolder>
      {(folder) => (
        <div>
          <p className="mb-4 text-sm text-muted">
            {t("Paket yang dipakai proses di folder ini.")}{" "}
            <Link href="/tenant/packages" className="font-medium text-brand hover:underline">
              {t("Lihat seluruh umpan paket")}
            </Link>
          </p>
          <DaftarPaket folderId={folder.id} />
        </div>
      )}
    </PerluFolder>
  );
}
