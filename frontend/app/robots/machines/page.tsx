"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, CardHeader } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";

export default function RobotDanMesin() {
  const { t } = useT();
  const klien = useQueryClient();
  const [galat, setGalat] = useState("");

  const robots = useQuery({ queryKey: ["robots"], queryFn: ForgeHubApi.robots, refetchInterval: 10_000 });
  const mesin = useQuery({ queryKey: ["machines"], queryFn: ForgeHubApi.machines });

  const hapusRobot = useMutation({
    mutationFn: ForgeHubApi.deleteRobot,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["robots"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  const hapusMesin = useMutation({
    mutationFn: ForgeHubApi.deleteMachine,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["machines"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div className="space-y-6">
      <h1 className="text-lg font-semibold text-ink">{t("Robot")}</h1>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <p className="text-sm text-muted">
        Robot mendaftarkan dirinya sendiri saat JakRunner berdenyut pertama kali; tidak perlu
        dibuat lebih dulu di sini.
      </p>

      <Card>
        <CardHeader title={t("Robot")} />
        <DataTable
          data={robots.data ?? []}
          kunci={(r) => r.name}
          kosong="Belum ada robot yang mendaftar."
          kolom={[
            { judul: "Nama", sel: (r) => <span className="font-medium">{r.name}</span>, urut: (r) => r.name },
            { judul: "Mesin", sel: (r) => <span className="text-muted">{r.machineName ?? "-"}</span>, urut: (r) => r.machineName },
            { judul: "Tipe", sel: (r) => r.type, urut: (r) => r.type },
            { judul: "Lingkungan", sel: (r) => r.environment ?? "-", urut: (r) => r.environment },
            {
              judul: "CPU",
              sel: (r) => <span className="tabular-nums">{r.cpuPercent.toFixed(1)}%</span>,
              urut: (r) => r.cpuPercent,
            },
            {
              judul: "Memori",
              sel: (r) => <span className="tabular-nums">{r.memoryMb.toFixed(0)} MB</span>,
              urut: (r) => r.memoryMb,
            },
            {
              judul: "Denyut",
              sel: (r) => <span className="text-muted">{dateTimeOf(r.lastHeartbeatAt)}</span>,
              urut: (r) => r.lastHeartbeatAt,
            },
            { judul: "Status", sel: (r) => <Badge value={r.status} />, urut: (r) => r.status },
            {
              judul: "",
              sel: (r) => (
                <div className="flex justify-end">
                  <Button
                    variant="ghost"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${r.name}"?`)) hapusRobot.mutate(r.name);
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

      <Card>
        <CardHeader title={t("Mesin")} />
        <DataTable
          data={mesin.data ?? []}
          kunci={(m) => m.name}
          kolom={[
            { judul: "Nama", sel: (m) => <span className="font-medium">{m.name}</span>, urut: (m) => m.name },
            { judul: "Tipe", sel: (m) => m.type, urut: (m) => m.type },
            {
              judul: "Robot",
              sel: (m) => <span className="tabular-nums">{m.robotCount}</span>,
              urut: (m) => m.robotCount,
            },
            { judul: "Keterangan", sel: (m) => <span className="text-muted">{m.description ?? "-"}</span> },
            {
              judul: "",
              sel: (m) => (
                <div className="flex justify-end">
                  <Button
                    variant="ghost"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${m.name}"?`)) hapusMesin.mutate(m.name);
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
    </div>
  );
}
