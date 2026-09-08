"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText, type Process } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog } from "@/components/Dialog";

export default function Proses() {
  const { t } = useT();
  const klien = useQueryClient();

  const [detail, setDetail] = useState<Process | null>(null);
  const [galat, setGalat] = useState("");

  const proses = useQuery({ queryKey: ["processes"], queryFn: ForgeHubApi.processes });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteProcess,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["processes"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  const jalankan = useMutation({
    mutationFn: (nama: string) => ForgeHubApi.startJob({ processName: nama, source: "Dashboard" }),
    onSuccess: () => {
      klien.invalidateQueries({ queryKey: ["jobs"] });
      klien.invalidateQueries({ queryKey: ["processes"] });
    },
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-ink">{t("Proses")}</h1>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <p className="text-sm text-muted">
        Klik ganda pada barisnya untuk melihat riwayat jalan dan catatannya.
      </p>

      <Card>
        <DataTable
          data={proses.data ?? []}
          kunci={(p) => p.name}
          onBuka={setDetail}
          kolom={[
            { judul: "Nama", sel: (p) => <span className="font-medium">{p.name}</span>, urut: (p) => p.name },
            {
              judul: "Paket",
              sel: (p) =>
                p.packageName ? (
                  <span className="text-muted">
                    {p.packageName} <span className="tabular-nums">{p.packageVersion}</span>
                  </span>
                ) : (
                  <span className="text-muted">-</span>
                ),
              urut: (p) => p.packageVersion,
            },
            { judul: "Lingkungan", sel: (p) => p.environment ?? "-", urut: (p) => p.environment },
            {
              judul: "Pekerjaan",
              sel: (p) => <span className="tabular-nums">{p.jobCount}</span>,
              urut: (p) => p.jobCount,
            },
            {
              judul: "Jalan Terakhir",
              sel: (p) => <span className="text-muted">{dateTimeOf(p.lastRunAt)}</span>,
              urut: (p) => p.lastRunAt,
            },
            {
              judul: "",
              sel: (p) => (
                <div className="flex justify-end gap-1.5">
                  <Button variant="ghost" onClick={() => jalankan.mutate(p.name)}>
                    {t("Jalankan")}
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${p.name}"?`)) hapus.mutate(p.name);
                    }}
                  >
                    {t("Hapus")}
                  </Button>
                </div>
              ),
            },
          ]}
        />
      </Card>

      <DialogProses proses={detail} onTutup={() => setDetail(null)} />
    </div>
  );
}

/**
 * Detail proses: riwayat jalannya DAN catatannya, di satu tempat.
 *
 * Ini yang diminta — log berada di dalam prosesnya. Halaman Catatan yang
 * terpisah tetap ada untuk pencarian menyeluruh, tapi orang yang sedang
 * menyelidiki satu proses tidak seharusnya menyaring seluruh catatan lebih
 * dulu untuk sampai ke sini.
 */
function DialogProses({ proses, onTutup }: { proses: Process | null; onTutup: () => void }) {
  const { t } = useT();
  const [tab, setTab] = useState<"jalan" | "catatan">("jalan");

  const jobs = useQuery({
    queryKey: ["jobs", "process", proses?.name],
    queryFn: () => ForgeHubApi.jobs({ process: proses!.name, limit: 200 }),
    enabled: !!proses,
  });

  const log = useQuery({
    queryKey: ["logs", "process", proses?.name],
    queryFn: () => ForgeHubApi.logs({ process: proses!.name, limit: 500 }),
    enabled: !!proses && tab === "catatan",
  });

  if (!proses) return null;

  return (
    <Dialog judul={proses.name} terbuka onTutup={onTutup} lebar="max-w-4xl">
      <div className="mb-4 flex gap-2 rounded-lg bg-slate-100 p-1">
        <button
          type="button"
          onClick={() => setTab("jalan")}
          className={`flex-1 rounded-md px-3 py-1.5 text-sm ${tab === "jalan" ? "bg-card font-medium shadow-sm" : "text-muted"}`}
        >
          {t("Pekerjaan")} ({proses.jobCount})
        </button>
        <button
          type="button"
          onClick={() => setTab("catatan")}
          className={`flex-1 rounded-md px-3 py-1.5 text-sm ${tab === "catatan" ? "bg-card font-medium shadow-sm" : "text-muted"}`}
        >
          {t("Catatan")}
        </button>
      </div>

      {tab === "jalan" ? (
        <div className="max-h-[26rem] overflow-y-auto rounded-lg border border-line">
          <DataTable
            data={jobs.data ?? []}
            kunci={(j) => j.id}
            perHalaman={0}
            kolom={[
              { judul: "Keadaan", sel: (j) => <Badge value={j.state} /> },
              { judul: "Robot", sel: (j) => j.robotName ?? "-" },
              { judul: "Sumber", sel: (j) => <span className="text-muted">{j.source}</span> },
              { judul: "Dimulai", sel: (j) => <span className="text-muted">{dateTimeOf(j.startedAt)}</span> },
              { judul: "Selesai", sel: (j) => <span className="text-muted">{dateTimeOf(j.endedAt)}</span> },
              { judul: "Info", sel: (j) => <span className="text-muted">{j.info ?? "-"}</span> },
            ]}
          />
        </div>
      ) : (
        <div className="max-h-[26rem] overflow-y-auto rounded-lg border border-line">
          {log.data?.length ? (
            log.data.map((l) => (
              <div key={l.id} className="flex gap-3 border-b border-line/70 px-3 py-1.5 text-xs last:border-0">
                <span className="w-32 shrink-0 tabular-nums text-muted">{dateTimeOf(l.loggedAt)}</span>
                <span className="w-14 shrink-0 font-medium">{l.level}</span>
                <span className="w-28 shrink-0 truncate text-muted">{l.robotName ?? "-"}</span>
                <span className="break-all">{l.message}</span>
              </div>
            ))
          ) : (
            <p className="px-3 py-8 text-center text-sm text-muted">
              {log.isFetching ? t("Memuat...") : t("Belum ada data.")}
            </p>
          )}
        </div>
      )}
    </Dialog>
  );
}
