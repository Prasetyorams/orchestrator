"use client";

import type { Robot } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge } from "@/components/ui/primitives";

/** Nama pemicu jalan (V14) untuk layar — belum diterjemahkan. */
export function labelPemicu(pemicu: string | null | undefined): string {
  return ({ job: "Job", manual: "Manual", "local-schedule": "Jadwal lokal" } as Record<string, string>)[pemicu ?? ""] ?? "-";
}

/**
 * Yang sedang dikerjakan robot: job Robot Agent-nya, atau automasi lokal Open
 * Assistant di PC attended (V14) — Play manual atau jadwal lokal, di luar
 * Orchestrator. Selama yang kedua berjalan, robot itu tidak diberi job.
 *
 * Robot v1 tidak melaporkan job-nya sendiri: "-".
 */
export function PekerjaanRobot({ robot }: { robot: Robot }) {
  const { t } = useT();

  if (robot.currentJobProcess) {
    return (
      <span className="flex items-center gap-2">
        <span className="max-w-40 truncate">{robot.currentJobProcess}</span>
        {robot.currentJobState ? <Badge value={robot.currentJobState} /> : null}
      </span>
    );
  }

  // Robot yang terputus tidak sedang menjalankan apa pun yang bisa dipastikan.
  if (robot.busyLocalSince && robot.status !== "DISCONNECTED") {
    return (
      <span className="flex flex-col" title={t("Dijalankan di PC robot, di luar Orchestrator. Robot ini tidak diberi job sampai selesai.")}>
        <span className="flex items-center gap-2">
          <Badge value="BUSY" label={t("Sibuk (lokal)")} />
          <span className="max-w-40 truncate">{robot.busyLocalName ?? t("Automasi lokal")}</span>
        </span>
        <span className="mt-0.5 text-xs text-muted">
          {robot.busyLocalTrigger ? `${t(labelPemicu(robot.busyLocalTrigger))} · ` : ""}
          {t("sejak {0}", dateTimeOf(robot.busyLocalSince))}
        </span>
      </span>
    );
  }

  return <span className="text-muted">-</span>;
}
