"use client";

import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi } from "@/lib/api";
import { Card, CardHeader, Cell, EmptyState, Row, Table } from "@/components/ui/primitives";

export default function ProcessesPage() {
  const processes = useQuery({ queryKey: ["processes"], queryFn: ForgeHubApi.processes });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Processes</h1>
        <p className="text-sm text-muted">Paket automasi yang siap dijalankan robot.</p>
      </div>

      <Card>
        <CardHeader title="Proses" />
        {processes.data && processes.data.length > 0 ? (
          <Table head={["Nama", "Paket", "Versi", "Entry point"]}>
            {processes.data.map((p) => (
              <Row key={p.id}>
                <Cell className="font-medium">
                  {p.name}
                  {p.description ? <p className="text-xs text-muted">{p.description}</p> : null}
                </Cell>
                <Cell className="text-muted">{p.packageName ?? "-"}</Cell>
                <Cell className="font-mono text-xs">{p.packageVersion ?? "-"}</Cell>
                <Cell className="font-mono text-xs">{p.entryPoint}</Cell>
              </Row>
            ))}
          </Table>
        ) : (
          <EmptyState message="Belum ada proses. Unggah paket dari Studio lebih dulu." />
        )}
      </Card>
    </div>
  );
}
