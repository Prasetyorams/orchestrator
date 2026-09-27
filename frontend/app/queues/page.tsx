"use client";

import { useCallback, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderInput, ListChecks, Trash2 } from "lucide-react";
import { OpenOrchestratorApi, errorText, type FolderNode, type Queue } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { DialogPindah } from "@/components/DialogPindah";
import { JudulHalaman, PerluFolder } from "@/components/HalamanFolder";

export default function Antrean() {
  return <PerluFolder>{(folder) => <IsiAntrean folder={folder} />}</PerluFolder>;
}

function IsiAntrean({ folder }: { folder: FolderNode }) {
  const { t, tp } = useT();
  const klien = useQueryClient();
  const { boleh } = useIzin();

  const [detail, setDetail] = useState<Queue | null>(null);
  const [baru, setBaru] = useState(false);
  const [pindah, setPindah] = useState<Queue | null>(null);
  const [galat, setGalat] = useState("");

  const antrean = useQuery({
    queryKey: ["queues", folder.id],
    queryFn: () => OpenOrchestratorApi.queues(folder.id),
    refetchInterval: 10_000,
  });

  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["queues"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }, [klien]);

  const hapus = useMutation({
    mutationFn: OpenOrchestratorApi.deleteQueue,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const tutupBaru = useCallback(() => setBaru(false), []);

  return (
    <div>
      <JudulHalaman
        judul={t("Queues")}
        aksi={
          boleh("queues.create") ? (
            <Button variant="primary" onClick={() => setBaru(true)}>
              {t("Tambah antrean")}
            </Button>
          ) : null
        }
      />

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={antrean.data ?? []}
          kunci={(q) => q.name}
          onBuka={setDetail}
          kosong={antrean.isLoading ? "Memuat..." : "Belum ada antrean di folder ini."}
          kolom={[
            {
              judul: "Nama",
              sel: (q) => (
                <div>
                  <p className="font-medium">{q.name}</p>
                  {q.description ? <p className="text-xs text-muted">{tp(q.description)}</p> : null}
                </div>
              ),
              urut: (q) => q.name,
            },
            { judul: "Baru", sel: (q) => <span className="tabular-nums">{q.newCount}</span>, urut: (q) => q.newCount },
            {
              judul: "Diproses",
              sel: (q) => <span className="tabular-nums text-info">{q.inProgressCount}</span>,
              urut: (q) => q.inProgressCount,
            },
            {
              judul: "Berhasil",
              sel: (q) => <span className="tabular-nums text-ok">{q.successfulCount}</span>,
              urut: (q) => q.successfulCount,
            },
            {
              judul: "Gagal",
              sel: (q) => (
                <span className={q.failedCount > 0 ? "tabular-nums text-danger" : "tabular-nums"}>{q.failedCount}</span>
              ),
              urut: (q) => q.failedCount,
            },
            { judul: "Total", sel: (q) => <span className="tabular-nums">{q.totalCount}</span>, urut: (q) => q.totalCount },
            {
              judul: "Percobaan",
              sel: (q) => <span className="text-muted">{t("maks {0}", q.maxRetries)}</span>,
              urut: (q) => q.maxRetries,
            },
            {
              judul: "",
              sel: (q) => (
                <div className="flex justify-end gap-0.5">
                  <IconButton label={t("Lihat butir")} onClick={() => setDetail(q)}>
                    <ListChecks size={16} />
                  </IconButton>
                  {boleh("queues.update") ? (
                    <IconButton label={t("Pindahkan ke folder lain")} onClick={() => setPindah(q)}>
                      <FolderInput size={16} />
                    </IconButton>
                  ) : null}
                  {boleh("queues.delete") ? (
                    <IconButton
                      label={t("Hapus")}
                      tone="danger"
                      onClick={() => {
                        // Jumlahnya disebut: butirnya ikut terhapus, dan tidak
                        // ada jalan untuk mengembalikannya.
                        if (window.confirm(t("Hapus antrean \"{0}\" beserta {1} butirnya?", q.name, q.totalCount))) {
                          hapus.mutate(q.name);
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

      <p className="mt-3 text-xs text-muted">{t("Klik ganda pada barisnya untuk melihat butir-butirnya.")}</p>

      <DialogButir antrean={detail} onTutup={() => setDetail(null)} />

      {baru ? <DialogAntrean folder={folder} onTutup={tutupBaru} onSelesai={segarkan} /> : null}

      {pindah ? (
        <DialogPindah
          judul={t("Pindahkan antrean \"{0}\"", pindah.name)}
          keterangan={t("Butir-butirnya ikut pindah bersama antreannya.")}
          folderSekarang={folder.id}
          onTutup={() => setPindah(null)}
          onPindah={async (tujuan) => {
            await OpenOrchestratorApi.moveQueue(pindah.name, tujuan);
            segarkan();
          }}
        />
      ) : null}
    </div>
  );
}

function DialogAntrean({
  folder,
  onTutup,
  onSelesai,
}: {
  folder: FolderNode;
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const [nama, setNama] = useState("");
  const [ket, setKet] = useState("");
  const [maks, setMaks] = useState(3);
  const [kembar, setKembar] = useState(false);
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: () =>
      OpenOrchestratorApi.saveQueue({
        name: nama.trim(),
        description: ket.trim() || undefined,
        maxRetries: maks,
        acceptDuplicates: kembar,
        folderId: folder.id,
      }),
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    setGalat("");
    if (!nama.trim()) return setGalat(t("Nama antrean wajib diisi."));
    if (maks < 0) return setGalat(t("Jumlah percobaan ulang tidak boleh negatif."));

    simpan.mutate();
  }

  return (
    <Dialog
      judul={t("Tambah antrean")}
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
      <Isian label={t("Nama")} petunjuk={t("Nama antrean unik untuk seluruh penyewa: robot memanggilnya lewat nama.")}>
        <input value={nama} onChange={(e) => setNama(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian label={t("Keterangan")}>
        <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian label={t("Percobaan ulang maksimal")} petunjuk={t("Butir yang gagal dicoba lagi sebanyak ini sebelum dianggap gagal permanen.")}>
        <input
          type="number"
          min={0}
          max={20}
          value={maks}
          onChange={(e) => setMaks(Number(e.target.value))}
          className={kelasIsian}
        />
      </Isian>

      <label className="mb-3 flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={kembar}
          onChange={(e) => setKembar(e.target.checked)}
          className="h-4 w-4 rounded border-line"
        />
        {t("Terima rujukan kembar")}
      </label>

      <Galat pesan={galat} />
    </Dialog>
  );
}

function DialogButir({ antrean, onTutup }: { antrean: Queue | null; onTutup: () => void }) {
  const { t } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();
  const [galat, setGalat] = useState("");

  const butir = useQuery({
    queryKey: ["queueItems", antrean?.name],
    queryFn: () => OpenOrchestratorApi.queueItems(antrean!.name, { limit: 500 }),
    enabled: !!antrean,
  });

  const hapus = useMutation({
    mutationFn: OpenOrchestratorApi.deleteQueueItem,
    onSuccess: () => {
      klien.invalidateQueries({ queryKey: ["queueItems", antrean?.name] });
      klien.invalidateQueries({ queryKey: ["queues"] });
    },
    onError: (e) => setGalat(errorText(e)),
  });

  if (!antrean) return null;

  return (
    <Dialog judul={antrean.name} terbuka onTutup={onTutup} lebar="max-w-5xl">
      <Galat pesan={galat} className="mb-3" />

      <DataTable
        data={butir.data ?? []}
        kunci={(b) => b.id}
        perHalaman={20}
        kosong={butir.isFetching ? "Memuat..." : "Belum ada data."}
        kolom={[
          { judul: "Rujukan", sel: (b) => <span className="font-medium">{b.reference ?? "-"}</span>, urut: (b) => b.reference },
          { judul: "Status", sel: (b) => <Badge value={b.status} />, urut: (b) => b.status },
          { judul: "Robot|satu", sel: (b) => <span className="text-muted">{b.robotName ?? "-"}</span>, urut: (b) => b.robotName },
          { judul: "Percobaan", sel: (b) => <span className="tabular-nums">{b.retries}</span>, urut: (b) => b.retries },
          {
            judul: "Isi",
            sel: (b) => (
              <code className="block max-w-xs truncate font-mono text-xs text-muted" title={b.content ?? ""}>
                {b.content ?? "-"}
              </code>
            ),
          },
          {
            judul: "Galat",
            sel: (b) => (
              <span className="block max-w-xs truncate text-xs text-danger" title={b.exception ?? ""}>
                {b.exception ?? ""}
              </span>
            ),
          },
          { judul: "Dibuat", sel: (b) => <span className="text-muted">{dateTimeOf(b.createdAt)}</span>, urut: (b) => b.createdAt },
          {
            judul: "",
            sel: (b) =>
              boleh("queues.delete") ? (
                <div className="flex justify-end">
                  <IconButton
                    label={t("Hapus")}
                    tone="danger"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${b.reference ?? b.id}"?`)) hapus.mutate(b.id);
                    }}
                  >
                    <Trash2 size={15} />
                  </IconButton>
                </div>
              ) : null,
          },
        ]}
      />
    </Dialog>
  );
}
