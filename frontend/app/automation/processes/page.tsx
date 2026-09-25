"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Play, Trash2 } from "lucide-react";
import { ForgeHubApi, errorText, type Process } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Card, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog } from "@/components/Dialog";

export default function Proses() {
  const { t } = useT();
  const klien = useQueryClient();

  const [detail, setDetail] = useState<Process | null>(null);
  const [galat, setGalat] = useState("");

  const proses = useQuery({
    queryKey: ["processes"],
    queryFn: ForgeHubApi.processes,
    // Tombol Jalankan harus hidup lagi SENDIRI begitu pekerjaannya selesai,
    // tanpa orang menekan muat ulang. Selama ada yang berjalan, daftar
    // disegarkan tiap 3 detik; selebihnya cukup tiap 15 detik.
    refetchInterval: (q) => ((q.state.data ?? []).some((p) => p.activeJobs > 0) ? 3_000 : 15_000),
  });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteProcess,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["processes"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  const jalankan = useMutation({
    mutationFn: (nama: string) => ForgeHubApi.startJob({ processName: nama, source: "Dashboard" }),
    onMutate: (nama) => {
      setGalat("");

      // Tombolnya langsung mati, tidak menunggu penyegaran berikutnya. Tanpa
      // ini ada jeda beberapa detik saat tombol masih hidup, dan klik kedua
      // di jeda itu menjadwalkan pekerjaan kedua.
      klien.setQueryData<Process[]>(["processes"], (lama) =>
        lama?.map((p) =>
          p.name === nama ? { ...p, activeJobs: p.activeJobs + 1, activeState: p.activeState ?? "PENDING" } : p,
        ),
      );
    },
    onError: (e) => setGalat(errorText(e)),
    onSettled: () => {
      klien.invalidateQueries({ queryKey: ["jobs"] });
      klien.invalidateQueries({ queryKey: ["processes"] });
      klien.invalidateQueries({ queryKey: ["dashboard"] });
    },
  });

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-ink">{t("Proses")}</h1>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <p className="text-sm text-muted">
        {t("Klik ganda pada barisnya untuk melihat riwayat jalan dan catatannya.")}
      </p>

      <Card>
        <DataTable
          data={proses.data ?? []}
          kunci={(p) => p.name}
          onBuka={setDetail}
          kolom={[
            { judul: "Nama", sel: (p) => <span className="font-medium">{p.name}</span>, urut: (p) => p.name },
            {
              judul: "Paket|satu",
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
            { judul: "Lingkungan|satu", sel: (p) => p.environment ?? "-", urut: (p) => p.environment },
            {
              judul: "Keadaan",
              sel: (p) => (p.activeState ? <Badge value={p.activeState} /> : <span className="text-muted">-</span>),
              urut: (p) => p.activeState,
            },
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
              sel: (p) => {
                // Mati selama proses ini masih punya pekerjaan yang belum
                // selesai — menunggu robot, berjalan, atau sedang dihentikan.
                const berjalan = p.activeJobs > 0 || (jalankan.isPending && jalankan.variables === p.name);

                return (
                  <div className="flex justify-end gap-0.5">
                    <IconButton
                      label={
                        berjalan
                          ? t("Sedang berjalan ({0}). Bisa dijalankan lagi setelah selesai.", p.activeState ?? "PENDING")
                          : t("Jalankan")
                      }
                      tone="ok"
                      disabled={berjalan}
                      onClick={() => jalankan.mutate(p.name)}
                    >
                      <Play size={16} fill="currentColor" />
                    </IconButton>
                    <IconButton
                      label={t("Hapus")}
                      tone="danger"
                      onClick={() => {
                        if (window.confirm(`${t("Yakin menghapus")} "${p.name}"?`)) hapus.mutate(p.name);
                      }}
                    >
                      <Trash2 size={16} />
                    </IconButton>
                  </div>
                );
              },
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
  const { t, tp } = useT();
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
              { judul: "Robot|satu", sel: (j) => j.robotName ?? "-" },
              { judul: "Sumber", sel: (j) => <span className="text-muted">{j.source}</span> },
              { judul: "Dimulai", sel: (j) => <span className="text-muted">{dateTimeOf(j.startedAt)}</span> },
              { judul: "Selesai", sel: (j) => <span className="text-muted">{dateTimeOf(j.endedAt)}</span> },
              { judul: "Info", sel: (j) => <span className="text-muted">{j.info ? tp(j.info) : "-"}</span> },
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
                <span className="break-all">{tp(l.message)}</span>
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
