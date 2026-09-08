"use client";

import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi } from "@/lib/api";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Card, CardHeader, Cell, EmptyState, Row, Table } from "@/components/ui/primitives";

export default function TriggersPage() {
  const triggers = useQuery({ queryKey: ["triggers"], queryFn: ForgeHubApi.triggers });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Triggers</h1>
        <p className="text-sm text-muted">Jadwal yang menjalankan proses tanpa ditekan orang.</p>
      </div>

      <Card>
        <CardHeader title="Jadwal" />
        {triggers.data && triggers.data.length > 0 ? (
          <Table head={["Nama", "Cron", "Jalan berikutnya", "Status"]}>
            {triggers.data.map((t) => (
              <Row key={t.id}>
                <Cell className="font-medium">{t.name}</Cell>
                <Cell className="font-mono text-xs text-muted">{t.cronExpression ?? "-"}</Cell>
                <Cell className="text-muted">{dateTimeOf(t.nextRunAt)}</Cell>
                <Cell><Badge value={t.enabled ? "AVAILABLE" : "OFFLINE"} /></Cell>
              </Row>
            ))}
          </Table>
        ) : (
          <EmptyState message="Belum ada jadwal." />
        )}
      </Card>
    </div>
  );
}
