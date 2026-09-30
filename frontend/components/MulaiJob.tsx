"use client";

import { useMemo, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowDown,
  ArrowUp,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  Equal,
  Info,
  Monitor,
  Plus,
} from "lucide-react";
import { OpenOrchestratorApi, PRIORITAS_JOB, errorText, type FolderNode, type PilihanMulai } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn } from "@/lib/utils";
import { Button, Galat, labelKeadaan } from "@/components/ui/primitives";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { Laci } from "@/components/Laci";
import { useNotifikasi } from "@/components/Notifikasi";

/**
 * Start Job: pilih proses, lalu DI MANA dan SEBAGAI SIAPA ia dijalankan —
 * tipe runtime, akun, mesin — berapa kali, dan dengan prioritas apa.
 *
 * Pilihan akun dan mesin datang dari server (GET /api/jobs/start-options) dan
 * saling menyaring: runtime menyaring mesin dan akun, akun menyaring mesin,
 * mesin menyaring akun. Server memeriksa kombinasi yang sama saat job dibuat,
 * jadi yang bisa dipilih di sini adalah yang akan diterima di sana.
 */

/** Sama dengan JobService.MAX_JOBS_PER_REQUEST. */
const MAKS_JALAN = 100;

type RobotPilihan = PilihanMulai["robots"][number];

