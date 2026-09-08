"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText, unduh, type Bucket } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Button, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog } from "@/components/Dialog";

/** Bita menjadi satuan yang enak dibaca. */
function ukuran(b: number) {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

export default function Gudang() {
  const { t } = useT();
  const [detail, setDetail] = useState<Bucket | null>(null);

  const gudang = useQuery({ queryKey: ["buckets"], queryFn: ForgeHubApi.buckets });

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-ink">{t("Gudang")}</h1>

      <p className="text-sm text-muted">
        Berkas yang dipakai bersama oleh proses — masukan, keluaran, lampiran. Klik ganda untuk
        melihat isinya.
      </p>

      <Card>
        <DataTable
          data={gudang.data ?? []}
          kunci={(g) => g.name}
          onBuka={setDetail}
          kolom={[
            { judul: "Nama", sel: (g) => <span className="font-medium">{g.name}</span>, urut: (g) => g.name },
            { judul: "Keterangan", sel: (g) => <span className="text-muted">{g.description ?? "-"}</span> },
            {
              judul: "Berkas",
              sel: (g) => <span className="tabular-nums">{g.fileCount}</span>,
              urut: (g) => g.fileCount,
            },
            {
              judul: "Ukuran",
              sel: (g) => <span className="tabular-nums text-muted">{ukuran(g.totalBytes)}</span>,
              urut: (g) => g.totalBytes,
            },
            {
              judul: "Dibuat",
              sel: (g) => <span className="text-muted">{dateTimeOf(g.createdAt)}</span>,
              urut: (g) => g.createdAt,
            },
          ]}
        />
      </Card>

      <DialogBerkas gudang={detail} onTutup={() => setDetail(null)} />
    </div>
  );
}

function DialogBerkas({ gudang, onTutup }: { gudang: Bucket | null; onTutup: () => void }) {
  const { t } = useT();
  const klien = useQueryClient();
  const [galat, setGalat] = useState("");

  const berkas = useQuery({
    queryKey: ["bucketFiles", gudang?.name],
    queryFn: () => ForgeHubApi.bucketFiles(gudang!.name),
    enabled: !!gudang,
  });

  function segarkan() {
    klien.invalidateQueries({ queryKey: ["bucketFiles", gudang?.name] });
    klien.invalidateQueries({ queryKey: ["buckets"] });
  }

  const hapus = useMutation({
    mutationFn: (id: string) => ForgeHubApi.deleteBucketFile(gudang!.name, id),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const unggah = useMutation({
    mutationFn: (f: File) =>
      new Promise<void>((selesai, gagal) => {
        const pembaca = new FileReader();

        pembaca.onerror = () => gagal(new Error("Berkasnya tidak bisa dibaca."));

        pembaca.onload = async () => {
          // Hasilnya berbentuk data URL; yang dikirim hanya bagian sesudah
          // koma, karena awalannya bukan bagian dari isi berkasnya.
          const b64 = String(pembaca.result).split(",")[1] ?? "";

          try {
            await ForgeHubApi.uploadBucketFile(gudang!.name, {
              fileName: f.name,
              contentBase64: b64,
              contentType: f.type || "application/octet-stream",
            });
            selesai();
          } catch (e) {
            gagal(e);
          }
        };

        pembaca.readAsDataURL(f);
      }),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  if (!gudang) return null;

  return (
    <Dialog
      judul={gudang.name}
      terbuka
      onTutup={onTutup}
      lebar="max-w-3xl"
      aksi={
        <label className="inline-flex cursor-pointer items-center justify-center rounded-lg bg-sidebar px-3.5 py-2 text-sm font-medium text-white hover:bg-sidebarHover">
          {unggah.isPending ? t("Memuat...") : t("Unggah")}
          <input
            type="file"
            className="hidden"
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) unggah.mutate(f);

              // Dikosongkan supaya memilih berkas yang SAMA dua kali tetap
              // memicu onChange; tanpa ini unggah ulang terlihat tidak bekerja.
              e.target.value = "";
            }}
          />
        </label>
      }
    >
      {galat ? <p className="mb-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-danger">{galat}</p> : null}

      <DataTable
        data={berkas.data ?? []}
        kunci={(b) => b.id}
        perHalaman={20}
        kolom={[
          { judul: "Nama", sel: (b) => <span className="font-medium">{b.fileName}</span>, urut: (b) => b.fileName },
          {
            judul: "Ukuran",
            sel: (b) => <span className="tabular-nums text-muted">{ukuran(b.sizeBytes)}</span>,
            urut: (b) => b.sizeBytes,
          },
          {
            judul: "Diunggah",
            sel: (b) => <span className="text-muted">{dateTimeOf(b.uploadedAt)}</span>,
            urut: (b) => b.uploadedAt,
          },
          { judul: "Oleh", sel: (b) => <span className="text-muted">{b.uploadedBy ?? "-"}</span> },
          {
            judul: "",
            sel: (b) => (
              <div className="flex justify-end gap-1.5">
                <Button
                  variant="ghost"
                  onClick={() =>
                    unduh(
                      `/api/buckets/${encodeURIComponent(gudang.name)}/files/${b.id}/content`,
                      b.fileName,
                    ).catch((e) => setGalat(errorText(e)))
                  }
                >
                  {t("Unduh")}
                </Button>
                <Button
                  variant="ghost"
                  onClick={() => {
                    if (window.confirm(`${t("Yakin menghapus")} "${b.fileName}"?`)) hapus.mutate(b.id);
                  }}
                >
                  {t("Hapus")}
                </Button>
              </div>
            ),
          },
        ]}
      />
    </Dialog>
  );
}
