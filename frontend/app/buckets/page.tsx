"use client";

import { useCallback, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, FolderInput, FolderOpen, Trash2 } from "lucide-react";
import { ForgeHubApi, errorText, unduh, type Bucket, type FolderNode } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf, ukuranBerkas } from "@/lib/utils";
import { Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { DialogPindah } from "@/components/DialogPindah";
import { JudulHalaman, PerluFolder } from "@/components/HalamanFolder";

/**
 * Ember penyimpanan: berkas yang dipakai bersama oleh proses — masukan,
 * keluaran, lampiran — seperti Storage Buckets di Orchestrator.
 */
export default function EmberPenyimpanan() {
  return <PerluFolder>{(folder) => <IsiEmber folder={folder} />}</PerluFolder>;
}

function IsiEmber({ folder }: { folder: FolderNode }) {
  const { t, tp } = useT();
  const klien = useQueryClient();
  const { boleh } = useIzin();

  const [detail, setDetail] = useState<Bucket | null>(null);
  const [baru, setBaru] = useState(false);
  const [pindah, setPindah] = useState<Bucket | null>(null);
  const [galat, setGalat] = useState("");

  const ember = useQuery({ queryKey: ["buckets", folder.id], queryFn: () => ForgeHubApi.buckets(folder.id) });

  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["buckets"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }, [klien]);

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteBucket,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const tutupBaru = useCallback(() => setBaru(false), []);

  return (
    <div>
      <JudulHalaman
        judul={t("Ember Penyimpanan")}
        aksi={
          boleh("buckets.create") ? (
            <Button variant="primary" onClick={() => setBaru(true)}>
              {t("Tambah ember")}
            </Button>
          ) : null
        }
      />

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={ember.data ?? []}
          kunci={(g) => g.name}
          onBuka={setDetail}
          kosong={ember.isLoading ? "Memuat..." : "Belum ada ember penyimpanan di folder ini."}
          kolom={[
            { judul: "Nama", sel: (g) => <span className="font-medium">{g.name}</span>, urut: (g) => g.name },
            {
              judul: "Keterangan",
              sel: (g) => <span className="text-muted">{g.description ? tp(g.description) : "-"}</span>,
            },
            { judul: "Berkas", sel: (g) => <span className="tabular-nums">{g.fileCount}</span>, urut: (g) => g.fileCount },
            {
              judul: "Ukuran",
              sel: (g) => <span className="tabular-nums text-muted">{ukuranBerkas(g.totalBytes)}</span>,
              urut: (g) => g.totalBytes,
            },
            {
              judul: "Dibuat",
              sel: (g) => <span className="text-muted">{dateTimeOf(g.createdAt)}</span>,
              urut: (g) => g.createdAt,
            },
            {
              judul: "",
              sel: (g) => (
                <div className="flex justify-end gap-0.5">
                  <IconButton label={t("Buka isinya")} onClick={() => setDetail(g)}>
                    <FolderOpen size={16} />
                  </IconButton>
                  {boleh("buckets.update") ? (
                    <IconButton label={t("Pindahkan ke folder lain")} onClick={() => setPindah(g)}>
                      <FolderInput size={16} />
                    </IconButton>
                  ) : null}
                  {boleh("buckets.delete") ? (
                    <IconButton
                      label={t("Hapus")}
                      tone="danger"
                      onClick={() => {
                        if (window.confirm(t("Hapus ember \"{0}\" beserta {1} berkasnya?", g.name, g.fileCount))) {
                          hapus.mutate(g.name);
                        }
                      }}
                    >
                      <Trash2 size={16} />
                    </IconButton>
                  ) : null}
                </div>
              ),
            },
          ]}
        />
      </Card>

      <p className="mt-3 text-xs text-muted">
        {t("Berkas yang dipakai bersama oleh proses — masukan, keluaran, lampiran. Klik ganda untuk melihat isinya.")}
      </p>

      <DialogBerkas ember={detail} onTutup={() => setDetail(null)} />

      {baru ? <DialogEmber folder={folder} onTutup={tutupBaru} onSelesai={segarkan} /> : null}

      {pindah ? (
        <DialogPindah
          judul={t("Pindahkan ember \"{0}\"", pindah.name)}
          keterangan={t("Berkas-berkasnya ikut pindah bersama embernya.")}
          folderSekarang={folder.id}
          onTutup={() => setPindah(null)}
          onPindah={async (tujuan) => {
            await ForgeHubApi.moveBucket(pindah.name, tujuan);
            segarkan();
          }}
        />
      ) : null}
    </div>
  );
}

