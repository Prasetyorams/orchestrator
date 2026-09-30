"use client";

import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { X } from "lucide-react";
import { OpenOrchestratorApi, type FolderNode } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { kelasIsian } from "@/components/Dialog";
import { PilihTingkat } from "@/components/PilihTingkat";
import { BilahAlat, PerluFolder } from "@/components/HalamanFolder";

// Tanpa TRACE dan DEBUG: OpenOrchestrator tidak menyimpan maupun menampilkan tingkat
// rincian (lihat LogLevel.rincian di backend), jadi pilihan itu selalu kosong.
// WARN juga membawa baris WARNING — satu tingkat dengan dua ejaan.
const TINGKAT = ["INFO", "WARN", "ERROR", "FATAL"];

export default function Catatan() {
  return <PerluFolder>{(folder) => <IsiCatatan folder={folder} />}</PerluFolder>;
}

function IsiCatatan({ folder }: { folder: FolderNode }) {
  const { t, tp } = useT();

  const [tingkat, setTingkat] = useState<string[]>([]);
  const [proses, setProses] = useState("");
  // Satu pekerjaan saja — dari "Lihat Log Job Ini" di menu halaman Pekerjaan.
  const [jobId, setJobId] = useState("");
  const [ikuti, setIkuti] = useState(true);

  // ?jobId= dan ?process= dibaca sekali saat dipasang, lewat window:
  // useSearchParams menuntut pembungkus Suspense di seluruh halaman hanya
  // untuk nilai awal ini.
  useEffect(() => {
    const cari = new URLSearchParams(window.location.search);
    setJobId(cari.get("jobId") ?? "");
    setProses(cari.get("process") ?? "");
  }, []);

  const daftarProses = useQuery({
    queryKey: ["processes", folder.id],
    queryFn: () => OpenOrchestratorApi.processes(folder.id),
  });

  // Nama proses job yang disaring, untuk label saringannya.
  const job = useQuery({
    queryKey: ["job", jobId],
    queryFn: () => OpenOrchestratorApi.job(jobId),
    enabled: !!jobId,
  });

  const log = useQuery({
    queryKey: ["logs", folder.id, tingkat, proses, jobId],
    queryFn: () =>
      OpenOrchestratorApi.logs({
        level: tingkat,
        process: jobId ? undefined : proses || undefined,
        jobId: jobId || undefined,
        folderId: jobId ? undefined : folder.id,
        limit: 500,
      }),
    // Hanya menyegarkan sendiri saat "ikuti" menyala. Tabel yang melompat ke
    // baris terbaru tiap tiga detik membuat orang yang sedang membaca satu
    // baris kehilangan tempatnya.
    refetchInterval: ikuti ? 3_000 : false,
    // Mengganti saringan tidak mengosongkan tabel lebih dulu.
    placeholderData: keepPreviousData,
  });

  function hapusSaringanJob() {
    setJobId("");
    // Alamatnya ikut dibersihkan: memuat ulang halaman tidak boleh
    // menghidupkan kembali saringan yang sudah dilepas.
    window.history.replaceState(null, "", window.location.pathname);
  }

  return (
    <div>
      <BilahAlat aksi={<Button onClick={() => log.refetch()}>{t("Muat ulang")}</Button>}>
        <PilihTingkat pilihan={TINGKAT} terpilih={tingkat} onUbah={setTingkat} />

        {jobId ? (
          <span className="inline-flex items-center gap-1.5 rounded-lg border border-brandLine bg-brandSoft py-1.5 pl-3 pr-1.5 text-sm text-ink">
            {t("Pekerjaan|satu")}: <span className="font-medium">{job.data?.processName ?? "…"}</span>
            <code className="text-xs text-muted">{jobId.slice(0, 8)}</code>
            <button
              type="button"
              onClick={hapusSaringanJob}
              aria-label={t("Hapus saringan pekerjaan")}
              title={t("Hapus saringan pekerjaan")}
              className="rounded p-0.5 text-muted transition hover:bg-slate-100 hover:text-ink"
            >
              <X size={14} />
            </button>
          </span>
        ) : (
          <select
            value={proses}
            onChange={(e) => setProses(e.target.value)}
            className={cn(kelasIsian, "w-48")}
            aria-label={t("Proses|satu")}
          >
            <option value="">{t("Semua proses")}</option>
            {(daftarProses.data ?? []).map((p) => (
              <option key={p.name} value={p.name}>
                {p.name}
              </option>
            ))}
            {/* Proses dari alamat yang tidak ada di folder ini tetap bisa
                dipilih — kalau tidak, saringannya diam-diam hilang. */}
            {proses && daftarProses.isSuccess && !daftarProses.data.some((p) => p.name === proses) ? (
              <option value={proses}>{proses}</option>
            ) : null}
          </select>
        )}

        <label className="flex items-center gap-2 text-sm text-muted">
          <input
            type="checkbox"
            checked={ikuti}
            onChange={(e) => setIkuti(e.target.checked)}
            className="h-4 w-4 rounded border-line"
          />
          {t("Ikuti otomatis")}
        </label>
      </BilahAlat>

      <Card className={cn("transition-opacity", log.isPlaceholderData && "opacity-60")}>
        <DataTable
          data={log.data ?? []}
          kunci={(l) => String(l.id)}
          perHalaman={50}
          kosong={log.isLoading ? "Memuat..." : jobId ? "Belum ada catatan untuk pekerjaan ini." : "Belum ada catatan di folder ini."}
          kolom={[
            {
              judul: "Waktu",
              sel: (l) => <span className="whitespace-nowrap tabular-nums text-muted">{dateTimeOf(l.loggedAt)}</span>,
              urut: (l) => l.id,
            },
            { judul: "Tingkat", sel: (l) => <Badge value={l.level} />, urut: (l) => l.level },
            {
              judul: "Robot|satu",
              sel: (l) => <span className="text-muted">{l.robotName ?? "-"}</span>,
              urut: (l) => l.robotName,
            },
            {
              judul: "Proses|satu",
              sel: (l) => <span className="text-muted">{l.processName ?? "-"}</span>,
              urut: (l) => l.processName,
            },
            { judul: "Pesan", sel: (l) => <span className="break-all">{tp(l.message)}</span> },
          ]}
        />
      </Card>

      <p className="mt-3 text-xs text-muted">
        {t(
          "Paling banyak 500 baris terbaru dari pekerjaan di folder ini. Untuk catatan satu proses atau satu pekerjaan, buka detailnya dari halaman Proses atau Pekerjaan.",
        )}
      </p>
    </div>
  );
}
