"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  CircleStop,
  CirclePause,
  CirclePlay,
  CircleX,
  FileText,
  List,
  MonitorPlay,
  RotateCw,
} from "lucide-react";
import { KEADAAN_BERJALAN, OpenOrchestratorApi, errorText, type FolderNode, type Job, type JobAttachment } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat, labelKeadaan } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, kelasIsian } from "@/components/Dialog";
import { DialogKonfirmasi } from "@/components/DialogKonfirmasi";
import { MenuAksi, type ItemMenu } from "@/components/MenuAksi";
import { IkonPrioritas, MulaiJob, labelPrioritas } from "@/components/MulaiJob";
import { useNotifikasi } from "@/components/Notifikasi";
import { BilahAlat, PerluFolder } from "@/components/HalamanFolder";

const KEADAAN = [
  "",
  "PENDING",
  "ASSIGNED",
  "PREPARING_SESSION",
  "RUNNING",
  "UNRESPONSIVE",
  "STOPPING",
  "SUCCESSFUL",
  "FAULTED",
  "STOPPED",
];

/** Dipegang robot: sudah diambil, belum selesai — ada proses yang bisa dihentikan atau dimatikan. */
const DIPEGANG_ROBOT = ["ASSIGNED", "PREPARING_SESSION", "RUNNING", "UNRESPONSIVE", "STOPPING"];

const SELESAI = ["SUCCESSFUL", "FAULTED", "STOPPED"];

type AksiJob = "stop" | "kill" | "pause" | "resume" | "restart";

export default function Pekerjaan() {
  return <PerluFolder>{(folder) => <IsiPekerjaan folder={folder} />}</PerluFolder>;
}

