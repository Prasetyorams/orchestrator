"use client";

import { useCallback, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, History, Trash2 } from "lucide-react";
import { OpenOrchestratorApi, errorText, unduh, type Package } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { kelompokkanPaket, type RingkasanPaket } from "@/lib/paket";
import { dateTimeOf, kunciUrutVersi, ukuranBerkas } from "@/lib/utils";
import { Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog } from "@/components/Dialog";

/**
 * Daftar paket: satu baris per PAKET dengan versi tertingginya.
 *
 * Dipakai dua tempat. Di folder: paket yang dipakai proses folder itu. Di
 * Tenant: seluruh umpan, dan hanya di sana versi lama boleh dihapus —
 * menghapus dari dalam folder mudah disangka "keluarkan dari folder ini",
 * padahal paketnya hilang untuk semua folder.
 */
export function DaftarPaket({ folderId, bolehHapus = false }: { folderId?: string | null; bolehHapus?: boolean }) {
  const { t } = useT();
  const klien = useQueryClient();

  const [dibuka, setDibuka] = useState<string | null>(null);
  const [galat, setGalat] = useState("");

  const paket = useQuery({
    queryKey: ["packages", folderId ?? "semua"],
    queryFn: () => OpenOrchestratorApi.packages(folderId),
  });

  // Satu baris per PAKET dengan versi tertingginya; API memberi satu baris per versi.
  const ringkasan = useMemo(() => kelompokkanPaket(paket.data ?? []), [paket.data]);

  // Dicari ulang dari data terkini, bukan disimpan sebagai objek: kalau
  // daftarnya disegarkan selagi dialog terbuka, yang tampil tetap yang baru.
  const detail = ringkasan.find((r) => r.name === dibuka) ?? null;

  // Stabil, supaya Dialog tidak menjalankan ulang efeknya tiap kali halaman
  // ini dirender ulang oleh penarikan data.
  const tutup = useCallback(() => setDibuka(null), []);

  const unduhVersi = useCallback(async (p: Package) => {
    setGalat("");
    try {
      await unduh(OpenOrchestratorApi.packageUrl(p.name, p.version), `${p.name}.${p.version}.zip`);
    } catch (e) {
      setGalat(errorText(e));
    }
  }, []);

  const hapusVersi = useCallback(
    async (p: Package) => {
      if (!window.confirm(t("Hapus {0} versi {1} dari umpan paket? Proses yang memakai versi ini tidak bisa dijalankan lagi.", p.name, p.version))) {
        return;
      }

      setGalat("");
      try {
        await OpenOrchestratorApi.deletePackage(p.name, p.version);
        klien.invalidateQueries({ queryKey: ["packages"] });
      } catch (e) {
        setGalat(errorText(e));
      }
    },
    [klien, t],
  );

  return (
    <div>
      <Galat pesan={galat || (paket.isError ? errorText(paket.error) : "")} className="mb-4" />

      <Card>
        <DataTable
          data={ringkasan}
          kunci={(r) => r.name}
          onBuka={(r) => setDibuka(r.name)}
          kosong={
            paket.isLoading
              ? "Memuat..."
              : folderId
                ? "Belum ada paket yang dipakai proses di folder ini."
                : "Belum ada paket yang diterbitkan. Terbitkan dari Studio: tab Design → grup OpenOrchestrator → Terbitkan."
          }
          kolom={[
            {
              judul: "Nama",
              sel: (r) => (
                <button
                  type="button"
                  onClick={() => setDibuka(r.name)}
                  className="font-medium text-ink hover:text-brand hover:underline"
                >
                  {r.name}
                </button>
              ),
              urut: (r) => r.name.toLowerCase(),
            },
            {
              judul: "Versi Terbaru",
              sel: (r) => <span className="font-medium tabular-nums">{r.terbaru.version}</span>,
              urut: (r) => kunciUrutVersi(r.terbaru.version),
            },
            {
              judul: "Jumlah Versi",
              sel: (r) => <span className="tabular-nums">{r.versi.length}</span>,
              urut: (r) => r.versi.length,
            },
            {
              judul: "Keterangan",
              sel: (r) => <span className="text-muted">{r.terbaru.description ?? "-"}</span>,
            },
            {
              judul: "Diterbitkan",
              sel: (r) => <span className="text-muted">{dateTimeOf(r.terbaru.publishedAt)}</span>,
              urut: (r) => r.terbaru.publishedAt,
            },
            {
              judul: "Diterbitkan Oleh",
              sel: (r) => <span className="text-muted">{r.terbaru.publishedBy ?? "-"}</span>,
              urut: (r) => r.terbaru.publishedBy,
            },
            {
              judul: "",
              sel: (r) => (
                <div className="flex justify-end gap-0.5">
                  <IconButton label={t("Unduh")} onClick={() => unduhVersi(r.terbaru)}>
                    <Download size={16} />
                  </IconButton>
                  <IconButton label={t("Riwayat versi")} onClick={() => setDibuka(r.name)}>
                    <History size={16} />
                  </IconButton>
                </div>
              ),
            },
          ]}
        />
      </Card>

      <p className="mt-3 text-xs text-muted">
        {t("Satu baris per paket, dengan versi tertingginya. Klik ganda untuk melihat semua versinya.")}
      </p>

      {detail ? (
        <DialogVersi
          paket={detail}
          onTutup={tutup}
          onUnduh={unduhVersi}
          onHapus={bolehHapus ? hapusVersi : undefined}
        />
      ) : null}
    </div>
  );
}

