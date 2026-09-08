"use client";

import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi } from "@/lib/api";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Card, CardHeader, Cell, EmptyState, Row, Table } from "@/components/ui/primitives";

export default function JobsPage() {
  const jobs = useQuery({ queryKey: ["jobs", "all"], queryFn: () => ForgeHubApi.jobs(), refetchInterval: 5_000 });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Jobs</h1>
        <p className="text-sm text-muted">Seluruh pekerjaan, terbaru di atas.</p>
      </div>

      <Card>
        <CardHeader title="Semua pekerjaan" />
        {jobs.data && jobs.data.length > 0 ? (
          <Table head={["Job Name", "Robot", "Machine", "Mulai", "Durasi", "Status"]}>
            {jobs.data.map((job) => (
              <Row key={job.id}>
                <Cell className="font-medium">
                  {job.jobName}
                  {job.errorMessage ? (
                    <p className="mt-0.5 text-xs text-danger">{job.errorMessage}</p>
                  ) : null}
                </Cell>
                <Cell className="text-muted">{job.robotName}</Cell>
                <Cell className="text-muted">{job.machineName}</Cell>
                <Cell className="text-muted">{dateTimeOf(job.startedAt)}</Cell>
                <Cell className="tabular-nums text-muted">{job.duration}</Cell>
                <Cell><Badge value={job.status} /></Cell>
              </Row>
            ))}
          </Table>
        ) : (
          <EmptyState message="Belum ada pekerjaan." />
        )}
      </Card>
    </div>
  );
}
