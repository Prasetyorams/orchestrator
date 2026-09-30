"use client";

import type { ReactNode } from "react";
import { useT } from "@/lib/i18n";
import { Button, Galat } from "@/components/ui/primitives";
import { Dialog } from "@/components/Dialog";

/**
 * "Yakin?" untuk aksi yang tidak bisa dibatalkan — dengan penjelasan APA
 * yang akan terjadi, bukan hanya pertanyaannya.
 *
 * window.confirm tidak cukup di sini: teksnya tidak bisa diformat, tombolnya
 * selalu "OK", dan galat dari server tidak punya tempat untuk tampil — dialog
 * tertutup dan kegagalannya hilang.
 */
export function DialogKonfirmasi({
  judul,
  children,
  label,
  bahaya = false,
  sibuk = false,
  galat,
  onYa,
  onTutup,
}: {
  judul: string;
  children: ReactNode;
  /** Tulisan tombol yang menjalankan aksinya, mis. "Matikan". */
  label: string;
  bahaya?: boolean;
  sibuk?: boolean;
  galat?: string;
  onYa: () => void;
  onTutup: () => void;
}) {
  const { t } = useT();

  return (
    <Dialog
      judul={judul}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant={bahaya ? "danger" : "primary"} onClick={onYa} disabled={sibuk}>
            {sibuk ? t("Memproses...") : label}
          </Button>
        </>
      }
    >
      <div className="space-y-2 text-sm text-ink">{children}</div>
      <Galat pesan={galat} className="mt-3" />
    </Dialog>
  );
}
