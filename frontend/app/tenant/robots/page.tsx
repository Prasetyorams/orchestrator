"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { ForgeHubApi, errorText } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { BilahAlat } from "@/components/HalamanFolder";

/**
 * Semua robot penyewa, dengan folder tempat masing-masing ditugaskan.
 *
 * Robot tidak tinggal di satu folder: satu mesin bisa melayani beberapa
 * folder. Menugaskannya ada di Setelan setiap folder; di sini robotnya
 * dilihat dan, kalau sudah tidak dipakai, dihapus.
 */
export default function RobotPenyewa() {
  const { t } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();
  const [galat, setGalat] = useState("");

  const robots = useQuery({ queryKey: ["robots", "semua"], queryFn: () => ForgeHubApi.robots(), refetchInterval: 10_000 });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteRobot,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["robots"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div>
      <BilahAlat>
        <p className="text-sm text-muted">
          {t(
            "Robot mendaftarkan dirinya sendiri saat JakRunner berdenyut pertama kali, dan langsung ditugaskan ke folder Shared.",
          )}
        </p>
      </BilahAlat>

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={robots.data ?? []}
          kunci={(r) => r.name}
          kosong={robots.isLoading ? "Memuat..." : "Belum ada robot yang mendaftar."}
          kolom={[
            { judul: "Nama", sel: (r) => <span className="font-medium">{r.name}</span>, urut: (r) => r.name },
            { judul: "Mesin|satu", sel: (r) => <span className="text-muted">{r.machineName ?? "-"}</span>, urut: (r) => r.machineName },
            { judul: "Tipe", sel: (r) => r.type, urut: (r) => r.type },
            {
              judul: "Folder",
              sel: (r) =>
                r.folders?.length ? (
                  <span className="text-muted" title={r.folders.join(", ")}>
                    {r.folders.length > 3 ? `${r.folders.slice(0, 3).join(", ")} +${r.folders.length - 3}` : r.folders.join(", ")}
                  </span>
                ) : (
                  <span className="text-warn">{t("Tidak di folder mana pun")}</span>
                ),
            },
            {
              judul: "CPU / Memori",
              sel: (r) => (
                <span className="tabular-nums text-muted">
                  {r.cpuPercent.toFixed(1)}% / {r.memoryMb.toFixed(0)} MB
                </span>
              ),
              urut: (r) => r.cpuPercent,
            },
            {
              judul: "Denyut",
              sel: (r) => <span className="text-muted">{dateTimeOf(r.lastHeartbeatAt)}</span>,
              urut: (r) => r.lastHeartbeatAt,
            },
            { judul: "Status", sel: (r) => <Badge value={r.status} />, urut: (r) => r.status },
            {
              judul: "",
              sel: (r) =>
                boleh("robots.delete") ? (
                  <div className="flex justify-end">
                    <IconButton
                      label={t("Hapus")}
                      tone="danger"
                      onClick={() => {
                        if (window.confirm(`${t("Yakin menghapus")} "${r.name}"?`)) hapus.mutate(r.name);
                      }}
                    >
                      <Trash2 size={16} />
                    </IconButton>
                  </div>
                ) : null,
            },
          ]}
        />
      </Card>
    </div>
  );
}
