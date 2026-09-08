"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Eye } from "lucide-react";
import { ForgeHubApi, errorText, type Asset } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

const TIPE = ["Text", "Integer", "Bool", "Credential", "Secret"];
const RAHASIA = new Set(["Credential", "Secret"]);

export default function AsetHalaman() {
  const { t } = useT();
  const klien = useQueryClient();

  const [sunting, setSunting] = useState<Asset | null>(null);
  const [baru, setBaru] = useState(false);
  const [galat, setGalat] = useState("");
  const [terbuka, setTerbuka] = useState<Record<string, string>>({});

  const aset = useQuery({ queryKey: ["assets"], queryFn: ForgeHubApi.assets });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteAsset,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["assets"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  /** Nilai rahasia diambil satu per satu, hanya saat diminta. */
  async function lihat(nama: string) {
    try {
      const r = await ForgeHubApi.assetValue(nama);
      setTerbuka((s) => ({ ...s, [nama]: r.value ?? "" }));
    } catch (e) {
      setGalat(errorText(e));
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center">
        <h1 className="text-lg font-semibold text-ink">{t("Aset")}</h1>
        <Button variant="primary" className="ml-auto" onClick={() => setBaru(true)}>
          {t("Tambah")}
        </Button>
      </div>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <p className="text-sm text-muted">
        Nilai yang dipakai bersama oleh banyak proses. Aset bertipe Credential dan Secret tidak
        pernah menampilkan isinya di daftar — hanya saat diminta satu per satu.
      </p>

      <Card>
        <DataTable
          data={aset.data ?? []}
          kunci={(a) => a.name}
          onBuka={setSunting}
          kolom={[
            { judul: "Nama", sel: (a) => <span className="font-medium">{a.name}</span>, urut: (a) => a.name },
            { judul: "Tipe", sel: (a) => <Badge value={a.type.toUpperCase()} />, urut: (a) => a.type },
            {
              judul: "Nilai",
              sel: (a) =>
                RAHASIA.has(a.type) ? (
                  terbuka[a.name] !== undefined ? (
                    <code className="font-mono text-xs">{terbuka[a.name] || "(kosong)"}</code>
                  ) : (
                    <button
                      type="button"
                      onClick={() => lihat(a.name)}
                      className="inline-flex items-center gap-1 text-xs text-info hover:underline"
                    >
                      <Eye size={13} /> {a.hasValue ? "lihat" : "(kosong)"}
                    </button>
                  )
                ) : (
                  <code className="font-mono text-xs">{a.valueText ?? "-"}</code>
                ),
            },
            { judul: "Keterangan", sel: (a) => <span className="text-muted">{a.description ?? "-"}</span> },
            {
              judul: "Diubah",
              sel: (a) => <span className="text-muted">{dateTimeOf(a.updatedAt ?? a.createdAt)}</span>,
              urut: (a) => a.updatedAt ?? a.createdAt,
            },
            {
              judul: "",
              sel: (a) => (
                <div className="flex justify-end">
                  <Button
                    variant="ghost"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${a.name}"?`)) hapus.mutate(a.name);
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
        <DialogAset
          key={sunting?.name ?? "baru"}
          awal={sunting}
          onTutup={() => {
            setBaru(false);
            setSunting(null);
          }}
          onSelesai={() => klien.invalidateQueries({ queryKey: ["assets"] })}
        />
      ) : null}
    </div>
  );
}

function DialogAset({
  awal,
  onTutup,
  onSelesai,
}: {
  awal: Asset | null;
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const [nama, setNama] = useState(awal?.name ?? "");
  const [tipe, setTipe] = useState(awal?.type ?? "Text");
  const [nilai, setNilai] = useState(awal?.valueText ?? "");
  const [ket, setKet] = useState(awal?.description ?? "");
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: ForgeHubApi.saveAsset,
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <Dialog
      judul={awal ? `${t("Sunting")} — ${awal.name}` : `${t("Tambah")} ${t("Aset").toLowerCase()}`}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button
            variant="primary"
            disabled={simpan.isPending}
            onClick={() => {
              if (!nama.trim()) return setGalat("Nama aset wajib diisi.");
              simpan.mutate({ name: nama.trim(), type: tipe, value: nilai, description: ket });
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

      <Isian label={t("Tipe")}>
        <select value={tipe} onChange={(e) => setTipe(e.target.value)} className={kelasIsian}>
          {TIPE.map((x) => (
            <option key={x}>{x}</option>
          ))}
        </select>
      </Isian>

      <Isian
        label="Nilai"
        petunjuk={
          RAHASIA.has(tipe)
            ? "Disandikan sebelum disimpan, dan tidak pernah muncul di daftar."
            : undefined
        }
      >
        <input
          type={RAHASIA.has(tipe) ? "password" : "text"}
          value={nilai}
          onChange={(e) => setNilai(e.target.value)}
          className={kelasIsian}
        />
      </Isian>

      <Isian label={t("Keterangan")}>
        <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
      </Isian>

      {galat ? <p className="text-sm text-danger">{galat}</p> : null}
    </Dialog>
  );
}
