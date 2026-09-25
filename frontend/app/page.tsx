"use client";

import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ForgeHubApi, type HistoryDay, type Periode } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { lokalTanggal } from "@/lib/bahasa";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Card, CardBody, CardHeader, StatCard } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { PilihTingkat } from "@/components/PilihTingkat";

/**
 * Rentang yang bisa dipilih di setiap kartu. Semuanya rentang KALENDER, sama
 * dengan yang dihitung server: "Mingguan" berarti minggu ini sejak Senin,
 * "Bulanan" bulan ini sejak tanggal 1 — bukan tujuh atau tiga puluh hari
 * terakhir.
 */
const PERIODE: { kode: Periode; label: string; riwayat: string; jumlah: string }[] = [
  { kode: "today", label: "Harian", riwayat: "Riwayat per jam, hari ini", jumlah: "{0} pekerjaan hari ini" },
  { kode: "week", label: "Mingguan", riwayat: "Riwayat per hari, minggu ini", jumlah: "{0} pekerjaan minggu ini" },
  { kode: "month", label: "Bulanan", riwayat: "Riwayat per hari, bulan ini", jumlah: "{0} pekerjaan bulan ini" },
  { kode: "year", label: "Tahunan", riwayat: "Riwayat per bulan, tahun ini", jumlah: "{0} pekerjaan tahun ini" },
];

const TINGKAT_PERINGATAN = ["Info", "Warning", "Error"];

/** Awalan kunci penyimpanan pilihan periode; setiap metrik menambahkan namanya. */
const KUNCI_PERIODE = "forgehub.dasbor.periode";

function infoPeriode(kode: Periode) {
  return PERIODE.find((p) => p.kode === kode) ?? PERIODE[0];
}

