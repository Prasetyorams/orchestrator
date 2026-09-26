"use client";

import { useCallback, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText, type FolderNode, type Job } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat, labelKeadaan } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { BilahAlat, PerluFolder } from "@/components/HalamanFolder";

const KEADAAN = ["", "PENDING", "RUNNING", "STOPPING", "SUCCESSFUL", "FAULTED", "STOPPED"];

export default function Pekerjaan() {
  return <PerluFolder>{(folder) => <IsiPekerjaan folder={folder} />}</PerluFolder>;
}

function IsiPekerjaan({ folder }: { folder: FolderNode }) {
  const { t } = useT();
  const klien = useQueryClient();
  const { boleh } = useIzin();
  const bolehPilih = boleh("jobs.update") || boleh("jobs.delete");

  const [saring, setSaring] = useState("");
  const [detail, setDetail] = useState<Job | null>(null);
  const [mulai, setMulai] = useState(false);
  const [galat, setGalat] = useState("");

  const jobs = useQuery({
    queryKey: ["jobs", folder.id, saring],
    queryFn: () => ForgeHubApi.jobs({ state: saring || undefined, folderId: folder.id, limit: 500 }),
    refetchInterval: 5_000,
  });

  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["jobs"] });
    klien.invalidateQueries({ queryKey: ["processes"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }, [klien]);

  const hentikan = useMutation({
    mutationFn: ForgeHubApi.stopJob,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteJob,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const tutupMulai = useCallback(() => setMulai(false), []);

  return (
    <div>
      <BilahAlat
        aksi={
          <>
            <Button onClick={segarkan}>{t("Muat ulang")}</Button>
            {boleh("jobs.create") ? (
              <Button variant="primary" onClick={() => setMulai(true)}>
                {t("Jalankan")}
              </Button>
            ) : null}
          </>
        }
      >
        <select
          value={saring}
          onChange={(e) => setSaring(e.target.value)}
          aria-label={t("Keadaan")}
          className={cn(kelasIsian, "w-48")}
        >
          {KEADAAN.map((k) => (
            <option key={k} value={k}>
              {k ? t(labelKeadaan(k)) : t("Semua keadaan")}
            </option>
          ))}
        </select>
      </BilahAlat>

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={jobs.data ?? []}
          kunci={(j) => j.id}
          onBuka={setDetail}
          // Baris hanya bisa dipilih kalau ada yang boleh dilakukan atasnya.
          onPilih={bolehPilih ? () => {} : undefined}
          kosong={jobs.isLoading ? "Memuat..." : "Belum ada pekerjaan di folder ini."}
          aksiTerpilih={bolehPilih ? (dipilih) => (
              <>
                {boleh("jobs.update") ? (
                  <Button
                    onClick={() =>
                      dipilih
                        .filter((j) => j.state === "PENDING" || j.state === "RUNNING")
                        .forEach((j) => hentikan.mutate(j.id))
                    }
                  >
                    {t("Hentikan")}
                  </Button>
                ) : null}
                {boleh("jobs.delete") ? (
                  <Button
                    onClick={() => {
                      // Konfirmasi memuat JUMLAHNYA, bukan cuma "Anda yakin?".
                      // Memilih 200 baris dan menghapusnya karena mengira ada 2
                      // adalah kesalahan yang tidak bisa dibatalkan.
                      if (window.confirm(`${t("Yakin menghapus")} ${dipilih.length} ${t("Pekerjaan").toLowerCase()}?`)) {
                        dipilih.forEach((j) => hapus.mutate(j.id));
                      }
                    }}
                  >
                    {t("Hapus")}
                  </Button>
                ) : null}
              </>
          ) : undefined}
          kolom={[
            {
              judul: "Proses|satu",
              sel: (j) => <span className="font-medium">{j.processName}</span>,
              urut: (j) => j.processName,
            },
            { judul: "Robot|satu", sel: (j) => j.robotName ?? "-", urut: (j) => j.robotName },
            { judul: "Keadaan", sel: (j) => <Badge value={j.state} />, urut: (j) => j.state },
            { judul: "Sumber", sel: (j) => <span className="text-muted">{j.source}</span>, urut: (j) => j.source },
            { judul: "Prioritas", sel: (j) => j.priority, urut: (j) => j.priority },
            {
              judul: "Kemajuan",
              sel: (j) => <span className="tabular-nums text-muted">{j.progress}%</span>,
              urut: (j) => j.progress,
            },
            {
              judul: "Dibuat",
              sel: (j) => <span className="text-muted">{dateTimeOf(j.createdAt)}</span>,
              urut: (j) => j.createdAt,
            },
          ]}
        />
      </Card>

      <DialogDetail job={detail} onTutup={() => setDetail(null)} />

      {mulai ? <DialogJalankan folder={folder} onTutup={tutupMulai} onSelesai={segarkan} /> : null}
    </div>
  );
}

function DialogDetail({ job, onTutup }: { job: Job | null; onTutup: () => void }) {
  const { t, tp } = useT();

  // Log pekerjaan ini diambil hanya saat dialognya terbuka: log berada DI
  // DALAM prosesnya, bukan di halaman terpisah yang harus disaring sendiri.
  const log = useQuery({
    queryKey: ["logs", "job", job?.id],
    queryFn: () => ForgeHubApi.logs({ jobId: job!.id, limit: 500 }),
    enabled: !!job,
  });

  if (!job) return null;

  return (
    <Dialog judul={`${job.processName} — ${t(labelKeadaan(job.state))}`} terbuka onTutup={onTutup} lebar="max-w-3xl">
      <dl className="mb-4 grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
        <Medan label={t("Robot|satu")} nilai={job.robotName} />
        <Medan label={t("Mesin|satu")} nilai={job.machineName} />
        <Medan label={t("Sumber")} nilai={job.source} />
        <Medan label={t("Prioritas")} nilai={job.priority} />
        <Medan label={t("Dimulai")} nilai={dateTimeOf(job.startedAt)} />
        <Medan label={t("Selesai")} nilai={dateTimeOf(job.endedAt)} />
      </dl>

      {job.info ? <p className="mb-4 rounded-lg bg-slate-50 px-3 py-2 text-sm">{tp(job.info)}</p> : null}

      {job.inputJson ? <Kode judul="Input" isi={job.inputJson} /> : null}
      {job.outputJson ? <Kode judul="Output" isi={job.outputJson} /> : null}

      <h3 className="mb-2 mt-4 text-xs font-semibold uppercase tracking-wide text-muted">{t("Catatan")}</h3>

      <div className="max-h-72 overflow-y-auto rounded-lg border border-line">
        {log.data?.length ? (
          log.data.map((l) => (
            <div key={l.id} className="flex gap-3 border-b border-line/70 px-3 py-1.5 text-xs last:border-0">
              <span className="w-32 shrink-0 tabular-nums text-muted">{dateTimeOf(l.loggedAt)}</span>
              <span className="w-14 shrink-0 font-medium">{l.level}</span>
              <span className="break-all">{tp(l.message)}</span>
            </div>
          ))
        ) : (
          <p className="px-3 py-6 text-center text-sm text-muted">
            {log.isFetching ? t("Memuat...") : t("Belum ada data.")}
          </p>
        )}
      </div>
    </Dialog>
  );
}

function Medan({ label, nilai }: { label: string; nilai: string | null | undefined }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="font-medium text-ink">{nilai || "-"}</dd>
    </div>
  );
}

function Kode({ judul, isi }: { judul: string; isi: string }) {
  return (
    <div className="mb-3">
      <p className="mb-1 text-xs font-medium text-muted">{judul}</p>
      <pre className="overflow-x-auto rounded-lg bg-neutral-900 px-3 py-2 text-xs text-neutral-100 dark:bg-black/40">{isi}</pre>
    </div>
  );
}

/**
 * Jalankan proses dari folder ini, pada robot mana pun yang ditugaskan ke
 * folder ini, atau pada satu robot tertentu.
 *
 * Pilihan robotnya hanya robot folder ini: robot yang dipilih langsung
 * dianggap sah di mana pun foldernya (lihat JobRepository.ambilBerikutnya),
 * jadi menawarkan robot folder lain di sini berarti diam-diam melompati
 * penugasan folder.
 */
function DialogJalankan({
  folder,
  onTutup,
  onSelesai,
}: {
  folder: FolderNode;
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const proses = useQuery({ queryKey: ["processes", folder.id], queryFn: () => ForgeHubApi.processes(folder.id) });
  const { boleh } = useIzin();
  const robot = useQuery({
    queryKey: ["robots", folder.id],
    queryFn: () => ForgeHubApi.robots(folder.id),
    enabled: boleh("robots.read"),
  });

  const [nama, setNama] = useState("");
  const [namaRobot, setNamaRobot] = useState("");
  const [prioritas, setPrioritas] = useState("Normal");
  const [masukan, setMasukan] = useState("");
  const [galat, setGalat] = useState("");

  const jalankan = useMutation({
    mutationFn: ForgeHubApi.startJob,
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    if (!nama) return setGalat(t("Pilih prosesnya dulu."));

    // JSON diperiksa DI SINI. Dikirim apa adanya, yang gagal justru robotnya
    // saat sudah mulai berjalan — dan kegagalan di sana terlihat sebagai
    // automasi yang rusak, bukan sebagai salah ketik.
    if (masukan.trim()) {
      try {
        JSON.parse(masukan);
      } catch {
        return setGalat(t("Argumen masukan bukan JSON yang sah."));
      }
    }

    jalankan.mutate({
      processName: nama,
      folderId: folder.id,
      robotName: namaRobot || undefined,
      priority: prioritas,
      source: "Dashboard",
      inputJson: masukan.trim() || undefined,
    });
  }

  const tanpaRobot = robot.isSuccess && robot.data.length === 0;

  return (
    <Dialog
      judul={t("Jalankan")}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" onClick={kirim} disabled={jalankan.isPending}>
            {t("Jalankan")}
          </Button>
        </>
      }
    >
      <Isian label={t("Proses|satu")}>
        <select value={nama} onChange={(e) => setNama(e.target.value)} className={kelasIsian}>
          <option value="">—</option>
          {(proses.data ?? []).map((p) => (
            <option key={p.name} value={p.name}>
              {p.name}
            </option>
          ))}
        </select>
      </Isian>

      <Isian
        label={t("Robot|satu")}
        petunjuk={
          tanpaRobot
            ? t("Belum ada robot yang ditugaskan ke folder ini; pekerjaannya akan menunggu.")
            : t("Kosongkan supaya robot mana pun di folder ini yang sedang bebas mengambilnya.")
        }
      >
        <select value={namaRobot} onChange={(e) => setNamaRobot(e.target.value)} className={kelasIsian}>
          <option value="">{t("Robot mana pun di folder ini")}</option>
          {(robot.data ?? []).map((r) => (
            <option key={r.name} value={r.name}>
              {`${r.name} — ${t(labelKeadaan(r.status))}`}
            </option>
          ))}
        </select>
      </Isian>

      <Isian label={t("Prioritas")}>
        <select value={prioritas} onChange={(e) => setPrioritas(e.target.value)} className={kelasIsian}>
          <option>Low</option>
          <option>Normal</option>
          <option>High</option>
        </select>
      </Isian>

      <Isian label="Input JSON" petunjuk={t('Contoh: {"in_Nama":"Budi"}')}>
        <textarea
          rows={4}
          value={masukan}
          onChange={(e) => setMasukan(e.target.value)}
          className={`${kelasIsian} font-mono`}
        />
      </Isian>

      <Galat pesan={galat} />
    </Dialog>
  );
}
