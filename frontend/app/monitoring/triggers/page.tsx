"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText, type Trigger } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

/**
 * Contoh cron yang bisa diklik.
 *
 * Ada karena hampir tidak ada yang hafal urutan lima ruasnya, dan yang
 * mengarangnya sendiri biasanya menuliskan sesuatu yang sah tapi bukan yang
 * dimaksudnya — lalu baru tahu seminggu kemudian.
 */
const CONTOH = [
  { cron: "*/15 * * * *", arti: "Tiap 15 menit" },
  { cron: "0 * * * *", arti: "Tiap jam, di menit ke-0" },
  { cron: "0 7 * * *", arti: "Tiap hari pukul 07:00" },
  { cron: "0 7 * * 1-5", arti: "Tiap hari kerja pukul 07:00" },
  { cron: "0 0 1 * *", arti: "Tiap tanggal 1 tengah malam" },
  { cron: "30 22 * * 6", arti: "Tiap Sabtu pukul 22:30" },
];

const ZONA = ["Asia/Jakarta", "Asia/Makassar", "Asia/Jayapura", "UTC"];

export default function Pemicu() {
  const { t } = useT();
  const klien = useQueryClient();

  const [sunting, setSunting] = useState<Trigger | null>(null);
  const [baru, setBaru] = useState(false);
  const [galat, setGalat] = useState("");

  const pemicu = useQuery({ queryKey: ["triggers"], queryFn: ForgeHubApi.triggers, refetchInterval: 10_000 });
  const proses = useQuery({ queryKey: ["processes"], queryFn: ForgeHubApi.processes });

  const segarkan = () => klien.invalidateQueries({ queryKey: ["triggers"] });

  const alihkan = useMutation({
    mutationFn: ForgeHubApi.toggleTrigger,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteTrigger,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center">
        <h1 className="text-lg font-semibold text-ink">{t("Pemicu")}</h1>
        <Button variant="primary" className="ml-auto" onClick={() => setBaru(true)}>
          {t("Tambah")}
        </Button>
      </div>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <Card>
        <DataTable
          data={pemicu.data ?? []}
          kunci={(p) => p.name}
          onBuka={setSunting}
          kolom={[
            { judul: "Nama", sel: (p) => <span className="font-medium">{p.name}</span>, urut: (p) => p.name },
            { judul: "Proses", sel: (p) => p.processName, urut: (p) => p.processName },
            {
              judul: "Jadwal",
              sel: (p) =>
                p.cron ? (
                  <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">{p.cron}</code>
                ) : (
                  <span className="text-muted">tiap {p.intervalMinutes} menit</span>
                ),
              urut: (p) => p.cron ?? `${p.intervalMinutes}m`,
            },
            { judul: "Zona Waktu", sel: (p) => <span className="text-muted">{p.timezone}</span>, urut: (p) => p.timezone },
            {
              judul: "Status",
              sel: (p) => <Badge value={p.enabled ? "AVAILABLE" : "STOPPED"} />,
              urut: (p) => String(p.enabled),
            },
            {
              judul: "Jalan Berikutnya",
              sel: (p) => <span className="text-muted">{dateTimeOf(p.nextRunAt)}</span>,
              urut: (p) => p.nextRunAt,
            },
            {
              judul: "Jalan Terakhir",
              sel: (p) => <span className="text-muted">{dateTimeOf(p.lastRunAt)}</span>,
              urut: (p) => p.lastRunAt,
            },
            {
              judul: "",
              sel: (p) => (
                <div className="flex justify-end gap-1.5">
                  <Button variant="ghost" onClick={() => alihkan.mutate(p.name)}>
                    {p.enabled ? t("Matikan") : t("Nyalakan")}
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${p.name}"?`)) hapus.mutate(p.name);
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

      <DialogPemicu
        terbuka={baru || !!sunting}
        awal={sunting}
        proses={(proses.data ?? []).map((p) => p.name)}
        onTutup={() => {
          setBaru(false);
          setSunting(null);
        }}
        onSelesai={segarkan}
      />
    </div>
  );
}

function DialogPemicu({
  terbuka,
  awal,
  proses,
  onTutup,
  onSelesai,
}: {
  terbuka: boolean;
  awal: Trigger | null;
  proses: string[];
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  // Kunci pada Dialog memaksa isiannya dibuat ulang saat pemicu yang disunting
  // berganti. Tanpa itu, membuka pemicu kedua menampilkan isian pemicu
  // pertama, karena useState hanya membaca nilai awalnya sekali.
  const kunci = awal?.name ?? "baru";

  return terbuka ? <IsiDialog key={kunci} awal={awal} proses={proses} onTutup={onTutup} onSelesai={onSelesai} /> : null;
}

function IsiDialog({
  awal,
  proses,
  onTutup,
  onSelesai,
}: {
  awal: Trigger | null;
  proses: string[];
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const [nama, setNama] = useState(awal?.name ?? "");
  const [namaProses, setNamaProses] = useState(awal?.processName ?? "");
  const [pakaiCron, setPakaiCron] = useState(!!awal?.cron);
  const [cron, setCron] = useState(awal?.cron ?? "0 7 * * 1-5");
  const [selang, setSelang] = useState(awal?.intervalMinutes || 60);
  const [zona, setZona] = useState(awal?.timezone ?? "Asia/Jakarta");
  const [prioritas, setPrioritas] = useState(awal?.priority ?? "Normal");
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: ForgeHubApi.saveTrigger,
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    if (!nama.trim()) return setGalat("Nama pemicu wajib diisi.");
    if (!namaProses) return setGalat("Pilih prosesnya dulu.");

    simpan.mutate({
      name: nama.trim(),
      processName: namaProses,
      timezone: zona,
      priority: prioritas,
      enabled: awal?.enabled ?? true,
      ...(pakaiCron ? { cron: cron.trim() } : { intervalMinutes: Number(selang) || 60 }),
    });
  }

  return (
    <Dialog
      judul={awal ? `${t("Sunting")} — ${awal.name}` : `${t("Tambah")} ${t("Pemicu").toLowerCase()}`}
      terbuka
      onTutup={onTutup}
      lebar="max-w-2xl"
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" onClick={kirim} disabled={simpan.isPending}>
            {t("Simpan")}
          </Button>
        </>
      }
    >
      <div className="grid gap-x-4 sm:grid-cols-2">
        <Isian label={t("Nama")}>
          <input
            value={nama}
            onChange={(e) => setNama(e.target.value)}
            disabled={!!awal}
            className={kelasIsian}
          />
        </Isian>

        <Isian label={t("Proses")}>
          <select value={namaProses} onChange={(e) => setNamaProses(e.target.value)} className={kelasIsian}>
            <option value="">—</option>
            {proses.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </Isian>
      </div>

      <div className="mb-3 flex gap-2 rounded-lg bg-slate-100 p-1">
        <button
          type="button"
          onClick={() => setPakaiCron(false)}
          className={`flex-1 rounded-md px-3 py-1.5 text-sm ${!pakaiCron ? "bg-card font-medium shadow-sm" : "text-muted"}`}
        >
          Selang waktu
        </button>
        <button
          type="button"
          onClick={() => setPakaiCron(true)}
          className={`flex-1 rounded-md px-3 py-1.5 text-sm ${pakaiCron ? "bg-card font-medium shadow-sm" : "text-muted"}`}
        >
          Cron
        </button>
      </div>

      {pakaiCron ? (
        <>
          <Isian
            label="Ekspresi cron"
            petunjuk="Lima ruas: menit jam tanggal bulan hari. Kalau tanggal DAN hari sama-sama diisi, cukup salah satu cocok."
          >
            <input value={cron} onChange={(e) => setCron(e.target.value)} className={`${kelasIsian} font-mono`} />
          </Isian>

          <div className="mb-3 flex flex-wrap gap-1.5">
            {CONTOH.map((c) => (
              <button
                key={c.cron}
                type="button"
                onClick={() => setCron(c.cron)}
                title={c.cron}
                className="rounded-full border border-line px-2.5 py-1 text-xs text-muted transition hover:border-sidebar hover:text-ink"
              >
                {c.arti}
              </button>
            ))}
          </div>
        </>
      ) : (
        <Isian label="Jalankan tiap (menit)">
          <input
            type="number"
            min={1}
            value={selang}
            onChange={(e) => setSelang(Number(e.target.value))}
            className={kelasIsian}
          />
        </Isian>
      )}

      <div className="grid gap-x-4 sm:grid-cols-2">
        <Isian
          label={t("Zona Waktu")}
          petunjuk="Jadwalnya dihitung menurut zona ini, bukan waktu server."
        >
          <select value={zona} onChange={(e) => setZona(e.target.value)} className={kelasIsian}>
            {ZONA.map((z) => (
              <option key={z}>{z}</option>
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
      </div>

      {galat ? <p className="text-sm text-danger">{galat}</p> : null}
    </Dialog>
  );
}
