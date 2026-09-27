"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { OpenOrchestratorApi, errorText } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf } from "@/lib/utils";
import { Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { BilahAlat } from "@/components/HalamanFolder";

/** Mesin tempat robot berjalan; ikut terdaftar sendiri saat robotnya pertama kali berdenyut. */
export default function Mesin() {
  const { t, tp } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();
  const [galat, setGalat] = useState("");

  const mesin = useQuery({ queryKey: ["machines"], queryFn: OpenOrchestratorApi.machines });

  const hapus = useMutation({
    mutationFn: OpenOrchestratorApi.deleteMachine,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["machines"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div>
      <BilahAlat>
        <p className="text-sm text-muted">{t("Mesin ikut terdaftar sendiri saat robotnya pertama kali berdenyut.")}</p>
      </BilahAlat>

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={mesin.data ?? []}
          kunci={(m) => m.name}
          kosong={mesin.isLoading ? "Memuat..." : "Belum ada data."}
          kolom={[
            { judul: "Nama", sel: (m) => <span className="font-medium">{m.name}</span>, urut: (m) => m.name },
            { judul: "Tipe", sel: (m) => m.type, urut: (m) => m.type },
            { judul: "Robot", sel: (m) => <span className="tabular-nums">{m.robotCount}</span>, urut: (m) => m.robotCount },
            {
              judul: "Keterangan",
              sel: (m) => <span className="text-muted">{m.description ? tp(m.description) : "-"}</span>,
            },
            { judul: "Dibuat", sel: (m) => <span className="text-muted">{dateTimeOf(m.createdAt)}</span>, urut: (m) => m.createdAt },
            {
              judul: "",
              sel: (m) =>
                boleh("machines.delete") ? (
                  <div className="flex justify-end">
                    <IconButton
                      label={t("Hapus")}
                      tone="danger"
                      onClick={() => {
                        if (window.confirm(`${t("Yakin menghapus")} "${m.name}"?`)) hapus.mutate(m.name);
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
