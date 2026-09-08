"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText, type Job } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, CardHeader } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

const KEADAAN = ["", "PENDING", "RUNNING", "SUCCESSFUL", "FAULTED", "STOPPED"];

export default function Pekerjaan() {
  const { t } = useT();
  const klien = useQueryClient();

  const [saring, setSaring] = useState("");
  const [detail, setDetail] = useState<Job | null>(null);
  const [mulai, setMulai] = useState(false);
  const [galat, setGalat] = useState("");

  const jobs = useQuery({
    queryKey: ["jobs", saring],
    queryFn: () => ForgeHubApi.jobs({ state: saring || undefined, limit: 500 }),
    refetchInterval: 5_000,
  });

  const proses = useQuery({ queryKey: ["processes"], queryFn: ForgeHubApi.processes });

  function segarkan() {
    klien.invalidateQueries({ queryKey: ["jobs"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }

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

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-semibold text-ink">{t("Pekerjaan")}</h1>

        <select value={saring} onChange={(e) => setSaring(e.target.value)} className={`${kelasIsian} w-44`}>
          {KEADAAN.map((k) => (
            <option key={k} value={k}>
              {k || t("Semua keadaan")}
            </option>
          ))}
        </select>

        <div className="ml-auto flex gap-2">
          <Button onClick={segarkan}>{t("Muat ulang")}</Button>
          <Button variant="primary" onClick={() => setMulai(true)}>
            {t("Jalankan")}
          </Button>
        </div>
      </div>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <Card>
        <DataTable
          data={jobs.data ?? []}
          kunci={(j) => j.id}
          onBuka={setDetail}
          onPilih={() => {}}
          aksiTerpilih={(dipilih) => (
            <>
              <Button
                onClick={() =>
                  dipilih
                    .filter((j) => j.state === "PENDING" || j.state === "RUNNING")
                    .forEach((j) => hentikan.mutate(j.id))
                }
              >
                {t("Hentikan")}
              </Button>
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
            </>
          )}
          kolom={[
            { judul: "Proses", sel: (j) => <span className="font-medium">{j.processName}</span>, urut: (j) => j.processName },
            { judul: "Robot", sel: (j) => j.robotName ?? "-", urut: (j) => j.robotName },
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

      <DialogJalankan
        terbuka={mulai}
        proses={(proses.data ?? []).map((p) => p.name)}
        onTutup={() => setMulai(false)}
        onSelesai={segarkan}
      />
    </div>
  );
}

function DialogDetail({ job, onTutup }: { job: Job | null; onTutup: () => void }) {
  const { t } = useT();

  // Log pekerjaan ini diambil hanya saat dialognya terbuka. Ini yang diminta:
  // log berada DI DALAM prosesnya, bukan di halaman terpisah yang harus
  // disaring sendiri.
  const log = useQuery({
    queryKey: ["logs", "job", job?.id],
    queryFn: () => ForgeHubApi.logs({ jobId: job!.id, limit: 500 }),
    enabled: !!job,
  });

  if (!job) return null;

  return (
    <Dialog judul={`${job.processName} — ${job.state}`} terbuka onTutup={onTutup} lebar="max-w-3xl">
      <dl className="mb-4 grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
        <Medan label={t("Robot")} nilai={job.robotName} />
        <Medan label={t("Mesin")} nilai={job.machineName} />
        <Medan label={t("Sumber")} nilai={job.source} />
        <Medan label={t("Prioritas")} nilai={job.priority} />
        <Medan label={t("Dimulai")} nilai={dateTimeOf(job.startedAt)} />
        <Medan label={t("Selesai")} nilai={dateTimeOf(job.endedAt)} />
      </dl>

      {job.info ? <p className="mb-4 rounded-lg bg-slate-50 px-3 py-2 text-sm">{job.info}</p> : null}

      {job.inputJson ? <Kode judul="Input" isi={job.inputJson} /> : null}
      {job.outputJson ? <Kode judul="Output" isi={job.outputJson} /> : null}

      <h3 className="mb-2 mt-4 text-xs font-semibold uppercase tracking-wide text-muted">
        {t("Catatan")}
      </h3>

      <div className="max-h-72 overflow-y-auto rounded-lg border border-line">
        {log.data?.length ? (
          log.data.map((l) => (
            <div key={l.id} className="flex gap-3 border-b border-line/70 px-3 py-1.5 text-xs last:border-0">
              <span className="w-32 shrink-0 tabular-nums text-muted">{dateTimeOf(l.loggedAt)}</span>
              <span className="w-14 shrink-0 font-medium">{l.level}</span>
              <span className="break-all">{l.message}</span>
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
      <pre className="overflow-x-auto rounded-lg bg-slate-900 px-3 py-2 text-xs text-slate-100">
        {isi}
      </pre>
    </div>
  );
}

function DialogJalankan({
  terbuka,
  proses,
  onTutup,
  onSelesai,
}: {
  terbuka: boolean;
  proses: string[];
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();
  const [nama, setNama] = useState("");
  const [prioritas, setPrioritas] = useState("Normal");
  const [masukan, setMasukan] = useState("");
  const [galat, setGalat] = useState("");

  const jalankan = useMutation({
    mutationFn: ForgeHubApi.startJob,
    onSuccess: () => {
      onSelesai();
      onTutup();
      setNama("");
      setMasukan("");
      setGalat("");
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    if (!nama) return setGalat("Pilih prosesnya dulu.");

    // JSON diperiksa DI SINI. Dikirim apa adanya, yang gagal justru robotnya
    // saat sudah mulai berjalan — dan kegagalan di sana terlihat sebagai
    // automasi yang rusak, bukan sebagai salah ketik.
    if (masukan.trim()) {
      try {
        JSON.parse(masukan);
      } catch {
        return setGalat("Argumen masukan bukan JSON yang sah.");
      }
    }

    jalankan.mutate({
      processName: nama,
      priority: prioritas,
      source: "Dashboard",
      inputJson: masukan.trim() || undefined,
    });
  }

  return (
    <Dialog
      judul={t("Jalankan")}
      terbuka={terbuka}
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
      <Isian label={t("Proses")}>
        <select value={nama} onChange={(e) => setNama(e.target.value)} className={kelasIsian}>
          <option value="">—</option>
          {proses.map((p) => (
            <option key={p} value={p}>
              {p}
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

      <Isian label="Input JSON" petunjuk='Contoh: {"in_Nama":"Budi"}'>
        <textarea
          rows={4}
          value={masukan}
          onChange={(e) => setMasukan(e.target.value)}
          className={`${kelasIsian} font-mono`}
        />
      </Isian>

      {galat ? <p className="text-sm text-danger">{galat}</p> : null}
    </Dialog>
  );
}
