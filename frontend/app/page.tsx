"use client";

import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi, type HistoryDay } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Card, CardBody, CardHeader, StatCard } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";

export default function Dasbor() {
  const { t } = useT();

  // Disegarkan tiap 5 detik. Dasbor yang tidak bergerak selama satu menit
  // terlihat sama dengan dasbor yang rusak.
  const d = useQuery({
    queryKey: ["dashboard"],
    queryFn: ForgeHubApi.dashboard,
    refetchInterval: 5_000,
  });

  const riwayat = useQuery({
    queryKey: ["history"],
    queryFn: ForgeHubApi.history,
    refetchInterval: 60_000,
  });

  if (d.isLoading) return <p className="text-sm text-muted">{t("Memuat...")}</p>;

  if (d.isError || !d.data) {
    return <p className="text-sm text-danger">Tidak bisa mengambil data dasbor.</p>;
  }

  const x = d.data;

  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard
          label={t("Robot Aktif")}
          value={x.robots.available + x.robots.busy}
          hint={`${x.robots.total} ${t("terdaftar")} · ${x.robots.disconnected} ${t("terputus")}`}
          tone={x.robots.available + x.robots.busy > 0 ? "ok" : "default"}
        />
        <StatCard
          label={t("Pekerjaan Berjalan")}
          value={x.jobs.running}
          hint={`${x.jobs.pending} ${t("menunggu")}`}
          tone={x.jobs.running > 0 ? "info" : "default"}
        />
        <StatCard label={t("Berhasil Hari Ini")} value={x.jobs.successfulToday} tone="ok" />
        <StatCard
          label={t("Gagal Hari Ini")}
          value={x.jobs.faultedToday}
          tone={x.jobs.faultedToday > 0 ? "danger" : "default"}
        />
        <StatCard
          label={t("Tingkat Keberhasilan")}
          value={`${x.successRate}%`}
          hint={`${x.jobs.totalToday} ${t("pekerjaan hari ini")}`}
          tone={x.successRate >= 90 ? "ok" : x.successRate >= 70 ? "warn" : "danger"}
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title={t("Sedang Berjalan")} />
          <DataTable
            data={x.jobsInProgress}
            kunci={(j) => j.id}
            perHalaman={0}
            kosong="Tidak ada pekerjaan yang sedang berjalan."
            kolom={[
              { judul: "Proses", sel: (j) => <span className="font-medium">{j.processName}</span> },
              { judul: "Robot", sel: (j) => j.robotName ?? "-" },
              { judul: "Keadaan", sel: (j) => <Badge value={j.state} /> },
              {
                judul: "Kemajuan",
                sel: (j) => (
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-100">
                      <div className="h-full bg-info" style={{ width: `${j.progress}%` }} />
                    </div>
                    <span className="tabular-nums text-xs text-muted">{j.progress}%</span>
                  </div>
                ),
              },
              { judul: "Dibuat", sel: (j) => <span className="text-muted">{dateTimeOf(j.createdAt)}</span> },
            ]}
          />
        </Card>

        <Card>
          <CardHeader title={t("Riwayat 14 Hari")} />
          <CardBody>
            <Grafik data={riwayat.data ?? []} />
          </CardBody>
        </Card>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title={t("Robot")} />
          <DataTable
            data={x.activeRobots}
            kunci={(r) => r.name}
            perHalaman={0}
            kolom={[
              { judul: "Nama", sel: (r) => <span className="font-medium">{r.name}</span> },
              { judul: "Status", sel: (r) => <Badge value={r.status} /> },
              {
                judul: "CPU / Memori",
                sel: (r) => (
                  <span className="tabular-nums text-muted">
                    {Math.round(r.cpuPercent)}% · {Math.round(r.memoryMb)} MB
                  </span>
                ),
              },
              { judul: "Denyut", sel: (r) => <span className="text-muted">{dateTimeOf(r.lastHeartbeatAt)}</span> },
            ]}
          />
        </Card>

        <Card>
          <CardHeader title={t("Pemicu Berikutnya")} />
          <DataTable
            data={x.upcomingTriggers}
            kunci={(p) => p.name}
            perHalaman={0}
            kosong="Tidak ada pemicu yang aktif."
            kolom={[
              { judul: "Nama", sel: (p) => <span className="font-medium">{p.name}</span> },
              { judul: "Proses", sel: (p) => p.processName },
              {
                judul: "Jadwal",
                sel: (p) =>
                  p.cron ? (
                    <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">{p.cron}</code>
                  ) : (
                    <span className="text-muted">tiap {p.intervalMinutes} menit</span>
                  ),
              },
              { judul: "Jalan Berikutnya", sel: (p) => <span className="text-muted">{dateTimeOf(p.nextRunAt)}</span> },
            ]}
          />
        </Card>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title={t("Ringkasan Antrean")} />
          <DataTable
            data={x.queueSummary}
            kunci={(q) => q.name}
            perHalaman={0}
            kolom={[
              { judul: "Nama", sel: (q) => <span className="font-medium">{q.name}</span> },
              { judul: "Baru", sel: (q) => <span className="tabular-nums">{q.newCount}</span> },
              { judul: "Diproses", sel: (q) => <span className="tabular-nums">{q.inProgressCount}</span> },
              { judul: "Berhasil", sel: (q) => <span className="tabular-nums text-ok">{q.successfulCount}</span> },
              {
                judul: "Gagal",
                sel: (q) => (
                  <span className={q.failedCount > 0 ? "tabular-nums text-danger" : "tabular-nums"}>
                    {q.failedCount}
                  </span>
                ),
              },
            ]}
          />
        </Card>

        <Card>
          <CardHeader title={t("Peringatan Terbaru")} />
          <DataTable
            data={x.recentAlerts}
            kunci={(a) => String(a.id)}
            perHalaman={0}
            kolom={[
              { judul: "Tingkat", sel: (a) => <Badge value={a.severity.toUpperCase()} /> },
              {
                judul: "Pesan",
                sel: (a) => (
                  <div>
                    <p className="font-medium text-ink">{a.title}</p>
                    {a.message ? <p className="text-xs text-muted">{a.message}</p> : null}
                  </div>
                ),
              },
              { judul: "Waktu", sel: (a) => <span className="text-muted">{dateTimeOf(a.createdAt)}</span> },
            ]}
          />
        </Card>
      </div>
    </div>
  );
}