function IsiPekerjaan({ folder }: { folder: FolderNode }) {
  const { t } = useT();
  const klien = useQueryClient();
  const router = useRouter();
  const beritahu = useNotifikasi();
  const { boleh } = useIzin();
  const bolehPilih = boleh("jobs.update") || boleh("jobs.delete");

  const [saring, setSaring] = useState("");
  const [detail, setDetail] = useState<Job | null>(null);
  const [mulai, setMulai] = useState(false);
  const [rekaman, setRekaman] = useState<Job | null>(null);
  const [konfirmasi, setKonfirmasi] = useState<{ job: Job; aksi: "stop" | "kill" } | null>(null);
  const [galat, setGalat] = useState("");

  const jobs = useQuery({
    queryKey: ["jobs", folder.id, saring],
    queryFn: () => OpenOrchestratorApi.jobs({ state: saring || undefined, folderId: folder.id, limit: 500 }),
    refetchInterval: 5_000,
  });

  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["jobs"] });
    klien.invalidateQueries({ queryKey: ["job"] });
    klien.invalidateQueries({ queryKey: ["processes"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }, [klien]);

  const aksi = useMutation({
    mutationFn: ({ job, jenis }: { job: Job; jenis: AksiJob }) =>
      ({
        stop: OpenOrchestratorApi.stopJob,
        kill: OpenOrchestratorApi.killJob,
        pause: OpenOrchestratorApi.pauseJob,
        resume: OpenOrchestratorApi.resumeJob,
        restart: OpenOrchestratorApi.restartJob,
      })[jenis](job.id),
    onMutate: () => setGalat(""),
    onSuccess: (_, { jenis }) => {
      segarkan();
      setKonfirmasi(null);
      beritahu(
        t(
          {
            stop: "Permintaan berhenti dikirim ke robot.",
            kill: "Permintaan mematikan paksa dikirim ke robot.",
            pause: "Permintaan jeda dikirim ke robot.",
            resume: "Permintaan lanjut dikirim ke robot.",
            restart: "Job berhasil dijalankan ulang.",
          }[jenis],
        ),
      );
    },
    onError: (e) => setGalat(errorText(e)),
  });

  const hentikanBanyak = useMutation({
    mutationFn: (daftar: Job[]) => Promise.all(daftar.map((j) => OpenOrchestratorApi.stopJob(j.id))),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const hapus = useMutation({
    mutationFn: OpenOrchestratorApi.deleteJob,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const tutupMulai = useCallback(() => setMulai(false), []);

  /**
   * Isi menu tiga titik untuk satu job. Aksi yang tidak berlaku tetap tampil,
   * nonaktif, dengan alasannya di petunjuk.
   */
  function menuJob(j: Job): ItemMenu[] {
    const bolehUbah = boleh("jobs.update");
    const bolehBuat = boleh("jobs.create");
    const dipegang = DIPEGANG_ROBOT.includes(j.state);
    const selesai = SELESAI.includes(j.state);
    const tanpaIzin = t("Peran Anda tidak mengizinkan aksi ini.");

    // Hentikan: yang menunggu dibatalkan, yang dipegang robot diminta berhenti.
    const bisaHenti = j.state === "PENDING" || (dipegang && j.state !== "STOPPING");
    // Jeda berlaku untuk yang sedang berjalan; Lanjutkan untuk yang dijeda dari dasbor.
    const dijedaLokal = j.paused && j.pauseSource === "local" && !j.pauseRequested;
    const bisaJeda = j.state === "RUNNING" && !j.pauseRequested && !j.paused;
    const bisaLanjut = j.state === "RUNNING" && (j.pauseRequested || (j.paused && !dijedaLokal));
    const adaRekaman = (j.attachmentCount ?? 0) > 0;

    return [
      {
        label: t("Hentikan"),
        ikon: CircleStop,
        nonaktif: !bolehUbah || !bisaHenti,
        petunjuk: !bolehUbah
          ? tanpaIzin
          : j.state === "STOPPING"
            ? t("Sedang dihentikan. Pakai Matikan untuk menghentikannya paksa.")
            : !bisaHenti
              ? t("Pekerjaan ini sudah selesai.")
              : j.state === "PENDING"
                ? t("Batalkan sebelum diambil robot.")
                : t("Minta robot menghentikan pekerjaan ini dengan rapi."),
        onPilih: () => setKonfirmasi({ job: j, aksi: "stop" }),
      },
      {
        label: t("Matikan|job"),
        ikon: CircleX,
        bahaya: true,
        nonaktif: !bolehUbah || !dipegang,
        petunjuk: !bolehUbah
          ? tanpaIzin
          : j.state === "PENDING"
            ? t("Belum diambil robot — belum ada proses yang perlu dimatikan.")
            : !dipegang
              ? t("Pekerjaan ini sudah selesai.")
              : t("Hentikan paksa, tanpa menunggu robot berhenti dengan rapi."),
        onPilih: () => setKonfirmasi({ job: j, aksi: "kill" }),
      },
      {
        label: t("Jeda"),
        ikon: CirclePause,
        nonaktif: !bolehUbah || !bisaJeda,
        petunjuk: !bolehUbah
          ? tanpaIzin
          : j.paused || j.pauseRequested
            ? t("Sudah dijeda atau sedang dijeda.")
            : j.state !== "RUNNING"
              ? t("Hanya pekerjaan yang sedang berjalan yang bisa dijeda.")
              : t("Tahan workflow sebelum activity berikutnya. Robot yang belum mendukung jeda mengabaikannya."),
        onPilih: () => aksi.mutate({ job: j, jenis: "pause" }),
      },
      {
        label: t("Lanjutkan"),
        ikon: CirclePlay,
        nonaktif: !bolehUbah || !bisaLanjut,
        petunjuk: !bolehUbah
          ? tanpaIzin
          : dijedaLokal
            ? t("Dijeda langsung di PC robot — lanjutkan dari sana.")
            : selesai
              ? t("Pekerjaan yang sudah selesai tidak bisa dilanjutkan dari tengah. Pakai Jalankan Ulang.")
              : !bisaLanjut
                ? t("Pekerjaan ini tidak sedang dijeda.")
                : t("Lanjutkan pekerjaan yang dijeda dari dasbor."),
        onPilih: () => aksi.mutate({ job: j, jenis: "resume" }),
      },
      {
        label: t("Jalankan Ulang"),
        ikon: RotateCw,
        nonaktif: !bolehBuat || j.state === "PENDING",
        petunjuk: !bolehBuat
          ? tanpaIzin
          : j.state === "PENDING"
            ? t("Pekerjaan ini belum berjalan.")
            : t("Buat pekerjaan baru dengan konfigurasi yang sama. Pekerjaan ini tetap tercatat."),
        onPilih: () => aksi.mutate({ job: j, jenis: "restart" }),
      },
      { pemisah: true },
      {
        label: t("Buka Rekaman"),
        ikon: MonitorPlay,
        nonaktif: !adaRekaman,
        petunjuk: adaRekaman
          ? t("{0} rekaman dari robot.", j.attachmentCount ?? 0)
          : t("Tidak ada rekaman untuk pekerjaan ini. Robot Agent menyimpan tangkapan layar saat pekerjaan gagal."),
        onPilih: () => setRekaman(j),
      },
      {
        label: t("Lihat Log Job Ini"),
        ikon: FileText,
        nonaktif: !boleh("logs.read"),
        petunjuk: boleh("logs.read") ? undefined : tanpaIzin,
        onPilih: () => router.push(`/monitoring/logs?jobId=${encodeURIComponent(j.id)}`),
      },
      {
        label: t("Lihat Semua Log Proses"),
        ikon: List,
        nonaktif: !boleh("logs.read"),
        petunjuk: boleh("logs.read") ? undefined : tanpaIzin,
        onPilih: () => router.push(`/monitoring/logs?process=${encodeURIComponent(j.processName)}`),
      },
    ];
  }

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

      {/* Selama dialog konfirmasi terbuka, galatnya tampil di dialog itu. */}
      <Galat pesan={konfirmasi ? "" : galat} className="mb-4" />

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
                  <Button onClick={() => hentikanBanyak.mutate(dipilih.filter((j) => KEADAAN_BERJALAN.includes(j.state)))}>
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
              sel: (j) => (
                <span className="font-medium">
                  {j.processName}
                  {j.attempt && j.attempt > 1 ? (
                    <span className="ml-2 text-xs font-normal text-muted">{t("percobaan {0}", j.attempt)}</span>
                  ) : null}
                </span>
              ),
              urut: (j) => j.processName,
            },
            {
              judul: "Robot|satu",
              sel: (j) => (
                <span className="flex flex-col">
                  <span>{j.robotName ?? j.targetRobotName ?? "-"}</span>
                  {j.machineName ?? j.targetMachineName ? (
                    <span className="text-[11px] text-muted">{j.machineName ?? j.targetMachineName}</span>
                  ) : null}
                </span>
              ),
              urut: (j) => j.robotName ?? j.targetRobotName,
            },
            {
              judul: "Runtime",
              sel: (j) => <span className="text-muted">{j.runtimeType ? t(j.runtimeType) : "-"}</span>,
              urut: (j) => j.runtimeType,
            },
            {
              judul: "Keadaan",
              sel: (j) => <SelKeadaan job={j} />,
              urut: (j) => (j.paused ? "RUNNING-PAUSED" : j.state),
            },
            { judul: "Sumber", sel: (j) => <span className="text-muted">{j.source}</span>, urut: (j) => j.source },
            {
              judul: "Prioritas",
              sel: (j) => (
                <span className="inline-flex items-center gap-1.5">
                  <IkonPrioritas prioritas={j.priority} />
                  {t(labelPrioritas(j.priority))}
                </span>
              ),
              urut: (j) => ({ High: 0, Normal: 1, Low: 2 } as Record<string, number>)[j.priority] ?? 3,
            },
            {
              judul: "Dibuat",
              sel: (j) => <span className="text-muted">{dateTimeOf(j.createdAt)}</span>,
              urut: (j) => j.createdAt,
            },
            {
              judul: "",
              kelas: "w-12 text-right",
              sel: (j) => <MenuAksi label={t("Aksi untuk {0}", j.processName)} item={menuJob(j)} />,
            },
          ]}
        />
      </Card>

      <DialogDetail job={detail} onTutup={() => setDetail(null)} />

      {rekaman ? <DialogRekaman job={rekaman} onTutup={() => setRekaman(null)} /> : null}

      {konfirmasi ? (
        <DialogKonfirmasi
          judul={konfirmasi.aksi === "kill" ? t("Matikan paksa pekerjaan ini?") : t("Hentikan pekerjaan ini?")}
          label={konfirmasi.aksi === "kill" ? t("Matikan|job") : t("Hentikan")}
          bahaya={konfirmasi.aksi === "kill"}
          sibuk={aksi.isPending}
          galat={galat}
          onTutup={() => {
            setKonfirmasi(null);
            setGalat("");
          }}
          onYa={() => aksi.mutate({ job: konfirmasi.job, jenis: konfirmasi.aksi })}
        >
          <p>
            <span className="font-medium">{konfirmasi.job.processName}</span>
            {konfirmasi.job.robotName ? ` — ${konfirmasi.job.robotName}` : ""} · {t(labelKeadaan(konfirmasi.job.state))}
          </p>
          {konfirmasi.aksi === "kill" ? (
            <>
              <p>
                {t("Robot akan mematikan proses workflow SEKARANG, tanpa menunggu activity yang sedang berjalan selesai dan tanpa jeda berhenti rapi.")}
              </p>
              <p className="text-muted">
                {t("Pekerjaan yang setengah jalan tidak dibereskan: berkas yang sedang ditulis bisa rusak, dan aplikasi yang dibuka workflow ditutup paksa. Pakai Hentikan kalau robot masih bisa berhenti dengan rapi.")}
              </p>
            </>
          ) : konfirmasi.job.state === "PENDING" ? (
            <p>{t("Pekerjaan ini belum diambil robot, jadi langsung dibatalkan.")}</p>
          ) : (
            <p>
              {t("Robot diminta berhenti dengan rapi pada denyut berikutnya. Kalau robotnya tidak berhenti, pakai Matikan.")}
            </p>
          )}
        </DialogKonfirmasi>
      ) : null}

      {mulai ? <MulaiJob folder={folder} onTutup={tutupMulai} /> : null}

      <p className="mt-3 text-xs text-muted">
        {t("Klik ganda pada barisnya untuk melihat rincian dan catatannya.")}
      </p>
    </div>
  );
}

