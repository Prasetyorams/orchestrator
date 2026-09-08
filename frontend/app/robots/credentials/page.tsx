"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText, type Credential } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Button, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

export default function Kredensial() {
  const { t } = useT();
  const klien = useQueryClient();

  const [sunting, setSunting] = useState<Credential | null>(null);
  const [baru, setBaru] = useState(false);
  const [galat, setGalat] = useState("");

  const kredensial = useQuery({ queryKey: ["credentials"], queryFn: ForgeHubApi.credentials });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteCredential,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["credentials"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center">
        <h1 className="text-lg font-semibold text-ink">{t("Kredensial")}</h1>
        <Button variant="primary" className="ml-auto" onClick={() => setBaru(true)}>
          {t("Tambah")}
        </Button>
      </div>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <p className="text-sm text-muted">
        Kata sandinya disandikan dan tidak pernah dikirim ke layar ini — hanya robot yang
        memintanya lewat activity Get Credential yang menerimanya.
      </p>

      <Card>
        <DataTable
          data={kredensial.data ?? []}
          kunci={(k) => k.name}
          onBuka={setSunting}
          kolom={[
            { judul: "Nama", sel: (k) => <span className="font-medium">{k.name}</span>, urut: (k) => k.name },
            { judul: "Nama pengguna", sel: (k) => k.username ?? "-", urut: (k) => k.username },
            { judul: "Keterangan", sel: (k) => <span className="text-muted">{k.description ?? "-"}</span> },
            { judul: "Dibuat", sel: (k) => <span className="text-muted">{dateTimeOf(k.createdAt)}</span>, urut: (k) => k.createdAt },
            {
              judul: "",
              sel: (k) => (
                <div className="flex justify-end">
                  <Button
                    variant="ghost"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${k.name}"?`)) hapus.mutate(k.name);
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

      {baru || sunting ? (
        <DialogKredensial
          key={sunting?.name ?? "baru"}
          awal={sunting}
          onTutup={() => {
            setBaru(false);
            setSunting(null);
          }}
          onSelesai={() => klien.invalidateQueries({ queryKey: ["credentials"] })}
        />
      ) : null}
    </div>
  );
}

function DialogKredensial({
  awal,
  onTutup,
  onSelesai,
}: {
  awal: Credential | null;
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const [nama, setNama] = useState(awal?.name ?? "");
  const [pengguna, setPengguna] = useState(awal?.username ?? "");
  const [sandi, setSandi] = useState("");
  const [ket, setKet] = useState(awal?.description ?? "");
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: ForgeHubApi.saveCredential,
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <Dialog
      judul={awal ? `${t("Sunting")} — ${awal.name}` : `${t("Tambah")} ${t("Kredensial").toLowerCase()}`}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button
            variant="primary"
            disabled={simpan.isPending}
            onClick={() => {
              if (!nama.trim()) return setGalat("Nama kredensial wajib diisi.");

              // Kata sandi kosong TIDAK dikirim: server memakai COALESCE, jadi
              // yang lama dipertahankan. Mengirim untai kosong akan menimpanya.
              simpan.mutate({
                name: nama.trim(),
                username: pengguna,
                description: ket,
                ...(sandi ? { password: sandi } : {}),
              });
            }}
          >
            {t("Simpan")}
          </Button>
        </>
      }
    >
      <Isian label={t("Nama")}>
        <input value={nama} onChange={(e) => setNama(e.target.value)} disabled={!!awal} className={kelasIsian} />
      </Isian>

      <Isian label={t("Nama pengguna")}>
        <input value={pengguna} onChange={(e) => setPengguna(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian
        label={t("Kata sandi")}
        petunjuk={awal ? "Kosongkan kalau tidak ingin menggantinya." : undefined}
      >
        <input type="password" value={sandi} onChange={(e) => setSandi(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian label={t("Keterangan")}>
        <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
      </Isian>

      {galat ? <p className="text-sm text-danger">{galat}</p> : null}
    </Dialog>
  );
}