/**
 * Grafik batang 14 hari, ditulis dengan div biasa.
 *
 * Tanpa pustaka grafik: yang dibutuhkan hanya dua batang per hari, dan sebuah
 * pustaka bagan menambah ratusan kilobita ke setiap pemuatan halaman untuk
 * sesuatu yang muat dalam tiga puluh baris.
 */
function Grafik({ data }: { data: HistoryDay[] }) {
  const { t } = useT();

  if (data.length === 0) return <p className="text-sm text-muted">{t("Belum ada data.")}</p>;

  // Minimal 1 supaya pembagi tidak nol pada rentang yang seluruhnya kosong.
  const puncak = Math.max(1, ...data.map((d) => d.successful + d.faulted));

  return (
    <div>
      <div className="flex h-40 items-end gap-1">
        {data.map((d) => {
          const total = d.successful + d.faulted;

          return (
            <div
              key={d.day}
              // h-full WAJIB ada.
              //
              // items-end pada barisnya membuat tiap kolom menyusut ke isinya,
              // dan tinggi persen pada batang di dalamnya lalu tidak punya
              // acuan — hasilnya kolom setinggi 1px dan grafik yang kosong
              // sama sekali, tanpa galat apa pun.
              className="group relative flex h-full flex-1 flex-col justify-end gap-px"
              title={`${d.day}: ${d.successful} berhasil, ${d.faulted} gagal`}
            >
              {d.faulted > 0 ? (
                <div
                  className="w-full rounded-t bg-danger/70"
                  style={{ height: `${(d.faulted / puncak) * 100}%` }}
                />
              ) : null}
              {d.successful > 0 ? (
                <div
                  className="w-full bg-ok/70"
                  style={{ height: `${(d.successful / puncak) * 100}%` }}
                />
              ) : null}
              {total === 0 ? <div className="h-px w-full bg-line" /> : null}
            </div>
          );
        })}
      </div>

      <div className="mt-3 flex items-center gap-4 text-xs text-muted">
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-sm bg-ok/70" /> {t("berhasil")}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-sm bg-danger/70" /> {t("gagal")}
        </span>
        <span className="ml-auto">{data[0]?.day} → {data[data.length - 1]?.day}</span>
      </div>
    </div>
  );
}
