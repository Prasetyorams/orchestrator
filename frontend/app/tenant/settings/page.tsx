"use client";

import { useQuery } from "@tanstack/react-query";
import { OpenOrchestratorApi } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Card, CardHeader } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { JudulHalaman } from "@/components/HalamanFolder";

/** Setelan seluruh penyewa: layanan, isi basis data, lisensi, dan daftar penyewa. */
export default function Setelan() {
  const { t } = useT();

  const s = useQuery({ queryKey: ["settings"], queryFn: OpenOrchestratorApi.settings });
  const lisensi = useQuery({ queryKey: ["licensing"], queryFn: OpenOrchestratorApi.licensing });
  const penyewa = useQuery({ queryKey: ["tenants"], queryFn: OpenOrchestratorApi.tenants });

  return (
    <div className="space-y-5">
      <JudulHalaman judul={t("Setelan")} konteks="tenant" />

      <Card>
        <CardHeader title={t("Layanan")} />
        <dl className="grid gap-4 p-5 sm:grid-cols-2 lg:grid-cols-3">
          <Medan label={t("Penyewa aktif")} nilai={s.data?.tenant} />
          <Medan label={t("Basis data")} nilai={s.data?.database} />
          <Medan label={t("Zona tampilan")} nilai={s.data?.displayTimezone} />
          <Medan label={t("Waktu server")} nilai={dateTimeOf(s.data?.serverTime)} />
          <Medan label={t("Robot dianggap putus setelah")} nilai={t("{0} detik", s.data?.robotOfflineAfterSeconds ?? "-")} />
          <Medan label={t("Masa berlaku token")} nilai={t("{0} jam", s.data?.tokenLifetimeHours ?? "-")} />
        </dl>
      </Card>

      <Card>
        <CardHeader title={t("Isi basis data")} />
        <dl className="grid gap-4 p-5 sm:grid-cols-3 lg:grid-cols-5">
          <Medan label={t("Pengguna")} nilai={s.data?.counts.users} />
          <Medan label={t("Robot")} nilai={s.data?.counts.robots} />
          <Medan label={t("Proses")} nilai={s.data?.counts.processes} />
          <Medan label={t("Pekerjaan")} nilai={s.data?.counts.jobs} />
          <Medan label={t("Catatan")} nilai={s.data?.counts.logs} />
        </dl>
      </Card>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Card>
          <CardHeader title={t("Lisensi")} />
          <DataTable
            data={lisensi.data ?? []}
            kunci={(l) => l.id}
            perHalaman={0}
            kolom={[
              { judul: "Produk", sel: (l) => <span className="font-medium">{l.product}</span> },
              { judul: "Terpakai", sel: (l) => <span className="tabular-nums">{l.used}</span> },
              {
                judul: "Total",
                sel: (l) => <span className="tabular-nums text-muted">{l.total === 0 ? t("tanpa batas") : l.total}</span>,
              },
              {
                judul: "Berlaku sampai",
                sel: (l) => <span className="text-muted">{l.expiresAt ? dateTimeOf(l.expiresAt) : "-"}</span>,
              },
            ]}
          />
        </Card>

        <Card>
          <CardHeader title={t("Penyewa")} />
          <DataTable
            data={penyewa.data ?? []}
            kunci={(p) => p.name}
            perHalaman={0}
            kolom={[
              { judul: "Nama", sel: (p) => <span className="font-medium">{p.displayName}</span> },
              { judul: "Kode", sel: (p) => <code className="text-xs text-muted">{p.name}</code> },
              { judul: "Pengguna", sel: (p) => <span className="tabular-nums">{p.userCount}</span> },
              { judul: "Robot", sel: (p) => <span className="tabular-nums">{p.robotCount}</span> },
              { judul: "Dibuat", sel: (p) => <span className="text-muted">{dateTimeOf(p.createdAt)}</span> },
            ]}
          />
        </Card>
      </div>
    </div>
  );
}

function Medan({ label, nilai }: { label: string; nilai: string | number | undefined }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-0.5 font-medium text-ink">{nilai ?? "..."}</dd>
    </div>
  );
}