export default function Dasbor() {
  const { t, tp } = useT();

  // Setiap metrik memilih rentangnya sendiri, dan pilihannya diingat sendiri.
  const [periodeBerhasil, setPeriodeBerhasil] = usePeriode("berhasil");
  const [periodeGagal, setPeriodeGagal] = usePeriode("gagal");
  const [periodeKeberhasilan, setPeriodeKeberhasilan] = usePeriode("keberhasilan");
  const [periodeRiwayat, setPeriodeRiwayat] = usePeriode("riwayat");

  const [tingkat, setTingkat] = useState<string[]>([]);

  // Disegarkan tiap 5 detik. Dasbor yang tidak bergerak selama satu menit
  // terlihat sama dengan dasbor yang rusak.
  //
  // Angka keempat periode datang bersama dalam satu jawaban, jadi berganti
  // pilihan di sebuah kartu langsung mengganti angkanya — tanpa permintaan
  // baru dan tanpa "Memuat...".
  const d = useQuery({
    queryKey: ["dashboard"],
    queryFn: ForgeHubApi.dashboard,
    refetchInterval: 5_000,
  });

  // Grafik berbeda: setiap periode punya batangnya sendiri, jadi berganti
  // periode berarti satu permintaan. keepPreviousData menampilkan grafik
  // lama sebentar, bukan kotak kosong.
  //
  // Periodenya ikut disimpan bersama batangnya: selama grafik lama masih
  // tampil, labelnya harus tetap label periode LAMA — bukan nama hari untuk
  // batang yang sebenarnya per jam.
  const riwayat = useQuery({
    queryKey: ["history", periodeRiwayat],
    queryFn: async () => ({ periode: periodeRiwayat, batang: await ForgeHubApi.history(periodeRiwayat) }),
    refetchInterval: 30_000,
    placeholderData: keepPreviousData,
  });

  const peringatan = useQuery({
    queryKey: ["alerts", "dasbor", tingkat],
    queryFn: () => ForgeHubApi.alerts({ severity: tingkat, limit: 20 }),
    refetchInterval: 10_000,
    placeholderData: keepPreviousData,
  });

  if (d.isLoading) return <p className="text-sm text-muted">{t("Memuat...")}</p>;

  if (d.isError || !d.data) {
    return <p className="text-sm text-danger">{t("Tidak bisa mengambil data dasbor.")}</p>;
  }

  const x = d.data;
  const berhasil = x.periods[periodeBerhasil];
  const gagal = x.periods[periodeGagal];
  const keberhasilan = x.periods[periodeKeberhasilan];

  return (
    <div className="space-y-6">
      {/* Keadaan SAAT INI di baris atas, angka per rentang di bawahnya.
          Kelimanya tidak lagi dijejalkan ke satu baris: di kartu selebar
          seperlima layar, label dan pilihan rentang tidak muat berdampingan. */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-6">
        <StatCard
          className="xl:col-span-3"
          label={t("Robot Aktif")}
          action={<SaatIni />}
          value={x.robots.available + x.robots.busy}
          hint={t("{0} terdaftar · {1} terputus", x.robots.total, x.robots.disconnected)}
          tone={x.robots.available + x.robots.busy > 0 ? "ok" : "default"}
        />
        <StatCard
          className="xl:col-span-3"
          label={t("Pekerjaan Berjalan")}
          action={<SaatIni />}
          value={x.jobs.running}
          hint={t("{0} menunggu", x.jobs.pending)}
          tone={x.jobs.running > 0 ? "info" : "default"}
        />
        <StatCard
          className="xl:col-span-2"
          label={t("Berhasil")}
          action={<PilihPeriode nilai={periodeBerhasil} onUbah={setPeriodeBerhasil} untuk={t("Berhasil")} />}
          value={berhasil.successful}
          hint={keteranganRentang(periodeBerhasil, berhasil.start, t)}
          tone="ok"
        />
        <StatCard
          className="xl:col-span-2"
          label={t("Gagal")}
          action={<PilihPeriode nilai={periodeGagal} onUbah={setPeriodeGagal} untuk={t("Gagal")} />}
          value={gagal.faulted}
          hint={keteranganRentang(periodeGagal, gagal.start, t)}
          tone={gagal.faulted > 0 ? "danger" : "default"}
        />
        <StatCard
          className="sm:col-span-2 xl:col-span-2"
          label={t("Tingkat Keberhasilan")}
          action={
            <PilihPeriode
              nilai={periodeKeberhasilan}
              onUbah={setPeriodeKeberhasilan}
              untuk={t("Tingkat Keberhasilan")}
            />
          }
          value={`${keberhasilan.successRate}%`}
          hint={t(infoPeriode(periodeKeberhasilan).jumlah, keberhasilan.total)}
          tone={keberhasilan.successRate >= 90 ? "ok" : keberhasilan.successRate >= 70 ? "warn" : "danger"}
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title={t("Sedang Berjalan")} />
          <DataTable
            data={x.jobsInProgress}
            kunci={(j) => j.id}
            perHalaman={0}
            kosong="Tidak ada pekerjaan yang sedang berjalan."
            kolom={[
              { judul: "Proses|satu", sel: (j) => <span className="font-medium">{j.processName}</span> },
              { judul: "Robot|satu", sel: (j) => j.robotName ?? "-" },
              { judul: "Keadaan", sel: (j) => <Badge value={j.state} /> },
              {
                judul: "Kemajuan",
                sel: (j) => (
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-100">
                      <div className="h-full bg-info" style={{ width: `${j.progress}%` }} />
                    </div>
                    <span className="tabular-nums text-xs text-muted">{j.progress}%</span>
                  </div>
                ),
              },
              { judul: "Dibuat", sel: (j) => <span className="text-muted">{dateTimeOf(j.createdAt)}</span> },
            ]}
          />
        </Card>

        <Card>
          <CardHeader
            title={t(infoPeriode(periodeRiwayat).riwayat)}
            action={
              <PilihPeriode nilai={periodeRiwayat} onUbah={setPeriodeRiwayat} untuk={t("Riwayat pekerjaan")} />
            }
          />
          <CardBody className={cn("transition-opacity", riwayat.isPlaceholderData && "opacity-60")}>
            <Grafik data={riwayat.data?.batang ?? []} periode={riwayat.data?.periode ?? periodeRiwayat} />
          </CardBody>
        </Card>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title={t("Robot")} />
          <DataTable
            data={x.activeRobots}
            kunci={(r) => r.name}
            perHalaman={0}
            kolom={[
              { judul: "Nama", sel: (r) => <span className="font-medium">{r.name}</span> },
              { judul: "Status", sel: (r) => <Badge value={r.status} /> },
              {
                judul: "CPU / Memori",
                sel: (r) => (
                  <span className="tabular-nums text-muted">
                    {Math.round(r.cpuPercent)}% · {Math.round(r.memoryMb)} MB
                  </span>
                ),
              },
              { judul: "Denyut", sel: (r) => <span className="text-muted">{dateTimeOf(r.lastHeartbeatAt)}</span> },
            ]}
          />
        </Card>

        <Card>
          <CardHeader title={t("Pemicu Berikutnya")} />
          <DataTable
            data={x.upcomingTriggers}
            kunci={(q) => q.name}
            perHalaman={0}
            kosong="Tidak ada pemicu yang aktif."
            kolom={[
              { judul: "Nama", sel: (q) => <span className="font-medium">{q.name}</span> },
              { judul: "Proses|satu", sel: (q) => q.processName },
              {
                judul: "Jadwal",
                sel: (q) =>
                  q.cron ? (
                    <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">{q.cron}</code>
                  ) : (
                    <span className="text-muted">{t("tiap {0} menit", q.intervalMinutes)}</span>
                  ),
              },
              { judul: "Jalan Berikutnya", sel: (q) => <span className="text-muted">{dateTimeOf(q.nextRunAt)}</span> },
            ]}
          />
        </Card>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title={t("Ringkasan Antrean")} />
          <DataTable
            data={x.queueSummary}
            kunci={(q) => q.name}
            perHalaman={0}
            kolom={[
              { judul: "Nama", sel: (q) => <span className="font-medium">{q.name}</span> },
              { judul: "Baru", sel: (q) => <span className="tabular-nums">{q.newCount}</span> },
              { judul: "Diproses", sel: (q) => <span className="tabular-nums">{q.inProgressCount}</span> },
              { judul: "Berhasil", sel: (q) => <span className="tabular-nums text-ok">{q.successfulCount}</span> },
              {
                judul: "Gagal",
                sel: (q) => (
                  <span className={q.failedCount > 0 ? "tabular-nums text-danger" : "tabular-nums"}>
                    {q.failedCount}
                  </span>
                ),
              },
            ]}
          />
        </Card>

        <Card>
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-5 py-3">
            <h2 className="text-sm font-semibold text-ink">{t("Peringatan Terbaru")}</h2>
            <PilihTingkat pilihan={TINGKAT_PERINGATAN} terpilih={tingkat} onUbah={setTingkat} />
          </div>

          <div
            className={cn(
              "max-h-[26rem] overflow-y-auto transition-opacity",
              peringatan.isPlaceholderData && "opacity-60",
            )}
          >
            <DataTable
              data={peringatan.data ?? []}
              kunci={(a) => String(a.id)}
              perHalaman={0}
              kosong={
                peringatan.isLoading
                  ? "Memuat..."
                  : tingkat.length
                    ? "Tidak ada peringatan pada tingkat ini."
                    : "Belum ada peringatan."
              }
              kolom={[
                { judul: "Tingkat", sel: (a) => <Badge value={a.severity.toUpperCase()} /> },
                {
                  judul: "Pesan",
                  sel: (a) => (
                    <div>
                      <p className="font-medium text-ink">{tp(a.title)}</p>
                      {a.message ? <p className="text-xs text-muted">{tp(a.message)}</p> : null}
                    </div>
                  ),
                },
                {
                  judul: "Waktu",
                  sel: (a) => <span className="whitespace-nowrap text-muted">{dateTimeOf(a.createdAt)}</span>,
                },
              ]}
            />
          </div>
        </Card>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------
// Pilihan periode
// ---------------------------------------------------------------------

/**
 * Rentang yang dipilih untuk SATU metrik, diingat per peramban.
 *
 * Dibaca SESUDAH dipasang, alasannya sama dengan pilihan bahasa di
 * I18nProvider: di server tidak ada localStorage, dan membacanya saat render
 * pertama membuat isi dari server dan dari peramban tidak sama.
 */
function usePeriode(metrik: string): [Periode, (p: Periode) => void] {
  const kunci = `${KUNCI_PERIODE}.${metrik}`;
  const [periode, setPeriodeState] = useState<Periode>("today");

  useEffect(() => {
    try {
      const tersimpan = window.localStorage.getItem(kunci);
      if (PERIODE.some((p) => p.kode === tersimpan)) setPeriodeState(tersimpan as Periode);
    } catch {
      /* tanpa penyimpanan: mulai dari hari ini */
    }
  }, [kunci]);

  function setPeriode(p: Periode) {
    setPeriodeState(p);
    try {
      window.localStorage.setItem(kunci, p);
    } catch {
      /* diabaikan */
    }
  }

  return [periode, setPeriode];
}

/**
 * Pilihan rentang untuk satu kartu.
 *
 * <select> biasa, bukan menu buatan sendiri: papan ketik, pembaca layar, dan
 * layar sentuh sudah diurus peramban, dan pilihannya hanya empat.
 */
function PilihPeriode({
  nilai,
  onUbah,
  untuk,
}: {
  nilai: Periode;
  onUbah: (p: Periode) => void;
  /** Nama metriknya, untuk pembaca layar: "Rentang waktu: Gagal". */
  untuk: string;
}) {
  const { t } = useT();

  return (
    <select
      value={nilai}
      onChange={(e) => onUbah(e.target.value as Periode)}
      aria-label={t("Rentang waktu: {0}", untuk)}
      className={cn(
        "shrink-0 cursor-pointer rounded-md border border-line bg-card py-0.5 pl-1.5 pr-1 text-xs text-ink",
        "outline-none transition hover:border-slate-300 focus:border-sidebar focus:ring-2 focus:ring-sidebar/20",
      )}
    >
      {PERIODE.map((p) => (
        <option key={p.kode} value={p.kode}>
          {t(p.label)}
        </option>
      ))}
    </select>
  );
}

/**
 * Tanda pada kartu yang menggambarkan keadaan SEKARANG. Kartu seperti itu
 * tidak punya rentang waktu; tempat pilihan rentangnya diisi tanda ini supaya
 * tidak tampak seperti pilihannya lupa dipasang.
 */
function SaatIni() {
  const { t } = useT();

  return (
    <span
      title={t("Keadaan saat ini, tidak bergantung pada rentang waktu.")}
      className="shrink-0 rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-muted"
    >
      {t("Saat ini")}
    </span>
  );
}

/**
 * Keterangan di bawah angka: "Hari ini", atau sejak kapan periodenya dimulai.
 * Itu menjawab "mingguan itu tujuh hari terakhir, atau sejak Senin?" tanpa
 * orang perlu bertanya.
 */
function keteranganRentang(kode: Periode, awal: string, t: ReturnType<typeof useT>["t"]): string {
  if (kode === "today") return t("Hari ini");

  const bentuk: Intl.DateTimeFormatOptions =
    kode === "week"
      ? { weekday: "short", day: "numeric", month: "short" }
      : kode === "month"
        ? { day: "numeric", month: "short" }
        : { day: "numeric", month: "short", year: "numeric" };

  return t("Sejak {0}", tanggalDari(`${awal}T00:00`).toLocaleDateString(lokalTanggal(), bentuk));
}

// ---------------------------------------------------------------------
// Grafik
// ---------------------------------------------------------------------

/**
 * "YYYY-MM-DDTHH:mm" dari server menjadi tanggal PERAMBAN dengan angka yang
 * sama. Waktunya sudah waktu setempat server, jadi sengaja tidak diberi zona:
 * yang dibutuhkan hanya nama hari dan bulannya, bukan titik waktunya.
 */
function tanggalDari(bucket: string) {
  return new Date(`${bucket.slice(0, 16)}:00`);
}

/** Label pendek di bawah batang. */
function labelSumbu(bucket: string, periode: Periode, lokal: string): string {
  switch (periode) {
    case "today":
      return bucket.slice(11, 13);
    case "week":
      return tanggalDari(bucket).toLocaleDateString(lokal, { weekday: "short" });
    case "month":
      return String(Number(bucket.slice(8, 10)));
    case "year":
      return tanggalDari(bucket).toLocaleDateString(lokal, { month: "short" });
  }
}

/** Label lengkap untuk petunjuk saat kursor di atas batang. */
function labelPenuh(bucket: string, periode: Periode, lokal: string): string {
  const d = tanggalDari(bucket);

  switch (periode) {
    case "today":
      return `${bucket.slice(11, 16)}–${bucket.slice(11, 13)}:59`;
    case "week":
      return d.toLocaleDateString(lokal, { weekday: "long", day: "numeric", month: "short" });
    case "month":
      return d.toLocaleDateString(lokal, { day: "numeric", month: "long" });
    case "year":
      return d.toLocaleDateString(lokal, { month: "long", year: "numeric" });
  }
}

/**
 * Tidak setiap batang diberi label: 24 jam atau 31 hari yang semuanya
 * berlabel saling bertumpuk menjadi garis abu-abu yang tidak terbaca.
 */
function berlabel(i: number, bucket: string, periode: Periode): boolean {
  if (periode === "today") return i % 3 === 0;
  if (periode === "month") {
    const hari = Number(bucket.slice(8, 10));
    return hari === 1 || hari % 5 === 0;
  }
  return true;
}

/**
 * Grafik batang, ditulis dengan div biasa.
 *
 * Tanpa pustaka grafik: yang dibutuhkan hanya dua batang per titik, dan sebuah
 * pustaka bagan menambah ratusan kilobita ke setiap pemuatan halaman untuk
 * sesuatu yang muat dalam beberapa puluh baris.
 */
function Grafik({ data, periode }: { data: HistoryDay[]; periode: Periode }) {
  const { t } = useT();
  const lokal = lokalTanggal();

  if (data.length === 0) return <p className="text-sm text-muted">{t("Belum ada data.")}</p>;

  // Minimal 1 supaya pembagi tidak nol pada rentang yang seluruhnya kosong.
  const puncak = Math.max(1, ...data.map((d) => d.successful + d.faulted));
  const rapat = data.length > 14;

  // Batang lama (tanpa "bucket") dari server yang belum diperbarui tetap bisa
  // digambar: "day" selalu ada.
  const kunciDari = (d: HistoryDay) => d.bucket ?? `${d.day}T00:00`;

  return (
    <div>
      <div className={cn("flex h-40 items-end", rapat ? "gap-0.5" : "gap-1")}>
        {data.map((d) => {
          const total = d.successful + d.faulted;
          const kunci = kunciDari(d);

          return (
            <div
              key={kunci}
              // h-full WAJIB ada.
              //
              // items-end pada barisnya membuat tiap kolom menyusut ke isinya,
              // dan tinggi persen pada batang di dalamnya lalu tidak punya
              // acuan — hasilnya kolom setinggi 1px dan grafik yang kosong
              // sama sekali, tanpa galat apa pun.
              className="group relative flex h-full flex-1 flex-col justify-end gap-px"
              title={t("{0}: {1} berhasil, {2} gagal", labelPenuh(kunci, periode, lokal), d.successful, d.faulted)}
            >
              {d.faulted > 0 ? (
                <div
                  className="w-full rounded-t bg-danger/70"
                  style={{ height: `${(d.faulted / puncak) * 100}%` }}
                />
              ) : null}
              {d.successful > 0 ? (
                <div
                  className={cn("w-full bg-ok/70", d.faulted === 0 && "rounded-t")}
                  style={{ height: `${(d.successful / puncak) * 100}%` }}
                />
              ) : null}
              {total === 0 ? <div className="h-px w-full bg-line" /> : null}
            </div>
          );
        })}
      </div>

      <div className={cn("mt-1 flex", rapat ? "gap-0.5" : "gap-1")} aria-hidden="true">
        {data.map((d, i) => {
          const kunci = kunciDari(d);

          return (
            <span key={kunci} className="flex-1 overflow-visible whitespace-nowrap text-center text-[10px] text-muted">
              {berlabel(i, kunci, periode) ? labelSumbu(kunci, periode, lokal) : ""}
            </span>
          );
        })}
      </div>

      <div className="mt-3 flex items-center gap-4 text-xs text-muted">
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-sm bg-ok/70" /> {t("berhasil")}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-sm bg-danger/70" /> {t("gagal")}
        </span>
      </div>
    </div>
  );
}
