"use client";

import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi } from "@/lib/api";
import { dateTimeOf } from "@/lib/utils";
import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  Cell,
  EmptyState,
  Row,
  StatCard,
  Table,
} from "@/components/ui/primitives";
import { RealTimeLogs } from "@/components/RealTimeLogs";

export default function DashboardPage() {
  const summary = useQuery({
    queryKey: ["summary"],
    queryFn: ForgeHubApi.summary,
    refetchInterval: 10_000,
  });

  const jobs = useQuery({
    queryKey: ["jobs", "running"],
    queryFn: () => ForgeHubApi.jobs(),
    refetchInterval: 5_000,
  });

  const robots = useQuery({
    queryKey: ["robots"],
    queryFn: ForgeHubApi.robots,
    refetchInterval: 10_000,
  });

  const triggers = useQuery({ queryKey: ["triggers"], queryFn: ForgeHubApi.triggers });

  const s = summary.data;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Dashboard</h1>
        <p className="text-sm text-muted">Ringkasan robot, pekerjaan, dan antrean hari ini.</p>
      </div>

      {/* Kartu ringkasan */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <StatCard
          label="Total Robots"
          value={s?.totalRobots ?? "—"}
          hint={s ? `${s.availableRobots} tersedia` : undefined}
        />
        <StatCard
          label="Current Jobs"
          value={s?.runningJobs ?? "—"}
          hint={s ? `${s.pendingJobs} menunggu` : undefined}
          tone="info"
        />
        <StatCard label="Assets" value={s?.totalAssets ?? "—"} />
        <StatCard label="Queues" value={s?.totalQueues ?? "—"} />
        <StatCard
          label="Alerts"
          value={s?.alerts ?? "—"}
          hint={s && s.faultedJobs > 0 ? `${s.faultedJobs} job gagal` : "tidak ada"}
          tone={s && s.alerts > 0 ? "danger" : "ok"}
        />
      </div>

      {/* Job Management & Execution */}
      <Card>
        <CardHeader title="Job Management & Execution" />
        {jobs.isLoading ? (
          <EmptyState message="Memuat pekerjaan..." />
        ) : jobs.data && jobs.data.length > 0 ? (
          <Table head={["Job Name", "Robot", "Machine", "Duration", "Status"]}>
            {jobs.data.map((job) => (
              <Row key={job.id}>
                <Cell className="font-medium">{job.jobName}</Cell>
                <Cell className="text-muted">{job.robotName}</Cell>
                <Cell className="text-muted">{job.machineName}</Cell>
                <Cell className="tabular-nums text-muted">{job.duration}</Cell>
                <Cell>
                  <Badge value={job.status} />
                </Cell>
              </Row>
            ))}
          </Table>
        ) : (
          <EmptyState message="Belum ada pekerjaan. Jalankan proses dari Studio atau JakRunner." />
        )}
      </Card>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        {/* Robot & Environment Overview */}
        <Card>
          <CardHeader title="Robot & Environment Overview" />
          {robots.data && robots.data.length > 0 ? (
            <Table head={["Robot", "Environment", "CPU", "Mem", "Status"]}>
              {robots.data.map((robot) => (
                <Row key={robot.id}>
                  <Cell className="font-medium">
                    {robot.name}
                    <span className="ml-2 text-xs text-muted">{robot.machineName ?? "-"}</span>
                  </Cell>
                  <Cell className="text-muted">{robot.environmentName ?? "-"}</Cell>
                  <Cell className="tabular-nums">{robot.cpuPercent.toFixed(1)}%</Cell>
                  <Cell className="tabular-nums">{robot.memoryMb.toFixed(0)} MB</Cell>
                  <Cell>
                    <Badge value={robot.status} />
                  </Cell>
                </Row>
              ))}
            </Table>
          ) : (
            <EmptyState message="Belum ada robot yang mendaftar." />
          )}
        </Card>

        {/* Triggers & Scheduling */}
        <Card>
          <CardHeader title="Triggers & Scheduling" />
          <CardBody>
            {triggers.data && triggers.data.length > 0 ? (
              <ul className="space-y-2.5">
                {triggers.data.map((t) => (
                  <li
                    key={t.id}
                    className="flex items-center justify-between rounded-lg border border-line px-3.5 py-2.5"
                  >
                    <div>
                      <p className="text-sm font-medium">{t.name}</p>
                      <p className="font-mono text-xs text-muted">{t.cronExpression ?? "manual"}</p>
                    </div>
                    <div className="text-right">
                      <Badge value={t.enabled ? "AVAILABLE" : "OFFLINE"} />
                      <p className="mt-1 text-[11px] text-muted">{dateTimeOf(t.nextRunAt)}</p>
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <div className="rounded-lg border border-dashed border-line py-10 text-center">
                <p className="text-sm text-muted">Belum ada jadwal.</p>
                <p className="mt-1 text-xs text-muted">
                  Tampilan kalender akan menggantikan daftar ini begitu penjadwalan dipakai.
                </p>
              </div>
            )}
          </CardBody>
        </Card>
      </div>

      {/* Real-Time Logs */}
      <RealTimeLogs />
    </div>
  );
}
