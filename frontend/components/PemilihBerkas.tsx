"use client";

import { useRef, useState } from "react";
import { CloudUpload, FileArchive, X } from "lucide-react";
import { useT } from "@/lib/i18n";
import { cn, ukuranBerkas } from "@/lib/utils";
import { IconButton } from "@/components/ui/primitives";

/**
 * Pilih satu berkas untuk diunggah: seret ke kotaknya atau klik untuk
 * memilih, lalu berkas yang terpilih dan kemajuan kirimannya. Dipakai Unggah
 * Paket (V17) dan Unggah Library (V20).
 *
 * @param kemajuan null: tidak sedang mengunggah; 0..1: bagian yang sudah terkirim
 * @param memeriksa teks sesudah seluruh berkas terkirim, selagi server membaca dan menyimpannya
 */
export function PemilihBerkas({
  accept,
  judul,
  petunjuk,
  berkas,
  kemajuan,
  memeriksa,
  onPilih,
  onHapus,
}: {
  accept: string;
  judul: string;
  petunjuk: string;
  berkas: File | null;
  kemajuan: number | null;
  memeriksa: string;
  onPilih: (f: File | null | undefined) => void;
  onHapus: () => void;
}) {
  const { t } = useT();
  const pemilih = useRef<HTMLInputElement>(null);
  const [diseret, setDiseret] = useState(false);

  const mengunggah = kemajuan !== null;
  const persen = Math.round((kemajuan ?? 0) * 100);

  return (
    <>
      <div
        role="button"
        tabIndex={0}
        aria-disabled={mengunggah}
        onClick={() => !mengunggah && pemilih.current?.click()}
        onKeyDown={(e) => {
          if ((e.key === "Enter" || e.key === " ") && !mengunggah) {
            e.preventDefault();
            pemilih.current?.click();
          }
        }}
        onDragOver={(e) => {
          e.preventDefault();
          if (!mengunggah) setDiseret(true);
        }}
        onDragLeave={() => setDiseret(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDiseret(false);
          if (!mengunggah) onPilih(e.dataTransfer.files?.[0]);
        }}
        className={cn(
          "flex flex-col items-center justify-center rounded-lg border-2 border-dashed px-4 py-7 text-center transition",
          diseret ? "border-brand bg-brandSoft" : "border-line hover:border-brandLine",
          mengunggah ? "cursor-not-allowed opacity-60" : "cursor-pointer",
        )}
      >
        <CloudUpload size={28} className="mb-2 text-muted" />
        <p className="text-sm font-medium text-ink">{judul}</p>
        <p className="text-xs text-muted">{petunjuk}</p>
        <input
          ref={pemilih}
          type="file"
          accept={accept}
          className="hidden"
          onChange={(e) => {
            onPilih(e.target.files?.[0]);
            // Dikosongkan supaya berkas yang SAMA bisa dipilih lagi sesudah diperbaiki.
            e.target.value = "";
          }}
        />
      </div>

      {berkas ? (
        <div className="mt-3 flex items-center gap-3 rounded-lg border border-line px-3 py-2">
          <FileArchive size={18} className="shrink-0 text-muted" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-ink" title={berkas.name}>
              {berkas.name}
            </p>
            <p className="text-xs tabular-nums text-muted">{ukuranBerkas(berkas.size)}</p>
          </div>
          {!mengunggah ? (
            <IconButton label={t("Hapus pilihan")} onClick={onHapus}>
              <X size={15} />
            </IconButton>
          ) : null}
        </div>
      ) : null}

      {mengunggah ? (
        <div className="mt-3">
          <div
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={persen}
            className="h-2 overflow-hidden rounded-full bg-line"
          >
            <div className="h-full rounded-full bg-brand transition-all" style={{ width: `${persen}%` }} />
          </div>
          <p className="mt-1 text-xs text-muted">
            {kemajuan >= 1 ? memeriksa : t("Mengunggah... {0}%", persen)}
          </p>
        </div>
      ) : null}
    </>
  );
}