function DialogEmber({ folder, onTutup, onSelesai }: { folder: FolderNode; onTutup: () => void; onSelesai: () => void }) {
  const { t } = useT();

  const [nama, setNama] = useState("");
  const [ket, setKet] = useState("");
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: () =>
      ForgeHubApi.saveBucket({ name: nama.trim(), description: ket.trim() || undefined, folderId: folder.id }),
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    setGalat("");
    if (!nama.trim()) return setGalat(t("Nama ember wajib diisi."));

    simpan.mutate();
  }

  return (
    <Dialog
      judul={t("Tambah ember")}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" onClick={kirim} disabled={simpan.isPending}>
            {simpan.isPending ? t("Menyimpan...") : t("Simpan")}
          </Button>
        </>
      }
    >
      <Isian label={t("Nama")} petunjuk={t("Nama ember unik untuk seluruh penyewa: robot memanggilnya lewat nama.")}>
        <input value={nama} onChange={(e) => setNama(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian label={t("Keterangan")}>
        <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
      </Isian>

      <Galat pesan={galat} />
    </Dialog>
  );
}

function DialogBerkas({ ember, onTutup }: { ember: Bucket | null; onTutup: () => void }) {
  const { t } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();
  const [galat, setGalat] = useState("");

  const berkas = useQuery({
    queryKey: ["bucketFiles", ember?.name],
    queryFn: () => ForgeHubApi.bucketFiles(ember!.name),
    enabled: !!ember,
  });

  function segarkan() {
    klien.invalidateQueries({ queryKey: ["bucketFiles", ember?.name] });
    klien.invalidateQueries({ queryKey: ["buckets"] });
  }

  const hapus = useMutation({
    mutationFn: (id: string) => ForgeHubApi.deleteBucketFile(ember!.name, id),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const unggah = useMutation({
    mutationFn: (f: File) =>
      new Promise<void>((selesai, gagal) => {
        const pembaca = new FileReader();

        pembaca.onerror = () => gagal(new Error(t("Berkasnya tidak bisa dibaca.")));

        pembaca.onload = async () => {
          // Hasilnya berbentuk data URL; yang dikirim hanya bagian sesudah
          // koma, karena awalannya bukan bagian dari isi berkasnya.
          const b64 = String(pembaca.result).split(",")[1] ?? "";

          try {
            await ForgeHubApi.uploadBucketFile(ember!.name, {
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

  if (!ember) return null;

  return (
    <Dialog
      judul={ember.name}
      terbuka
      onTutup={onTutup}
      lebar="max-w-3xl"
      aksi={
        boleh("buckets.update") ? (
          <label className="inline-flex cursor-pointer items-center justify-center rounded-lg bg-brand px-3.5 py-2 text-sm font-medium text-white hover:bg-brandHover">
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
        ) : undefined
      }
    >
      <Galat pesan={galat} className="mb-3" />

      <DataTable
        data={berkas.data ?? []}
        kunci={(b) => b.id}
        perHalaman={20}
        kosong={berkas.isFetching ? "Memuat..." : "Ember ini masih kosong."}
        kolom={[
          { judul: "Nama", sel: (b) => <span className="font-medium">{b.fileName}</span>, urut: (b) => b.fileName },
          {
            judul: "Ukuran",
            sel: (b) => <span className="tabular-nums text-muted">{ukuranBerkas(b.sizeBytes)}</span>,
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
              <div className="flex justify-end gap-0.5">
                <IconButton
                  label={t("Unduh")}
                  onClick={() =>
                    // Alamat penuh ke API, bukan jalur relatif: unduh() tidak
                    // memakai baseURL, dan jalur relatif akan mendarat di
                    // server dasbor, bukan di ForgeHub.
                    unduh(ForgeHubApi.bucketFileUrl(ember.name, b.id), b.fileName).catch((e) => setGalat(errorText(e)))
                  }
                >
                  <Download size={16} />
                </IconButton>
                {boleh("buckets.delete") ? (
                  <IconButton
                    label={t("Hapus")}
                    tone="danger"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${b.fileName}"?`)) hapus.mutate(b.id);
                    }}
                  >
                    <Trash2 size={16} />
                  </IconButton>
                ) : null}
              </div>
            ),
          },
        ]}
      />
    </Dialog>
  );
}