function DialogVersi({
  paket,
  onTutup,
  onUnduh,
  onHapus,
}: {
  paket: RingkasanPaket;
  onTutup: () => void;
  onUnduh: (p: Package) => void;
  onHapus?: (p: Package) => void;
}) {
  const { t } = useT();

  return (
    <Dialog judul={`${paket.name} · ${t("Riwayat versi")}`} terbuka onTutup={onTutup} lebar="max-w-3xl">
      <div className="max-h-[26rem] overflow-y-auto rounded-lg border border-line">
        <DataTable
          data={paket.versi}
          kunci={(v) => v.version}
          perHalaman={0}
          kolom={[
            {
              judul: "Versi",
              sel: (v) => (
                <span className="flex items-center gap-2">
                  <span className="font-medium tabular-nums">{v.version}</span>
                  {v === paket.terbaru ? (
                    <span className="rounded-full bg-ok/10 px-2 py-0.5 text-[11px] font-medium text-ok">
                      {t("Terbaru")}
                    </span>
                  ) : null}
                </span>
              ),
              urut: (v) => kunciUrutVersi(v.version),
            },
            {
              judul: "Diterbitkan",
              sel: (v) => <span className="text-muted">{dateTimeOf(v.publishedAt)}</span>,
              urut: (v) => v.publishedAt,
            },
            { judul: "Diterbitkan Oleh", sel: (v) => v.publishedBy ?? "-" },
            { judul: "Titik Masuk", sel: (v) => <span className="text-muted">{v.entryPoint ?? "-"}</span> },
            {
              judul: "Ukuran",
              sel: (v) => <span className="tabular-nums text-muted">{ukuranBerkas(v.sizeBytes)}</span>,
              urut: (v) => v.sizeBytes,
            },
            {
              judul: "",
              sel: (v) => (
                <div className="flex justify-end gap-0.5">
                  <Button variant="ghost" onClick={() => onUnduh(v)}>
                    {t("Unduh")}
                  </Button>
                  {onHapus ? (
                    <IconButton label={t("Hapus versi ini")} tone="danger" onClick={() => onHapus(v)}>
                      <Trash2 size={15} />
                    </IconButton>
                  ) : null}
                </div>
              ),
            },
          ]}
        />
      </div>
    </Dialog>
  );
}
