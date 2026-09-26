"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi, type FolderNode } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { BilahAlat, PerluFolder } from "@/components/HalamanFolder";

/**
 * Robot yang ditugaskan ke folder ini, dengan keadaan hidupnya.
 *
 * Hanya robot inilah yang mengambil pekerjaan folder ini tanpa disebut
 * namanya. Mendaftarkan dan menghapus robot adalah urusan penyewa (Tenant ›
 * Robot); menugaskannya ke folder ada di Setelan folder.
 */
export default function RobotFolder() {
  return <PerluFolder>{(folder) => <IsiRobot folder={folder} />}</PerluFolder>;
}

function IsiRobot({ folder }: { folder: FolderNode }) {
  const { t } = useT();
  const { boleh } = useIzin();

  const robots = useQuery({
    queryKey: ["robots", folder.id],
    queryFn: () => ForgeHubApi.robots(folder.id),
    refetchInterval: 10_000,
  });

  // Robot folder bersama diatur pengelola folder; robot Folder Saya oleh pemiliknya.
  const bolehAtur = boleh("folders.update") || folder.personal;

  return (
    <div>
      <BilahAlat
        aksi={
          bolehAtur ? (
            <Link
              href="/folder-settings?tab=robot"
              className="inline-flex items-center rounded-lg border border-line bg-card px-3.5 py-2 text-sm font-medium hover:bg-slate-50"
            >
              {t("Atur robot folder ini")}
            </Link>
          ) : undefined
        }
      >
        <p className="text-sm text-muted">
          {t("Robot yang ditugaskan ke folder ini mengambil pekerjaannya. Robot baru masuk ke folder Shared saat pertama kali tersambung.")}
        </p>
      </BilahAlat>

      <Card>
        <DataTable
          data={robots.data ?? []}
          kunci={(r) => r.name}
          kosong={robots.isLoading ? "Memuat..." : "Belum ada robot yang ditugaskan ke folder ini."}
          kolom={[
            { judul: "Nama", sel: (r) => <span className="font-medium">{r.name}</span>, urut: (r) => r.name },
            {
              judul: "Mesin|satu",
              sel: (r) => <span className="text-muted">{r.machineName ?? "-"}</span>,
              urut: (r) => r.machineName,
            },
            { judul: "Tipe", sel: (r) => r.type, urut: (r) => r.type },
            { judul: "Lingkungan|satu", sel: (r) => r.environment ?? "-", urut: (r) => r.environment },
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
          ]}
        />
      </Card>
    </div>
  );
}
