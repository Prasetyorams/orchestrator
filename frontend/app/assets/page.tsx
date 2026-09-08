"use client";

import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi } from "@/lib/api";
import { Badge, Card, CardHeader, Cell, EmptyState, Row, Table } from "@/components/ui/primitives";

export default function AssetsPage() {
  const assets = useQuery({ queryKey: ["assets"], queryFn: ForgeHubApi.assets });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Assets</h1>
        <p className="text-sm text-muted">
          Nilai yang dipakai bersama oleh banyak proses. Aset bertipe CREDENTIAL tidak pernah
          menampilkan isinya di sini.
        </p>
      </div>

      <Card>
        <CardHeader title="Aset" />
        {assets.data && assets.data.length > 0 ? (
          <Table head={["Nama", "Tipe", "Nilai", "Keterangan"]}>
            {assets.data.map((a) => (
              <Row key={a.id}>
                <Cell className="font-medium">{a.name}</Cell>
                <Cell><Badge value={a.assetType} /></Cell>
                <Cell className="font-mono text-xs">{a.value ?? "-"}</Cell>
                <Cell className="text-muted">{a.description ?? "-"}</Cell>
              </Row>
            ))}
          </Table>
        ) : (
          <EmptyState message="Belum ada aset." />
        )}
      </Card>
    </div>
  );
}
