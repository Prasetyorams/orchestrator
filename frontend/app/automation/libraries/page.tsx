"use client";

import { Card, CardBody, CardHeader } from "@/components/ui/primitives";

export default function LibrariesPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Libraries</h1>
        <p className="text-sm text-muted">Pustaka activity yang dipakai bersama antar-proses.</p>
      </div>

      <Card>
        <CardHeader title="Libraries" />
        <CardBody>
          <div className="rounded-lg border border-dashed border-line py-12 text-center">
            <p className="text-sm text-muted">Layar ini belum punya API-nya sendiri.</p>
            <p className="mt-1 text-xs text-muted">
              Skema basis datanya sudah ada; yang belum adalah endpoint dan formulirnya.
            </p>
          </div>
        </CardBody>
      </Card>
    </div>
  );
}