export function MulaiJob({
  folder,
  prosesAwal,
  onTutup,
}: {
  folder: FolderNode;
  /** Proses yang sudah dipilih — dari tombol Jalankan di halaman Proses. */
  prosesAwal?: string;
  onTutup: () => void;
}) {
  const { t } = useT();
  const klien = useQueryClient();
  const beritahu = useNotifikasi();
  const router = useRouter();
  const { boleh } = useIzin();

  const proses = useQuery({ queryKey: ["processes", folder.id], queryFn: () => OpenOrchestratorApi.processes(folder.id) });
  const pilihan = useQuery({
    queryKey: ["start-options", folder.id],
    queryFn: () => OpenOrchestratorApi.startOptions(folder.id),
    // Robot yang baru menyambung atau baru ditugaskan harus bisa dipilih
    // tanpa menutup laci.
    refetchInterval: 15_000,
  });

  const [namaProses, setNamaProses] = useState(prosesAwal ?? "");
  const [runtime, setRuntime] = useState("");
  const [akun, setAkun] = useState("");
  const [mesin, setMesin] = useState("");
  const [jumlah, setJumlah] = useState("1");
  const [prioritas, setPrioritas] = useState<string>("Inherited");
  const [masukan, setMasukan] = useState("");
  const [bukaSetelan, setBukaSetelan] = useState(true);
  const [bukaArgumen, setBukaArgumen] = useState(false);
  const [sudahDikirim, setSudahDikirim] = useState(false);
  const [galat, setGalat] = useState("");
  const [tugaskan, setTugaskan] = useState(false);

  const data = pilihan.data;

  // Tipe runtime yang dimiliki mesin folder ini, beserta jumlahnya.
  const runtimeTersedia = useMemo(() => {
    const total = new Map<string, number>();
    for (const m of data?.machines ?? []) {
      for (const [tipe, n] of Object.entries(m.runtimes)) total.set(tipe, (total.get(tipe) ?? 0) + n);
    }
    return (data?.runtimeTypes ?? []).filter((tipe) => total.has(tipe)).map((tipe) => ({ tipe, jumlah: total.get(tipe)! }));
  }, [data]);

  // Pilihan yang sudah tidak berlaku — karena pilihan lain berubah atau
  // datanya disegarkan — kembali ke "mana pun", bukan dikirim lalu ditolak.
  const runtimeDipilih = runtimeTersedia.some((r) => r.tipe === runtime) ? runtime : (runtimeTersedia[0]?.tipe ?? "");
  const mesinDari = (nama: string) => data?.machines.find((m) => m.name === nama);

  const robotCocok = (data?.robots ?? []).filter(
    (r) => (mesinDari(r.machineName)?.runtimes[runtimeDipilih] ?? 0) > 0 && (!mesin || r.machineName === mesin),
  );
  const akunDipilih = robotCocok.some((r) => r.name === akun) ? akun : "";
  const robotAkun = robotCocok.find((r) => r.name === akunDipilih);

  const mesinCocok = (data?.machines ?? []).filter(
    (m) => (m.runtimes[runtimeDipilih] ?? 0) > 0 && (!robotAkun || robotAkun.machineName === m.name),
  );
  const mesinDipilih = mesinCocok.some((m) => m.name === mesin) ? mesin : "";

  // Akun pengguna: robot attended — robot di PC seseorang, termasuk milik
  // yang sedang membuka dasbor ("jalankan sebagai diri sendiri"), di atas.
  // Akun robot: robot unattended.
  const akunPengguna = robotCocok
    .filter((r) => r.type === "Attended")
    .sort((a, b) => Number(b.self) - Number(a.self) || a.name.localeCompare(b.name));
  const akunRobot = robotCocok.filter((r) => r.type !== "Attended");

  const prosesDipilih = (proses.data ?? []).find((p) => p.name === namaProses);
  const prioritasProses = prosesDipilih?.priority ?? "Normal";
  const prioritasEfektif = prioritas === "Inherited" ? prioritasProses : prioritas;

  const galatJumlah = periksaJumlah(jumlah);
  const tanpaRuntime = pilihan.isSuccess && runtimeTersedia.length === 0;

  // Penjaga klik ganda yang berlaku SEKETIKA. isPending dan tombol yang mati
  // baru berlaku sesudah React menggambar ulang; dua klik yang tiba sebelum
  // itu sama-sama melihat isPending = false dan membuat job dua kali lipat.
  const sedangMengirim = useRef(false);

  const jalankan = useMutation({
    mutationFn: () =>
      OpenOrchestratorApi.startJob({
        processName: namaProses,
        folderId: folder.id,
        runtimeType: runtimeDipilih,
        robotName: akunDipilih || undefined,
        machineName: mesinDipilih || undefined,
        count: Number(jumlah),
        priority: prioritas,
        inputJson: masukan.trim() || undefined,
        source: "Dashboard",
      }),
    onSuccess: (hasil) => {
      klien.invalidateQueries({ queryKey: ["jobs"] });
      klien.invalidateQueries({ queryKey: ["processes"] });
      klien.invalidateQueries({ queryKey: ["dashboard"] });
      beritahu(hasil.ids.length > 1 ? t("{0} job berhasil dijalankan.", hasil.ids.length) : t("Job berhasil dijalankan."));
      onTutup();
    },
    // Laci TIDAK ditutup: yang sudah diisi tetap ada untuk dibetulkan.
    onError: (e) => setGalat(errorText(e)),
    onSettled: () => {
      sedangMengirim.current = false;
    },
  });

  function periksaJumlah(teks: string): string {
    const bersih = teks.trim();
    if (!bersih) return t("Jumlah jalan wajib diisi.");
    if (!/^\d+$/.test(bersih)) return t("Isi bilangan bulat, tanpa koma atau tanda minus.");
    const n = Number(bersih);
    if (n < 1) return t("Paling sedikit 1 kali.");
    if (n > MAKS_JALAN) return t("Paling banyak {0} kali.", MAKS_JALAN);
    return "";
  }

  function kirim() {
    // Klik kedua selama permintaan pertama berjalan tidak membuat job kedua.
    if (sedangMengirim.current || jalankan.isPending) return;

    setSudahDikirim(true);
    setGalat("");

    if (!namaProses) return setGalat(t("Pilih prosesnya dulu."));
    if (!runtimeDipilih) return setGalat(t("Tidak ada runtime yang tersedia. Untuk menjalankan job, tambahkan runtime ke mesin di folder ini."));
    if (galatJumlah) return setBukaSetelan(true);

    // JSON diperiksa DI SINI. Dikirim apa adanya, yang gagal justru robotnya
    // saat sudah mulai berjalan — dan kegagalan di sana terlihat sebagai
    // automasi yang rusak, bukan sebagai salah ketik.
    if (masukan.trim()) {
      try {
        JSON.parse(masukan);
      } catch {
        setBukaArgumen(true);
        return setGalat(t("Argumen masukan bukan JSON yang sah."));
      }
    }

    sedangMengirim.current = true;
    jalankan.mutate();
  }

  const labelAkun = (r: RobotPilihan) => {
    const pemilik = r.userDisplayName ?? r.username;
    const nama = r.type === "Attended" && pemilik ? `${pemilik} · ${r.name}` : r.name;
    return `${nama}${r.self ? ` (${t("Anda")})` : ""} — ${t(labelKeadaan(r.status))}`;
  };

  return (
    <Laci
      judul={t("Jalankan job")}
      onTutup={onTutup}
      kepala={
        <nav aria-label={t("Remah roti")} className="flex min-w-0 items-center gap-1.5 text-muted">
          <span className="truncate">{folder.name}</span>
          <ChevronRight size={14} className="shrink-0" />
          <span>{t("Automations")}</span>
          <ChevronRight size={14} className="shrink-0" />
          <span>{t("Proses")}</span>
          <ChevronRight size={14} className="shrink-0" />
          <h2 className="truncate font-semibold text-ink">{t("Jalankan job")}</h2>
        </nav>
      }
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" onClick={kirim} disabled={jalankan.isPending || !pilihan.isSuccess || tanpaRuntime}>
            {jalankan.isPending ? t("Menjalankan...") : t("Mulai")}
          </Button>
        </>
      }
    >
      <Isian label={`${t("Proses|satu")} *`}>
        <select value={namaProses} onChange={(e) => setNamaProses(e.target.value)} className={kelasIsian}>
          <option value="">—</option>
          {(proses.data ?? []).map((p) => (
            <option key={p.name} value={p.name}>
              {p.name}
            </option>
          ))}
        </select>
      </Isian>

      <Bagian
        judul={t("Pengaturan eksekusi")}
        petunjuk={t("Di mesin mana, sebagai akun siapa, berapa kali, dan dengan prioritas apa proses ini dijalankan.")}
        buka={bukaSetelan}
        onAlih={() => setBukaSetelan(!bukaSetelan)}
      >
        <h3 className="mb-3 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">{t("Runtime & alokasi")}</h3>

        {/* A. Tipe runtime */}
        <Medan label={`${t("Tipe runtime")} *`} idMedan="mulai-runtime">
          <select
            id="mulai-runtime"
            value={runtimeDipilih}
            onChange={(e) => setRuntime(e.target.value)}
            disabled={!pilihan.isSuccess || tanpaRuntime}
            aria-invalid={tanpaRuntime || undefined}
            aria-describedby={tanpaRuntime ? "mulai-runtime-galat" : undefined}
            className={cn(kelasIsian, tanpaRuntime && "border-danger text-muted")}
          >
            {pilihan.isLoading ? <option value="">{t("Memuat...")}</option> : null}
            {tanpaRuntime ? <option value="">{t("Tidak ada runtime yang tersedia")}</option> : null}
            {runtimeTersedia.map((r) => (
              <option key={r.tipe} value={r.tipe}>
                {`${t(r.tipe)} (${r.jumlah})`}
              </option>
            ))}
          </select>
          {tanpaRuntime ? (
            <p id="mulai-runtime-galat" className="mt-1.5 flex items-start gap-1.5 text-xs text-danger">
              <CircleAlert size={14} className="mt-px shrink-0" />
              <span>
                {t("Tidak ada runtime yang tersedia. Untuk menjalankan job, tambahkan runtime ke mesin di folder ini.")}
                {boleh("machines.update") ? (
                  <>
                    {" "}
                    <Link href="/tenant/robots/machines" className="font-medium underline underline-offset-2">
                      {t("Atur runtime mesin")}
                    </Link>
                  </>
                ) : null}
              </span>
            </p>
          ) : null}
          {pilihan.isError ? <p className="mt-1.5 text-xs text-danger">{errorText(pilihan.error)}</p> : null}
        </Medan>

        {/* B. Akun */}
        <Medan
          label={t("Akun")}
          idMedan="mulai-akun"
          petunjuk={t("Cari akun robot atau pengguna dengan robot unattended yang ditugaskan ke folder ini, atau jalankan otomasi sebagai diri sendiri.")}
          tombol={
            <TombolSamping
              label={t("Tugaskan akun robot ke folder ini")}
              onClick={() => setTugaskan(true)}
            >
              <Plus size={18} />
            </TombolSamping>
          }
        >
          <select
            id="mulai-akun"
            value={akunDipilih}
            onChange={(e) => setAkun(e.target.value)}
            disabled={!pilihan.isSuccess || tanpaRuntime}
            className={kelasIsian}
          >
            <option value="">{t("Akun pengguna/robot mana pun")}</option>
            {akunPengguna.length ? (
              <optgroup label={t("Akun pengguna")}>
                {akunPengguna.map((r) => (
                  <option key={r.name} value={r.name}>
                    {labelAkun(r)}
                  </option>
                ))}
              </optgroup>
            ) : null}
            {akunRobot.length ? (
              <optgroup label={t("Akun robot")}>
                {akunRobot.map((r) => (
                  <option key={r.name} value={r.name}>
                    {labelAkun(r)}
                  </option>
                ))}
              </optgroup>
            ) : null}
          </select>
        </Medan>

        {/* C. Mesin */}
        <Medan
          label={t("Mesin|satu")}
          idMedan="mulai-mesin"
          petunjuk={t("Cari mesin atau templat mesin yang sudah dikonfigurasi.")}
          tombol={
            boleh("machines.read") ? (
              <TombolSamping
                label={t(mesinDipilih ? "Buka konfigurasi mesin ini" : "Buka konfigurasi mesin")}
                onClick={() =>
                  router.push(`/tenant/robots/machines${mesinDipilih ? `?ubah=${encodeURIComponent(mesinDipilih)}` : ""}`)
                }
              >
                <Monitor size={18} />
              </TombolSamping>
            ) : undefined
          }
        >
          <select
            id="mulai-mesin"
            value={mesinDipilih}
            onChange={(e) => setMesin(e.target.value)}
            disabled={!pilihan.isSuccess || tanpaRuntime}
            className={kelasIsian}
          >
            <option value="">{t("Mesin mana pun")}</option>
            {mesinCocok.map((m) => (
              <option key={m.name} value={m.name}>
                {`${m.name}${m.type && m.type !== "Standard" ? ` · ${m.type}` : ""} — ${t(m.online ? "Online" : "Offline")}`}
              </option>
            ))}
          </select>
        </Medan>

        {/* D. Berapa kali */}
        <Medan label={`${t("Jalankan proses")} *`} idMedan="mulai-jumlah">
          <div className={cn("flex w-44 items-center rounded-lg border bg-card focus-within:ring-2 focus-within:ring-brand/20",
            sudahDikirim && galatJumlah ? "border-danger" : "border-line focus-within:border-brand")}>
            <input
              id="mulai-jumlah"
              type="number"
              inputMode="numeric"
              min={1}
              max={MAKS_JALAN}
              step={1}
              value={jumlah}
              onChange={(e) => setJumlah(e.target.value)}
              // Koma, titik, minus, dan eksponen tidak pernah menghasilkan
              // bilangan bulat positif — ditolak sebelum masuk isian.
              onKeyDown={(e) => {
                if ([".", ",", "-", "+", "e", "E"].includes(e.key)) e.preventDefault();
              }}
              aria-invalid={(sudahDikirim && !!galatJumlah) || undefined}
              aria-describedby="mulai-jumlah-galat"
              className="w-full rounded-lg bg-transparent px-3 py-2 text-sm text-ink outline-none"
            />
            <span className="pr-3 text-sm text-muted">{t("kali")}</span>
          </div>
          <p id="mulai-jumlah-galat" className="mt-1 min-h-4 text-xs text-danger">
            {galatJumlah && (sudahDikirim || jumlah !== "1") ? galatJumlah : ""}
          </p>
        </Medan>

        {/* E. Prioritas */}
        <Medan
          label={`${t("Prioritas job")} *`}
          idMedan="mulai-prioritas"
          petunjuk={prioritas === "Inherited" ? t("Mengikuti prioritas proses: {0}.", t(labelPrioritas(prioritasProses))) : undefined}
        >
          <div className="relative">
            <IkonPrioritas prioritas={prioritasEfektif} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2" />
            <select
              id="mulai-prioritas"
              value={prioritas}
              onChange={(e) => setPrioritas(e.target.value)}
              className={cn(kelasIsian, "pl-9")}
            >
              {PRIORITAS_JOB.map((p) => (
                <option key={p} value={p}>
                  {t(labelPrioritas(p))}
                </option>
              ))}
            </select>
          </div>
        </Medan>
      </Bagian>

      <Bagian judul={t("Argumen masukan")} buka={bukaArgumen} onAlih={() => setBukaArgumen(!bukaArgumen)}>
        <Isian label="Input JSON" petunjuk={t('Contoh: {"in_Nama":"Budi"}')}>
          <textarea rows={5} value={masukan} onChange={(e) => setMasukan(e.target.value)} className={`${kelasIsian} font-mono`} />
        </Isian>
      </Bagian>

      <Galat pesan={galat} className="mt-4" />

      {tugaskan ? (
        <DialogTugaskanRobot
          folder={folder}
          onTutup={() => setTugaskan(false)}
          onSelesai={() => klien.invalidateQueries({ queryKey: ["start-options", folder.id] })}
        />
      ) : null}
    </Laci>
  );
}

