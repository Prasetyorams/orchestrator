"use client";

import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ForgeHubApi } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { kelasIsian } from "@/components/Dialog";
import { PilihTingkat } from "@/components/PilihTingkat";

// Tanpa TRACE dan DEBUG: ForgeHub tidak menyimpan maupun menampilkan tingkat
// rincian (lihat LogLevel.rincian di backend), jadi pilihan itu selalu kosong.
// WARN juga membawa baris WARNING — satu tingkat dengan dua ejaan.
const TINGKAT = ["INFO", "WARN", "ERROR", "FATAL"];

export default function Catatan() {
  const { t, tp } = useT();

  const [tingkat, setTingkat] = useState<string[]>([]);
  const [proses, setProses] = useState("");
  const [ikuti, setIkuti] = useState(true);

  const daftarProses = useQuery({ queryKey: ["processes"], queryFn: ForgeHubApi.processes });

  const log = useQuery({
    queryKey: ["logs", tingkat, proses],
    queryFn: () =>
      ForgeHubApi.logs({
        level: tingkat,
        process: proses || undefined,
        limit: 500,
      }),
    // Hanya menyegarkan sendiri saat "ikuti" menyala. Tabel yang melompat ke
    // baris terbaru tiap tiga detik membuat orang yang sedang membaca satu
    // baris kehilangan tempatnya.
    refetchInterval: ikuti ? 3_000 : false,
    // Mengganti saringan tidak mengosongkan tabel lebih dulu.
    placeholderData: keepPreviousData,
  });

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-lg font-semibold text-ink">{t("Catatan")}</h1>

        <PilihTingkat pilihan={TINGKAT} terpilih={tingkat} onUbah={setTingkat} />

        <select
          value={proses}
          onChange={(e) => setProses(e.target.value)}
          className={`${kelasIsian} w-48`}
          aria-label={t("Proses|satu")}
        >
          <option value="">{t("Semua proses")}</option>
          {(daftarProses.data ?? []).map((p) => (
            <option key={p.name} value={p.name}>
              {p.name}
            </option>
          ))}
        </select>

        <label className="flex items-center gap-2 text-sm text-muted">
          <input
            type="checkbox"
            checked={ikuti}
            onChange={(e) => setIkuti(e.target.checked)}
            className="h-4 w-4 rounded border-line"
          />
          {t("Ikuti otomatis")}
        </label>

        <Button className="ml-auto" onClick={() => log.refetch()}>
          {t("Muat ulang")}
        </Button>
      </div>

      <p className="text-sm text-muted">
        {t(
          "Paling banyak 500 baris terbaru. Untuk catatan satu proses atau satu pekerjaan, buka detailnya dari halaman Proses atau Pekerjaan.",
        )}
      </p>

      <Card className={cn("transition-opacity", log.isPlaceholderData && "opacity-60")}>
        <DataTable
          data={log.data ?? []}
          kunci={(l) => String(l.id)}
          perHalaman={50}
          kolom={[
            { judul: "Waktu", sel: (l) => <span className="tabular-nums text-muted">{dateTimeOf(l.loggedAt)}</span>, urut: (l) => l.id },
            { judul: "Tingkat", sel: (l) => <Badge value={l.level} />, urut: (l) => l.level },
            { judul: "Robot|satu", sel: (l) => <span className="text-muted">{l.robotName ?? "-"}</span>, urut: (l) => l.robotName },
            { judul: "Proses|satu", sel: (l) => <span className="text-muted">{l.processName ?? "-"}</span>, urut: (l) => l.processName },
            { judul: "Pesan", sel: (l) => <span className="break-all">{tp(l.message)}</span> },
          ]}
        />
      </Card>
    </div>
  );
}
