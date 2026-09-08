"use client";

import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";

export default function Setelan() {
  const { t } = useT();

  const s = useQuery({ queryKey: ["settings"], queryFn: ForgeHubApi.settings });
  const lisensi = useQuery({ queryKey: ["licensing"], queryFn: ForgeHubApi.licensing });
  const peran = useQuery({ queryKey: ["roles"], queryFn: ForgeHubApi.roles });

  return (
    <div className="space-y-6">
      <h1 className="text-lg font-semibold text-ink">{t("Setelan")}</h1>

      <Card>
        <CardHeader title="Layanan" />
        <CardBody>
          <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <Medan label="Penyewa" nilai={s.data?.tenant} />
            <Medan label="Basis data" nilai={s.data?.database} />
            <Medan label="Zona tampilan" nilai={s.data?.displayTimezone} />
            <Medan label="Waktu server" nilai={dateTimeOf(s.data?.serverTime)} />
            <Medan label="Robot dianggap putus setelah" nilai={`${s.data?.robotOfflineAfterSeconds ?? "-"} detik`} />
            <Medan label="Masa berlaku token" nilai={`${s.data?.tokenLifetimeHours ?? "-"} jam`} />
          </dl>
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Isi basis data" />
        <CardBody>
          <dl className="grid gap-4 sm:grid-cols-3 lg:grid-cols-5">
            <Medan label={t("Pengguna")} nilai={s.data?.counts.users} />
            <Medan label={t("Robot")} nilai={s.data?.counts.robots} />
            <Medan label={t("Proses")} nilai={s.data?.counts.processes} />
            <Medan label={t("Pekerjaan")} nilai={s.data?.counts.jobs} />
            <Medan label={t("Catatan")} nilai={s.data?.counts.logs} />
          </dl>
        </CardBody>
      </Card>

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
              sel: (l) => (
                <span className="tabular-nums text-muted">{l.total === 0 ? "tanpa batas" : l.total}</span>
              ),
            },
            { judul: "Berlaku sampai", sel: (l) => <span className="text-muted">{l.expiresAt ? dateTimeOf(l.expiresAt) : "-"}</span> },
          ]}
        />
      </Card>

      <Card>
        <CardHeader title={t("Peran")} />
        <DataTable
          data={peran.data ?? []}
          kunci={(p) => p.name}
          perHalaman={0}
          kolom={[
            { judul: "Nama", sel: (p) => <span className="font-medium">{p.name}</span> },
            { judul: "Keterangan", sel: (p) => <span className="text-muted">{p.description ?? "-"}</span> },
            {
              judul: "Izin",
              sel: (p) => <code className="text-xs text-muted">{p.permissions ?? "-"}</code>,
            },
            { judul: "Pengguna", sel: (p) => <span className="tabular-nums">{p.userCount}</span> },
          ]}
        />
      </Card>
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