/** Keadaan job, beserta yang sedang ditunggu dari robotnya: jeda, lanjut, atau mati paksa. */
function SelKeadaan({ job: j }: { job: Job }) {
  const { t } = useT();

  let catatan: ReactNode = null;

  if (j.state === "RUNNING" && j.pauseRequested && !j.paused) catatan = t("Menunggu robot menjeda…");
  else if (j.state === "RUNNING" && !j.pauseRequested && j.paused && j.pauseSource === "dashboard") catatan = t("Menunggu robot melanjutkan…");
  else if (j.state === "RUNNING" && j.paused && j.pauseSource === "local") catatan = t("Dijeda di PC robot");
  else if (j.state === "STOPPING" && j.killRequestedAt) catatan = t("Dimatikan paksa…");
  else if (j.errorCode) catatan = j.errorCode;

  return (
    <span className="flex flex-col items-start gap-0.5">
      {j.state === "RUNNING" && j.paused ? <Badge value="PAUSED" /> : <Badge value={j.state} />}
      {catatan ? <span className="text-[11px] text-muted">{catatan}</span> : null}
    </span>
  );
}

function DialogDetail({ job: ringkas, onTutup }: { job: Job | null; onTutup: () => void }) {
  const { t, tp } = useT();

  // Rincian lengkap — konteks eksekusi, paket, lampiran — hanya ada di
  // jawaban satu job; daftar membawa ringkasannya saja.
  const rincian = useQuery({
    queryKey: ["job", ringkas?.id],
    queryFn: () => OpenOrchestratorApi.job(ringkas!.id),
    enabled: !!ringkas,
    refetchInterval: 5_000,
  });

  // Log pekerjaan ini diambil hanya saat dialognya terbuka: log berada DI
  // DALAM prosesnya, bukan di halaman terpisah yang harus disaring sendiri.
  const log = useQuery({
    queryKey: ["logs", "job", ringkas?.id],
    queryFn: () => OpenOrchestratorApi.logs({ jobId: ringkas!.id, limit: 500 }),
    enabled: !!ringkas,
  });

  const lampiran = useQuery({
    queryKey: ["job", ringkas?.id, "lampiran"],
    queryFn: () => OpenOrchestratorApi.jobAttachments(ringkas!.id),
    enabled: !!ringkas && (rincian.data?.attachmentCount ?? 0) > 0,
  });

  if (!ringkas) return null;

  const job = rincian.data ?? ringkas;
  const judulKeadaan = job.state === "RUNNING" && job.paused ? t("Dijeda") : t(labelKeadaan(job.state));

  return (
    <Dialog judul={`${job.processName} — ${judulKeadaan}`} terbuka onTutup={onTutup} lebar="max-w-3xl">
      <dl className="mb-4 grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
        <Medan label={t("Robot|satu")} nilai={job.robotName} />
        <Medan label={t("Mesin|satu")} nilai={job.machineName} />
        <Medan label={t("Tipe runtime")} nilai={job.runtimeType ? t(job.runtimeType) : null} />
        <Medan label={t("Akun yang diminta")} nilai={job.targetRobotName ?? t("Mana pun")} />
        <Medan label={t("Mesin yang diminta")} nilai={job.targetMachineName ?? t("Mana pun")} />
        <Medan label={t("Prioritas")} nilai={t(labelPrioritas(job.priority))} />
        <Medan label={t("Sumber")} nilai={job.source} />
        <Medan label={t("Dimulai")} nilai={dateTimeOf(job.startedAt)} />
        <Medan label={t("Selesai")} nilai={dateTimeOf(job.endedAt)} />
        {job.pausedSeconds ? <Medan label={t("Lama dijeda")} nilai={t("{0} detik", job.pausedSeconds)} /> : null}
        {job.contractVersion === 2 ? (
          <>
            <Medan label={t("Percobaan")} nilai={String(job.attempt ?? 1)} />
            <Medan label={t("Sesi Windows")} nilai={job.sessionId != null ? `#${job.sessionId} · ${job.windowsUser ?? "-"}` : null} />
            <Medan label={t("PID Executor")} nilai={job.executorPid != null ? String(job.executorPid) : null} />
            <Medan label={t("Paket|satu")} nilai={job.packageName ? `${job.packageName} ${job.packageVersion ?? ""}` : null} />
            <Medan
              label={t("Batas waktu")}
              nilai={job.timeoutSeconds ? t("{0} menit", Math.round(job.timeoutSeconds / 60)) : t("Tanpa batas")}
            />
            <Medan label={t("Kode galat")} nilai={job.errorCode} />
          </>
        ) : null}
      </dl>

      {job.failureInferred ? (
        <p className="mb-3 text-xs text-warn">
          {t("Kegagalan ini disimpulkan Orchestrator karena robotnya tidak memberi kabar; laporan asli robot yang datang belakangan bisa menggantikannya.")}
        </p>
      ) : null}

      {job.retryOf ? <p className="mb-3 text-xs text-muted">{t("Percobaan ulang otomatis dari pekerjaan {0}.", job.retryOf)}</p> : null}
      {job.restartedFrom ? <p className="mb-3 text-xs text-muted">{t("Dijalankan ulang dari pekerjaan {0}.", job.restartedFrom)}</p> : null}

      {job.info ? <p className="mb-4 rounded-lg bg-slate-50 px-3 py-2 text-sm">{tp(job.info)}</p> : null}

      {job.inputJson ? <Kode judul="Input" isi={job.inputJson} /> : null}
      {job.outputJson ? <Kode judul="Output" isi={job.outputJson} /> : null}

      {lampiran.data?.length ? (
        <>
          <h3 className="mb-2 mt-4 text-xs font-semibold uppercase tracking-wide text-muted">{t("Rekaman")}</h3>
          <div className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-3">
            {lampiran.data.map((a) => (
              <Gambar key={a.id} jobId={job.id} lampiran={a} />
            ))}
          </div>
        </>
      ) : null}

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

/**
 * Rekaman eksekusi job: tangkapan layar (dan video, kalau robot mengirimnya)
 * yang dilampirkan Robot Agent — terutama saat pekerjaan gagal.
 */
function DialogRekaman({ job, onTutup }: { job: Job; onTutup: () => void }) {
  const { t } = useT();

  const lampiran = useQuery({
    queryKey: ["job", job.id, "lampiran"],
    queryFn: () => OpenOrchestratorApi.jobAttachments(job.id),
  });

  return (
    <Dialog judul={`${t("Rekaman")} — ${job.processName}`} terbuka onTutup={onTutup} lebar="max-w-4xl">
      {lampiran.isLoading ? (
        <p className="py-8 text-center text-sm text-muted">{t("Memuat...")}</p>
      ) : lampiran.data?.length ? (
        <div className="grid gap-3 sm:grid-cols-2">
          {lampiran.data.map((a) => (
            <figure key={a.id} className="overflow-hidden rounded-lg border border-line">
              <Gambar jobId={job.id} lampiran={a} besar />
              <figcaption className="border-t border-line px-3 py-1.5 text-xs text-muted">
                {a.fileName ?? a.kind} · {dateTimeOf(a.createdAt)}
              </figcaption>
            </figure>
          ))}
        </div>
      ) : (
        <p className="py-8 text-center text-sm text-muted">
          {t("Tidak ada rekaman untuk pekerjaan ini. Robot Agent menyimpan tangkapan layar saat pekerjaan gagal.")}
        </p>
      )}
    </Dialog>
  );
}

/**
 * Lampiran lewat axios, bukan <img src> langsung: gambar harus membawa token,
 * dan peramban tidak menyertakan header Authorization untuk <img>.
 */
function Gambar({ jobId, lampiran, besar = false }: { jobId: string; lampiran: JobAttachment; besar?: boolean }) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    let dibuat: string | null = null;
    let batal = false;

    OpenOrchestratorApi.jobAttachmentBlob(jobId, lampiran.id)
      .then((blob) => {
        if (batal) return;
        dibuat = URL.createObjectURL(blob);
        setUrl(dibuat);
      })
      .catch(() => setUrl(null));

    return () => {
      batal = true;
      if (dibuat) URL.revokeObjectURL(dibuat);
    };
  }, [jobId, lampiran.id]);

  const tinggi = besar ? "h-64" : "h-32";

  if (lampiran.contentType.startsWith("video/")) {
    return url ? (
      <video src={url} controls className={cn("w-full bg-black", tinggi)} />
    ) : (
      <span className={cn("block bg-slate-50", tinggi)} />
    );
  }

  return (
    <a
      href={url ?? undefined}
      target="_blank"
      rel="noreferrer"
      className={cn("block overflow-hidden bg-slate-50", !besar && "rounded-lg border border-line")}
      title={`${lampiran.fileName ?? lampiran.kind} · ${dateTimeOf(lampiran.createdAt)}`}
    >
      {url ? (
        // eslint-disable-next-line @next/next/no-img-element -- blob: URL, bukan gambar yang bisa dioptimalkan Next
        <img src={url} alt={lampiran.fileName ?? lampiran.kind} className={cn("w-full object-cover object-top", tinggi)} />
      ) : (
        <span className={cn("block", tinggi)} />
      )}
    </a>
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