/** Nama prioritas untuk layar; nilainya tetap nama Inggris yang dikirim ke server. */
export function labelPrioritas(p: string | null | undefined): string {
  return ({ Inherited: "Diwarisi", Low: "Rendah", Normal: "Normal", High: "Tinggi" } as Record<string, string>)[p ?? ""] ?? p ?? "-";
}

/** Tanda prioritas seperti di Orchestrator: panah naik merah, sama dengan jingga, panah turun biru. */
export function IkonPrioritas({ prioritas, className }: { prioritas: string | null | undefined; className?: string }) {
  if (prioritas === "High") return <ArrowUp size={16} className={cn("text-danger", className)} aria-hidden="true" />;
  if (prioritas === "Low") return <ArrowDown size={16} className={cn("text-info", className)} aria-hidden="true" />;
  return <Equal size={16} className={cn("text-warn", className)} aria-hidden="true" />;
}

/** Kelompok isian yang bisa dilipat, dengan kepala abu-abu seperti "Execution settings". */
function Bagian({
  judul,
  petunjuk,
  buka,
  onAlih,
  children,
}: {
  judul: string;
  petunjuk?: string;
  buka: boolean;
  onAlih: () => void;
  children: ReactNode;
}) {
  return (
    <section className="mb-4 rounded-lg border border-line">
      <div className="flex items-center gap-2 rounded-t-lg bg-slate-50 px-3 py-2.5">
        <button
          type="button"
          onClick={onAlih}
          aria-expanded={buka}
          className="flex items-center gap-2 text-sm font-medium text-ink"
        >
          {buka ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
          {judul}
        </button>
        {petunjuk ? (
          <span title={petunjuk} aria-label={petunjuk} className="inline-flex text-muted">
            <Info size={15} />
          </span>
        ) : null}
      </div>
      {buka ? <div className="px-4 pb-1 pt-4">{children}</div> : null}
    </section>
  );
}

/** Isian dengan label di atas, petunjuk di bawah, dan tombol opsional di kanannya. */
function Medan({
  label,
  idMedan,
  petunjuk,
  tombol,
  children,
}: {
  label: string;
  idMedan: string;
  petunjuk?: string;
  tombol?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="mb-4">
      <label htmlFor={idMedan} className="mb-1 block text-sm font-medium text-ink">
        {label}
      </label>
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">{children}</div>
        {tombol}
      </div>
      {petunjuk ? <p className="mt-1 text-xs text-muted">{petunjuk}</p> : null}
    </div>
  );
}

function TombolSamping({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className="inline-flex h-[38px] w-10 shrink-0 items-center justify-center rounded-lg text-brand transition hover:bg-brandSoft"
    >
      {children}
    </button>
  );
}

/**
 * Tugaskan robot yang sudah ada ke folder ini, tanpa meninggalkan Start Job.
 * Robot baru dibuat di Tenant › Robot; yang di sini hanya penugasannya.
 */
function DialogTugaskanRobot({
  folder,
  onTutup,
  onSelesai,
}: {
  folder: FolderNode;
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();
  const { boleh } = useIzin();
  const [nama, setNama] = useState("");
  const [galat, setGalat] = useState("");

  const anggota = useQuery({ queryKey: ["folder-members", folder.id], queryFn: () => OpenOrchestratorApi.folderMembers(folder.id) });
  const semua = useQuery({ queryKey: ["robots", "semua"], queryFn: () => OpenOrchestratorApi.robots(), enabled: boleh("robots.read") });

  const sudah = new Set((anggota.data?.robots ?? []).map((r) => r.name));
  const calon = (semua.data ?? []).filter((r) => !sudah.has(r.name));
  const bolehAtur = anggota.data?.canManageRobots ?? false;

  const simpan = useMutation({
    mutationFn: () => OpenOrchestratorApi.assignRobot(folder.id, nama),
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <Dialog
      judul={t("Tugaskan akun robot ke folder ini")}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button
            variant="primary"
            onClick={() => (nama ? simpan.mutate() : setGalat(t("Pilih robotnya dulu.")))}
            disabled={simpan.isPending || !bolehAtur}
          >
            {simpan.isPending ? t("Menyimpan...") : t("Tugaskan")}
          </Button>
        </>
      }
    >
      {anggota.isSuccess && !bolehAtur ? (
        <p className="mb-3 text-sm text-muted">{t("Anda tidak bisa mengatur robot folder ini. Minta pengelola folder menugaskannya.")}</p>
      ) : (
        <Isian
          label={t("Robot|satu")}
          petunjuk={t("Robot yang ditugaskan ke folder ini bisa mengambil job prosesnya.")}
        >
          <select value={nama} onChange={(e) => setNama(e.target.value)} className={kelasIsian} disabled={!bolehAtur}>
            <option value="">—</option>
            {calon.map((r) => (
              <option key={r.name} value={r.name}>
                {`${r.name} · ${r.type}${r.machineName ? ` · ${r.machineName}` : ""}`}
              </option>
            ))}
          </select>
        </Isian>
      )}

      {semua.isSuccess && calon.length === 0 && bolehAtur ? (
        <p className="mb-2 text-sm text-muted">{t("Semua robot sudah ditugaskan ke folder ini.")}</p>
      ) : null}

      {boleh("robots.create") ? (
        <Link href="/tenant/robots" className="text-sm font-medium text-brand underline underline-offset-2">
          {t("Buat robot baru")}
        </Link>
      ) : null}

      <Galat pesan={galat} className="mt-3" />
    </Dialog>
  );
}
