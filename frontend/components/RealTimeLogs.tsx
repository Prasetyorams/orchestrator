"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi, type LogLine } from "@/lib/api";
import { timeOf } from "@/lib/utils";
import { Card, CardHeader } from "@/components/ui/primitives";

/**
 * Panel log seperti terminal.
 *
 * Yang ditarik hanya baris yang LEBIH BARU dari yang sudah dipegang: id
 * terakhir disimpan, lalu dikirim balik sebagai afterId. Menarik dua ratus
 * baris terakhir setiap dua detik akan bekerja juga, tapi ia mengunduh hal
 * yang sama berulang-ulang dan membuat panel berkedip setiap penarikan.
 */
export function RealTimeLogs() {
  const [lines, setLines] = useState<LogLine[]>([]);
  const [follow, setFollow] = useState(true);
  const lastId = useRef<number | undefined>(undefined);
  const boxRef = useRef<HTMLDivElement | null>(null);

  const query = useQuery({
    queryKey: ["logs", "stream"],
    queryFn: () => ForgeHubApi.logs(lastId.current),
    refetchInterval: 2_000,
  });

  useEffect(() => {
    const incoming = query.data;
    if (!incoming || incoming.length === 0) return;

    setLines((previous) => {
      // Penarikan pertama datang terbaru-dulu; penarikan berikutnya
      // terlama-dulu. Keduanya disamakan di sini supaya urutan di layar
      // selalu naik.
      const ordered = lastId.current === undefined ? [...incoming].reverse() : incoming;
      const merged = [...previous, ...ordered];

      // Dibatasi supaya halaman yang dibiarkan terbuka semalaman tidak
      // menumpuk ratusan ribu simpul DOM.
      return merged.length > 500 ? merged.slice(merged.length - 500) : merged;
    });

    lastId.current = incoming.reduce((max, line) => (line.id > max ? line.id : max), lastId.current ?? 0);
  }, [query.data]);

  useEffect(() => {
    if (!follow || !boxRef.current) return;
    boxRef.current.scrollTop = boxRef.current.scrollHeight;
  }, [lines, follow]);

  const colorOf = (level: string) => {
    switch (level?.toUpperCase()) {
      case "ERROR":
        return "text-red-400";
      case "WARNING":
        return "text-amber-300";
      default:
        return "text-slate-300";
    }
  };

  return (
    <Card>
      <CardHeader
        title="Real-Time Logs"
        action={
          <label className="flex cursor-pointer items-center gap-2 text-xs text-muted">
            <input
              type="checkbox"
              checked={follow}
              onChange={(e) => setFollow(e.target.checked)}
              className="h-3.5 w-3.5 accent-slate-700"
            />
            Ikuti baris terbaru
          </label>
        }
      />

      <div
        ref={boxRef}
        className="thin-scroll h-72 overflow-y-auto rounded-b-xl bg-slate-900 p-4 font-mono text-xs leading-relaxed"
      >
        {lines.length === 0 ? (
          <p className="text-slate-500">Menunggu log dari robot...</p>
        ) : (
          lines.map((line) => (
            <div key={line.id} className="whitespace-pre-wrap">
              <span className="text-slate-500">{timeOf(line.loggedAt)}</span>{" "}
              <span className={colorOf(line.level)}>[{line.level}]</span>{" "}
              {line.robotName ? <span className="text-sky-400">{line.robotName} </span> : null}
              <span className="text-slate-200">{line.message}</span>
            </div>
          ))
        )}
      </div>
    </Card>
  );
}
