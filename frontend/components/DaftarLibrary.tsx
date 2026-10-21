"use client";

import { useCallback, useMemo, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, History, Trash2 } from "lucide-react";
import { OpenOrchestratorApi, errorText, unduh, type Library } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { kelompokkanLibrary, type RingkasanLibrary } from "@/lib/paket";
import { cn, dateTimeOf, kunciUrutVersi, ukuranBerkas } from "@/lib/utils";
import { Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog } from "@/components/Dialog";

/** "Id versi, Id versi" dari dependensi .nuspec. */
function teksDependensi(l: Library): string {
  const semua = Object.entries(l.dependencies ?? {}).map(([id, versi]) => `${id} ${versi}`.trim());
  return semua.length ? semua.join(", ") : "-";
}

/**
 * Feed library (V20): satu baris per library dengan versi tertingginya, dan
 * riwayat versinya di dialog — sama dengan daftar paket.
 */
export function DaftarLibrary({ cari, bolehHapus = false }: { cari: string; bolehHapus?: boolean }) {
  const { t } = useT();
  const klien = useQueryClient();

  const [dibuka, setDibuka] = useState<string | null>(null);
  const [galat, setGalat] = useState("");

  const library = useQuery({
    queryKey: ["libraries", cari.trim()],
    queryFn: () => OpenOrchestratorApi.libraries(cari),
    placeholderData: keepPreviousData,
  });

  const ringkasan = useMemo(() => kelompokkanLibrary(library.data ?? []), [library.data]);
  const detail = ringkasan.find((r) => r.name === dibuka) ?? null;
  const tutup = useCallback(() => setDibuka(null), []);

  const unduhVersi = useCallback(async (l: Library) => {
    setGalat("");
    try {
      await unduh(OpenOrchestratorApi.libraryUrl(l.id, l.version), `${l.id}.${l.version}.nupkg`);
    } catch (e) {
      setGalat(errorText(e));
    }
  }, []);

  const hapusVersi = useCallback(
    async (l: Library) => {
      if (!window.confirm(t("Hapus {0} versi {1} dari feed library? Versi yang masih dibutuhkan proses atau sedang dipakai robot tidak bisa dihapus.", l.id, l.version))) {
        return;
      }

      setGalat("");
      try {
        await OpenOrchestratorApi.deleteLibrary(l.id, l.version);
        klien.invalidateQueries({ queryKey: ["libraries"] });
      } catch (e) {
        setGalat(errorText(e));
      }
    },
    [klien, t],
  );

  return (
    <div>
      <Galat pesan={galat || (library.isError ? errorText(library.error) : "")} className="mb-4" />

      <Card className={cn("transition-opacity", library.isPlaceholderData && "opacity-60")}>
        <DataTable
          data={ringkasan}
          kunci={(r) => r.name.toLowerCase()}
          onBuka={(r) => setDibuka(r.name)}
          kosong={
            library.isLoading
              ? "Memuat..."
              : cari.trim()
                ? "Tidak ada library yang cocok."
                : "Belum ada library. Terbitkan proyek Library dari OpenStudio, atau Unggah Library di sini."
          }
          kolom={[
            {
              judul: "Id",
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
        {t("Satu baris per library, dengan versi tertingginya. Proses yang menyebut library ini di dependensinya mendapat versi persis yang disebut; robot memasangnya sebelum job berjalan.")}
      </p>

      {detail ? (
        <DialogVersi
          library={detail}
          onTutup={tutup}
          onUnduh={unduhVersi}
          onHapus={bolehHapus ? hapusVersi : undefined}
        />
      ) : null}
    </div>
  );
}

function DialogVersi({
  library,
  onTutup,
  onUnduh,
  onHapus,
}: {
  library: RingkasanLibrary;
  onTutup: () => void;
  onUnduh: (l: Library) => void;
  onHapus?: (l: Library) => void;
}) {
  const { t } = useT();

  return (
    <Dialog judul={`${library.name} · ${t("Riwayat versi")}`} terbuka onTutup={onTutup} lebar="max-w-4xl">
      <div className="max-h-[26rem] overflow-y-auto rounded-lg border border-line">
        <DataTable
          data={library.versi}
          kunci={(v) => v.version}
          perHalaman={0}
          kolom={[
            {
              judul: "Versi",
              sel: (v) => (
                <span className="flex items-center gap-2">
                  <span className="font-medium tabular-nums">{v.version}</span>
                  {v === library.terbaru ? (
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
            { judul: "Dependensi", sel: (v) => <span className="text-muted">{teksDependensi(v)}</span> },
            {
              judul: "Ukuran",
              sel: (v) => <span className="tabular-nums text-muted">{ukuranBerkas(v.sizeBytes)}</span>,
              urut: (v) => v.sizeBytes,
            },
            {
              judul: "SHA-256",
              sel: (v) => (
                <span title={v.sha256} className="font-mono text-xs text-muted">
                  {v.sha256.slice(0, 12)}…
                </span>
              ),
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
