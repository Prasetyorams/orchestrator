"use client";

import { useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check } from "lucide-react";
import { OpenOrchestratorApi, errorText } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { PilihTingkat } from "@/components/PilihTingkat";
import { JudulHalaman } from "@/components/HalamanFolder";

const TINGKAT = ["Info", "Warning", "Error"];

/**
 * Semua peringatan penyewa. Peringatan tidak tinggal di folder: robot yang
 * terputus atau paket yang baru terbit perlu diketahui dari folder mana pun.
 */
export default function Peringatan() {
  const { t, tp } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();

  const [tingkat, setTingkat] = useState<string[]>([]);
  const [belumDibaca, setBelumDibaca] = useState(false);
  const [galat, setGalat] = useState("");

  const peringatan = useQuery({
    queryKey: ["alerts", "semua", tingkat, belumDibaca],
    queryFn: () => OpenOrchestratorApi.alerts({ severity: tingkat, unread: belumDibaca ? "1" : undefined, limit: 500 }),
    refetchInterval: 15_000,
    placeholderData: keepPreviousData,
  });

  const segarkan = () => klien.invalidateQueries({ queryKey: ["alerts"] });

  const tandai = useMutation({
    mutationFn: OpenOrchestratorApi.readAlert,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const tandaiSemua = useMutation({
    mutationFn: OpenOrchestratorApi.readAllAlerts,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div>
      <JudulHalaman
        judul={t("Peringatan")}
        konteks="tenant"
        aksi={
          boleh("alerts.update") ? (
            <Button onClick={() => tandaiSemua.mutate()} disabled={tandaiSemua.isPending}>
              {t("Tandai semua dibaca")}
            </Button>
          ) : null
        }
      >
        <PilihTingkat pilihan={TINGKAT} terpilih={tingkat} onUbah={setTingkat} />
        <label className="flex items-center gap-2 text-sm text-muted">
          <input
            type="checkbox"
            checked={belumDibaca}
            onChange={(e) => setBelumDibaca(e.target.checked)}
            className="h-4 w-4 rounded border-line"
          />
          {t("Hanya yang belum dibaca")}
        </label>
      </JudulHalaman>

      <Galat pesan={galat} className="mb-4" />

      <Card className={cn("transition-opacity", peringatan.isPlaceholderData && "opacity-60")}>
        <DataTable
          data={peringatan.data ?? []}
          kunci={(a) => String(a.id)}
          perHalaman={50}
          kosong={
            peringatan.isLoading
              ? "Memuat..."
              : tingkat.length || belumDibaca
                ? "Tidak ada peringatan pada tingkat ini."
                : "Belum ada peringatan."
          }
          kolom={[
            { judul: "Tingkat", sel: (a) => <Badge value={a.severity.toUpperCase()} />, urut: (a) => a.severity },
            {
              judul: "Pesan",
              sel: (a) => (
                <div className={cn(!a.isRead && "border-l-2 border-brand pl-2")}>
                  <p className={cn("text-ink", !a.isRead && "font-semibold")}>{tp(a.title)}</p>
                  {a.message ? <p className="text-xs text-muted">{tp(a.message)}</p> : null}
                </div>
              ),
            },
            { judul: "Sumber", sel: (a) => <span className="text-muted">{a.source ?? "-"}</span>, urut: (a) => a.source },
            {
              judul: "Waktu",
              sel: (a) => <span className="whitespace-nowrap text-muted">{dateTimeOf(a.createdAt)}</span>,
              urut: (a) => a.id,
            },
            {
              judul: "",
              sel: (a) =>
                a.isRead || !boleh("alerts.update") ? null : (
                  <div className="flex justify-end">
                    <IconButton label={t("Tandai dibaca")} onClick={() => tandai.mutate(a.id)}>
                      <Check size={16} />
                    </IconButton>
                  </div>
                ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
