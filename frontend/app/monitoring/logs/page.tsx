"use client";

import { RealTimeLogs } from "@/components/RealTimeLogs";

export default function LogsPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Logs</h1>
        <p className="text-sm text-muted">Aliran log langsung dari robot yang sedang berjalan.</p>
      </div>
      <RealTimeLogs />
    </div>
  );
}
