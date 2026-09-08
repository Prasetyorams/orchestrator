"use client";

import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi } from "@/lib/api";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Card, CardHeader, Cell, EmptyState, Row, Table } from "@/components/ui/primitives";

export default function MachinesPage() {
  const robots = useQuery({ queryKey: ["robots"], queryFn: ForgeHubApi.robots, refetchInterval: 10_000 });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Machines</h1>
        <p className="text-sm text-muted">Robot yang terdaftar beserta mesin tempatnya berjalan.</p>
      </div>

      <Card>
        <CardHeader title="Robot" />
        {robots.data && robots.data.length > 0 ? (
          <Table head={["Robot", "Machine", "Tipe", "CPU", "Memori", "Denyut terakhir", "Status"]}>
            {robots.data.map((r) => (
              <Row key={r.id}>
                <Cell className="font-medium">{r.name}</Cell>
                <Cell className="text-muted">{r.machineName ?? "-"}</Cell>
                <Cell className="text-muted">{r.robotType}</Cell>
                <Cell className="tabular-nums">{r.cpuPercent.toFixed(1)}%</Cell>
                <Cell className="tabular-nums">{r.memoryMb.toFixed(0)} MB</Cell>
                <Cell className="text-muted">{dateTimeOf(r.lastHeartbeat)}</Cell>
                <Cell><Badge value={r.status} /></Cell>
              </Row>
            ))}
          </Table>
        ) : (
          <EmptyState message="Belum ada robot yang mendaftar." />
        )}
      </Card>
    </div>
  );
}
