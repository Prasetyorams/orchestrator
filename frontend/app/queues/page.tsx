"use client";

import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi } from "@/lib/api";
import { Card, CardHeader, Cell, EmptyState, Row, Table } from "@/components/ui/primitives";

export default function QueuesPage() {
  const queues = useQuery({ queryKey: ["queues"], queryFn: ForgeHubApi.queues, refetchInterval: 10_000 });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Queues</h1>
        <p className="text-sm text-muted">Antrean transaksi beserta hitungan tiap keadaannya.</p>
      </div>

      <Card>
        <CardHeader title="Antrean" />
        {queues.data && queues.data.length > 0 ? (
          <Table head={["Nama", "Baru", "Diproses", "Berhasil", "Gagal"]}>
            {queues.data.map((q) => (
              <Row key={q.id}>
                <Cell className="font-medium">
                  {q.name}
                  {q.description ? <p className="text-xs text-muted">{q.description}</p> : null}
                </Cell>
                <Cell className="tabular-nums">{q.newCount}</Cell>
                <Cell className="tabular-nums text-info">{q.inProgressCount}</Cell>
                <Cell className="tabular-nums text-ok">{q.successCount}</Cell>
                <Cell className="tabular-nums text-danger">{q.failedCount}</Cell>
              </Row>
            ))}
          </Table>
        ) : (
          <EmptyState message="Belum ada antrean." />
        )}
      </Card>
    </div>
  );
}
