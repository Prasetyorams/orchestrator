"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Button, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

export default function Lingkungan() {
  const { t } = useT();
  const klien = useQueryClient();

  const [baru, setBaru] = useState(false);
  const [nama, setNama] = useState("");
  const [ket, setKet] = useState("");
  const [galat, setGalat] = useState("");

  const lingkungan = useQuery({ queryKey: ["environments"], queryFn: ForgeHubApi.environments });
  const segarkan = () => klien.invalidateQueries({ queryKey: ["environments"] });

  const simpan = useMutation({
    mutationFn: ForgeHubApi.saveEnvironment,
    onSuccess: () => {
      segarkan();
      setBaru(false);
      setNama("");
      setKet("");
    },
    onError: (e) => setGalat(errorText(e)),
  });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteEnvironment,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center">
        <h1 className="text-lg font-semibold text-ink">{t("Lingkungan")}</h1>
        <Button variant="primary" className="ml-auto" onClick={() => setBaru(true)}>
          {t("Tambah")}
        </Button>
      </div>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <Card>
        <DataTable
          data={lingkungan.data ?? []}
          kunci={(l) => l.name}
          kolom={[
            { judul: "Nama", sel: (l) => <span className="font-medium">{l.name}</span>, urut: (l) => l.name },
            { judul: "Keterangan", sel: (l) => <span className="text-muted">{l.description ?? "-"}</span> },
            { judul: "Robot", sel: (l) => <span className="tabular-nums">{l.robotCount}</span>, urut: (l) => l.robotCount },
            { judul: "Dibuat", sel: (l) => <span className="text-muted">{dateTimeOf(l.createdAt)}</span>, urut: (l) => l.createdAt },
            {
              judul: "",
              sel: (l) => (
                <div className="flex justify-end">
                  <Button
                    variant="ghost"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${l.name}"?`)) hapus.mutate(l.name);
                    }}
                  >
                    {t("Hapus")}
                  </Button>
                </div>
              ),
            },
          ]}
        />
      </Card>

      <Dialog
        judul={`${t("Tambah")} ${t("Lingkungan").toLowerCase()}`}
        terbuka={baru}
        onTutup={() => setBaru(false)}
        aksi={
          <>
            <Button onClick={() => setBaru(false)}>{t("Batal")}</Button>
            <Button
              variant="primary"
              disabled={simpan.isPending}
              onClick={() => {
                if (!nama.trim()) return setGalat("Nama lingkungan wajib diisi.");
                simpan.mutate({ name: nama.trim(), description: ket });
              }}
            >
              {t("Simpan")}
            </Button>
          </>
        }
      >
        <Isian label={t("Nama")}>
          <input value={nama} onChange={(e) => setNama(e.target.value)} className={kelasIsian} />
        </Isian>
        <Isian label={t("Keterangan")}>
          <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
        </Isian>
      </Dialog>
    </div>
  );
}
