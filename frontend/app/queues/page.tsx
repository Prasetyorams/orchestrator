"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi, type Queue } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog } from "@/components/Dialog";

export default function Antrean() {
  const { t } = useT();
  const [detail, setDetail] = useState<Queue | null>(null);

  const antrean = useQuery({ queryKey: ["queues"], queryFn: ForgeHubApi.queues, refetchInterval: 10_000 });

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-ink">{t("Antrean")}</h1>

      <p className="text-sm text-muted">
        Klik ganda pada barisnya untuk melihat butir-butirnya.
      </p>

      <Card>
        <DataTable
          data={antrean.data ?? []}
          kunci={(q) => q.name}
          onBuka={setDetail}
          kolom={[
            {
              judul: "Nama",
              sel: (q) => (
                <div>
                  <p className="font-medium">{q.name}</p>
                  {q.description ? <p className="text-xs text-muted">{q.description}</p> : null}
                </div>
              ),
              urut: (q) => q.name,
            },
            { judul: "Baru", sel: (q) => <span className="tabular-nums">{q.newCount}</span>, urut: (q) => q.newCount },
            {
              judul: "Diproses",
              sel: (q) => <span className="tabular-nums text-info">{q.inProgressCount}</span>,
              urut: (q) => q.inProgressCount,
            },
            {
              judul: "Berhasil",
              sel: (q) => <span className="tabular-nums text-ok">{q.successfulCount}</span>,
              urut: (q) => q.successfulCount,
            },
            {
              judul: "Gagal",
              sel: (q) => (
                <span className={q.failedCount > 0 ? "tabular-nums text-danger" : "tabular-nums"}>
                  {q.failedCount}
                </span>
              ),
              urut: (q) => q.failedCount,
            },
            { judul: "Total", sel: (q) => <span className="tabular-nums">{q.totalCount}</span>, urut: (q) => q.totalCount },
            {
              judul: "Percobaan",
              sel: (q) => <span className="text-muted">maks {q.maxRetries}</span>,
              urut: (q) => q.maxRetries,
            },
          ]}
        />
      </Card>

      <DialogButir antrean={detail} onTutup={() => setDetail(null)} />
    </div>
  );
}

function DialogButir({ antrean, onTutup }: { antrean: Queue | null; onTutup: () => void }) {
  const { t } = useT();

  const butir = useQuery({
    queryKey: ["queueItems", antrean?.name],
    queryFn: () => ForgeHubApi.queueItems(antrean!.name, { limit: 500 }),
    enabled: !!antrean,
  });

  if (!antrean) return null;

  return (
    <Dialog judul={antrean.name} terbuka onTutup={onTutup} lebar="max-w-5xl">
      <DataTable
        data={butir.data ?? []}
        kunci={(b) => b.id}
        perHalaman={20}
        kosong={butir.isFetching ? "Memuat..." : "Belum ada data."}
        kolom={[
          { judul: "Rujukan", sel: (b) => <span className="font-medium">{b.reference ?? "-"}</span>, urut: (b) => b.reference },
          { judul: "Status", sel: (b) => <Badge value={b.status} />, urut: (b) => b.status },
          { judul: "Robot", sel: (b) => <span className="text-muted">{b.robotName ?? "-"}</span>, urut: (b) => b.robotName },
          { judul: "Percobaan", sel: (b) => <span className="tabular-nums">{b.retries}</span>, urut: (b) => b.retries },
          {
            judul: "Isi",
            sel: (b) => (
              <code className="block max-w-xs truncate font-mono text-xs text-muted" title={b.content ?? ""}>
                {b.content ?? "-"}
              </code>
            ),
          },
          {
            judul: "Galat",
            sel: (b) => (
              <span className="block max-w-xs truncate text-xs text-danger" title={b.exception ?? ""}>
                {b.exception ?? ""}
              </span>
            ),
          },
          { judul: "Dibuat", sel: (b) => <span className="text-muted">{dateTimeOf(b.createdAt)}</span>, urut: (b) => b.createdAt },
        ]}
      />
    </Dialog>
  );
}
