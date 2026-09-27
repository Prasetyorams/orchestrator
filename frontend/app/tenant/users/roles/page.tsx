"use client";

import { useCallback, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Eye, Lock, Pencil, Trash2 } from "lucide-react";
import { OpenOrchestratorApi, errorText, type Role, type SumberIzin } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { cocokIzin, useIzin } from "@/lib/izin";
import { cn } from "@/lib/utils";
import { Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { BilahAlat } from "@/components/HalamanFolder";

/**
 * Peran dan izinnya, seperti Roles di Orchestrator: setiap peran adalah
 * sekumpulan izin "sumber × tindakan", dan setiap pengguna memegang satu peran.
 *
 * Administrator bawaan tidak bisa diubah atau dihapus — ia selalu berarti
 * semuanya, supaya tidak ada penyuntingan yang bisa mengunci semua orang di
 * luar OpenOrchestrator. Yang lain, termasuk peran bawaan lainnya, bisa disunting.
 */

/** Nama tiap sumber di matriks; kuncinya sama dengan kunci katalog di server. */
const LABEL_SUMBER: Record<string, string> = {
  processes: "Proses",
  jobs: "Pekerjaan",
  triggers: "Pemicu",
  packages: "Paket",
  queues: "Antrean",
  assets: "Aset",
  buckets: "Ember Penyimpanan",
  robots: "Robot",
  logs: "Catatan",
  folders: "Folder",
  machines: "Mesin",
  environments: "Lingkungan",
  users: "Pengguna",
  roles: "Peran",
  alerts: "Peringatan",
  audit: "Audit",
  settings: "Setelan",
};

const AKSI = [
  { kode: "read", label: "Lihat" },
  { kode: "create", label: "Buat" },
  { kode: "update", label: "Ubah" },
  { kode: "delete", label: "Hapus" },
] as const;

/**
 * Arti yang tidak terbaca dari nama kotaknya saja. Tanpa catatan ini orang
 * yang menyusun peran robot tidak akan menebak bahwa "Robot · Ubah" adalah
 * denyutnya.
 */
const CATATAN: Record<string, string> = {
  jobs: "Ubah: menghentikan pekerjaan; robot memakainya untuk mengambil dan melaporkan pekerjaan.",
  queues: "Ubah: menambah dan memproses butir antrean.",
  assets: "Lihat: termasuk membuka isi aset rahasia dan kredensial.",
  robots: "Ubah: denyut robot — akun JakRunner memerlukannya.",
  folders: "Ubah: menugaskan orang dan robot, sekaligus melihat semua folder.",
  roles: "Izin yang bisa diberikan hanya yang juga Anda miliki.",
};

const KELOMPOK: { judul: string; sumber: string[] }[] = [
  {
    judul: "Automasi",
    sumber: ["processes", "jobs", "triggers", "packages", "queues", "assets", "buckets", "robots", "logs"],
  },
  {
    judul: "Pengelolaan penyewa",
    sumber: ["folders", "machines", "environments", "users", "roles", "alerts", "audit", "settings"],
  },
];

/** Setiap izin katalog yang dicakup pola peran. */
function jabarkan(pola: string[], katalog: SumberIzin[]): Set<string> {
  const hasil = new Set<string>();

  for (const s of katalog) {
    for (const a of s.actions) {
      const izin = `${s.resource}.${a}`;
      if (cocokIzin(pola, izin)) hasil.add(izin);
    }
  }

  return hasil;
}

const polaPeran = (r: Role | null | undefined) =>
  (r?.permissions ?? "").split(",").map((x) => x.trim()).filter(Boolean);

export default function Peran() {
  const { t, tp } = useT();
  const klien = useQueryClient();
  const { boleh } = useIzin();

  const [sunting, setSunting] = useState<Role | null>(null);
  const [baru, setBaru] = useState(false);
  const [galat, setGalat] = useState("");

  const peran = useQuery({ queryKey: ["roles"], queryFn: OpenOrchestratorApi.roles });
  const katalog = useQuery({ queryKey: ["permissions"], queryFn: OpenOrchestratorApi.permissionCatalog, staleTime: Infinity });

  const total = useMemo(() => (katalog.data ?? []).reduce((n, s) => n + s.actions.length, 0), [katalog.data]);

  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["roles"] });
    klien.invalidateQueries({ queryKey: ["users"] });
    // Peran sendiri bisa saja yang baru diubah: menu dan tombol menyesuaikan.
    klien.invalidateQueries({ queryKey: ["me"] });
  }, [klien]);

  const hapus = useMutation({
    mutationFn: OpenOrchestratorApi.deleteRole,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const tutup = useCallback(() => {
    setBaru(false);
    setSunting(null);
  }, []);

  return (
    <div>
      <BilahAlat
        aksi={
          boleh("roles.create") ? (
            <Button variant="primary" onClick={() => setBaru(true)} disabled={!katalog.isSuccess}>
              {t("Tambah peran")}
            </Button>
          ) : null
        }
      >
        <p className="text-sm text-muted">
          {t("Setiap pengguna memegang satu peran. Izinnya berlaku di semua folder tempat ia ditugaskan.")}
        </p>
      </BilahAlat>

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={peran.data ?? []}
          kunci={(p) => p.name}
          perHalaman={0}
          onBuka={setSunting}
          kosong={peran.isLoading ? "Memuat..." : "Belum ada data."}
          kolom={[
            {
              judul: "Nama",
              sel: (p) => (
                <span className="flex items-center gap-1.5 font-medium">
                  {p.locked ? <Lock className="h-3.5 w-3.5 shrink-0 text-muted" aria-label={t("Bawaan")} /> : null}
                  {p.name}
                </span>
              ),
              urut: (p) => p.name,
            },
            {
              judul: "Keterangan",
              sel: (p) => <span className="text-muted">{p.description ? tp(p.description) : "-"}</span>,
            },
            {
              judul: "Izin",
              sel: (p) => {
                const n = katalog.data ? jabarkan(polaPeran(p), katalog.data).size : 0;

                return (
                  <span className="whitespace-nowrap tabular-nums text-muted">
                    {n === total && total > 0 ? t("Semua izin") : t("{0} dari {1} izin", n, total)}
                  </span>
                );
              },
            },
            {
              judul: "Pengguna",
              sel: (p) => <span className="tabular-nums">{p.userCount}</span>,
              urut: (p) => p.userCount,
            },
            {
              judul: "",
              sel: (p) => {
                const bisaUbah = !p.locked && boleh("roles.update");

                return (
                  <div className="flex justify-end gap-0.5">
                    <IconButton label={bisaUbah ? t("Ubah") : t("Lihat izin")} onClick={() => setSunting(p)}>
                      {bisaUbah ? <Pencil size={15} /> : <Eye size={16} />}
                    </IconButton>
                    {boleh("roles.delete") ? (
                      <IconButton
                        label={
                          p.locked
                            ? t("Peran Administrator tidak bisa dihapus.")
                            : p.userCount > 0
                              ? t("Masih dipakai {0} pengguna. Ganti peran mereka dulu.", p.userCount)
                              : t("Hapus")
                        }
                        tone="danger"
                        disabled={p.locked || p.userCount > 0 || hapus.isPending}
                        onClick={() => {
                          setGalat("");
                          if (window.confirm(t("Hapus peran \"{0}\"?", p.name))) hapus.mutate(p.name);
                        }}
                      >
                        <Trash2 size={16} />
                      </IconButton>
                    ) : null}
                  </div>
                );
              },
            },
          ]}
        />
      </Card>

      <p className="mt-3 text-xs text-muted">
        {t("Akun robot (JakRunner) sebaiknya memakai peran Robot: izinnya persis yang dibutuhkan robot, tidak lebih.")}
      </p>

      {(baru || sunting) && katalog.data ? (
        <DialogPeran
          key={sunting?.name ?? "baru"}
          awal={sunting}
          katalog={katalog.data}
          semuaPeran={peran.data ?? []}
          onTutup={tutup}
          onSelesai={segarkan}
        />
      ) : null}
    </div>
  );
}

function DialogPeran({
  awal,
  katalog,
  semuaPeran,
  onTutup,
  onSelesai,
}: {
  awal: Role | null;
  katalog: SumberIzin[];
  semuaPeran: Role[];
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();
  const { boleh } = useIzin();

  const hanyaBaca = !!awal && (awal.locked || !boleh("roles.update"));

  const [nama, setNama] = useState(awal?.name ?? "");
  const [ket, setKet] = useState(awal?.description ?? "");
  const [dipilih, setDipilih] = useState<Set<string>>(() => jabarkan(polaPeran(awal), katalog));
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: () => {
      const body = { name: nama.trim(), description: ket.trim(), permissions: [...dipilih] };
      return awal ? OpenOrchestratorApi.updateRole(awal.name, body) : OpenOrchestratorApi.createRole(body);
    },
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  // Izin yang tidak dimiliki penyusunnya sendiri tidak bisa diberikan (server
  // juga menolaknya); kotaknya dimatikan supaya itu terlihat sebelum Simpan.
  const bisaBeri = (izin: string) => !hanyaBaca && boleh(izin);

  function alih(izin: string) {
    setDipilih((kini) => {
      const baru = new Set(kini);
      if (baru.has(izin)) baru.delete(izin);
      else baru.add(izin);
      return baru;
    });
  }

  /** Nyalakan atau matikan sekumpulan izin sekaligus — satu baris atau satu kolom. */
  function aturSemua(daftar: string[], nyala: boolean) {
    setDipilih((kini) => {
      const baru = new Set(kini);
      for (const izin of daftar) {
        if (!bisaBeri(izin)) continue;
        if (nyala) baru.add(izin);
        else baru.delete(izin);
      }
      return baru;
    });
  }

  function kirim() {
    setGalat("");
    if (!nama.trim()) return setGalat(t("Nama peran wajib diisi."));

    simpan.mutate();
  }

  const perSumber = new Map(katalog.map((s) => [s.resource, s]));
  const dikenal = new Set(KELOMPOK.flatMap((k) => k.sumber));
  const kelompok = [
    ...KELOMPOK.map((k) => ({ ...k, sumber: k.sumber.filter((s) => perSumber.has(s)) })),
    // Sumber baru dari server yang belum dikenal layar ini tetap tampil.
    { judul: "Lainnya", sumber: katalog.map((s) => s.resource).filter((s) => !dikenal.has(s)) },
  ].filter((k) => k.sumber.length > 0);

  const izinKolom = (aksi: string) =>
    katalog.filter((s) => s.actions.includes(aksi)).map((s) => `${s.resource}.${aksi}`);

  return (
    <Dialog
      judul={awal ? (hanyaBaca ? awal.name : `${t("Ubah peran")} — ${awal.name}`) : t("Tambah peran")}
      terbuka
      onTutup={onTutup}
      lebar="max-w-3xl"
      aksi={
        hanyaBaca ? (
          <Button onClick={onTutup}>{t("Tutup")}</Button>
        ) : (
          <>
            <Button onClick={onTutup}>{t("Batal")}</Button>
            <Button variant="primary" onClick={kirim} disabled={simpan.isPending}>
              {simpan.isPending ? t("Menyimpan...") : t("Simpan")}
            </Button>
          </>
        )
      }
    >
      {awal?.locked ? (
        <p className="mb-3 flex items-center gap-2 rounded-lg bg-slate-100 px-3 py-2 text-sm text-muted">
          <Lock className="h-4 w-4 shrink-0" />
          {t("Peran bawaan dengan semua izin. Tidak bisa diubah atau dihapus, supaya selalu ada yang bisa mengurus OpenOrchestrator.")}
        </p>
      ) : null}

      <div className="grid gap-x-4 sm:grid-cols-2">
        <Isian label={t("Nama")}>
          <input value={nama} onChange={(e) => setNama(e.target.value)} disabled={hanyaBaca} maxLength={48} className={kelasIsian} />
        </Isian>

        <Isian label={t("Keterangan")}>
          <input value={ket} onChange={(e) => setKet(e.target.value)} disabled={hanyaBaca} maxLength={400} className={kelasIsian} />
        </Isian>
      </div>

      {!awal ? (
        <Isian label={t("Salin izin dari")} petunjuk={t("Mulai dari izin peran lain, lalu ubah seperlunya.")}>
          <select
            defaultValue=""
            onChange={(e) => {
              const sumber = semuaPeran.find((r) => r.name === e.target.value);
              if (sumber) setDipilih(new Set([...jabarkan(polaPeran(sumber), katalog)].filter((i) => boleh(i))));
            }}
            className={kelasIsian}
          >
            <option value="">—</option>
            {semuaPeran.map((r) => (
              <option key={r.name} value={r.name}>
                {r.name}
              </option>
            ))}
          </select>
        </Isian>
      ) : null}

      <div className="mt-1 overflow-x-auto rounded-lg border border-line">
        <table className="w-full min-w-[34rem] text-sm">
          <thead>
            <tr className="border-b border-line bg-slate-50/60 text-xs text-muted">
              <th className="px-3 py-2 text-left font-medium">{t("Izin")}</th>
              {AKSI.map((a) => {
                const daftar = izinKolom(a.kode);
                const semua = daftar.length > 0 && daftar.every((i) => dipilih.has(i));

                return (
                  <th key={a.kode} className="w-20 px-2 py-2 text-center font-medium">
                    <label className="inline-flex cursor-pointer flex-col items-center gap-1">
                      <span>{t(a.label)}</span>
                      <input
                        type="checkbox"
                        checked={semua}
                        disabled={hanyaBaca}
                        onChange={(e) => aturSemua(daftar, e.target.checked)}
                        aria-label={t("Semua: {0}", t(a.label))}
                        className="h-4 w-4 rounded border-line"
                      />
                    </label>
                  </th>
                );
              })}
              <th className="w-16 px-2 py-2 text-center font-medium">{t("Semua")}</th>
            </tr>
          </thead>

          <tbody>
            {kelompok.map((k) => (
              <KelompokBaris
                key={k.judul}
                judul={t(k.judul)}
                sumber={k.sumber.map((s) => perSumber.get(s)!)}
                dipilih={dipilih}
                bisaBeri={bisaBeri}
                onAlih={alih}
                onBaris={aturSemua}
              />
            ))}
          </tbody>
        </table>
      </div>

      <p className="mt-2 text-xs text-muted">
        {t("{0} izin dipilih.", dipilih.size)}{" "}
        {!hanyaBaca ? t("Kotak yang mati adalah izin yang tidak dimiliki peran Anda sendiri.") : null}
      </p>

      <Galat pesan={galat} className="mt-3" />
    </Dialog>
  );
}

function KelompokBaris({
  judul,
  sumber,
  dipilih,
  bisaBeri,
  onAlih,
  onBaris,
}: {
  judul: string;
  sumber: SumberIzin[];
  dipilih: Set<string>;
  bisaBeri: (izin: string) => boolean;
  onAlih: (izin: string) => void;
  onBaris: (daftar: string[], nyala: boolean) => void;
}) {
  const { t } = useT();

  return (
    <>
      <tr className="border-b border-line bg-slate-50/60">
        <td colSpan={AKSI.length + 2} className="px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted">
          {judul}
        </td>
      </tr>

      {sumber.map((s) => {
        const daftar = s.actions.map((a) => `${s.resource}.${a}`);
        const jumlah = daftar.filter((i) => dipilih.has(i)).length;
        const catatan = CATATAN[s.resource];

        return (
          <tr key={s.resource} className="border-b border-line/70 last:border-0 hover:bg-slate-50">
            <td className="px-3 py-2">
              <span className="font-medium text-ink">{t(LABEL_SUMBER[s.resource] ?? s.resource)}</span>
              {catatan ? <span className="block text-xs text-muted">{t(catatan)}</span> : null}
            </td>

            {AKSI.map((a) => {
              const izin = `${s.resource}.${a.kode}`;

              if (!s.actions.includes(a.kode)) {
                return (
                  <td key={a.kode} className="px-2 py-2 text-center text-muted/60" aria-hidden="true">
                    —
                  </td>
                );
              }

              const bisa = bisaBeri(izin);

              return (
                <td key={a.kode} className="px-2 py-2 text-center">
                  <input
                    type="checkbox"
                    checked={dipilih.has(izin)}
                    disabled={!bisa}
                    onChange={() => onAlih(izin)}
                    aria-label={`${t(LABEL_SUMBER[s.resource] ?? s.resource)} · ${t(a.label)}`}
                    title={!bisa ? t("Peran Anda tidak punya izin ini.") : undefined}
                    className={cn("h-4 w-4 rounded border-line", bisa ? "cursor-pointer" : "cursor-not-allowed")}
                  />
                </td>
              );
            })}

            <td className="px-2 py-2 text-center">
              <input
                type="checkbox"
                checked={jumlah === daftar.length}
                ref={(el) => {
                  if (el) el.indeterminate = jumlah > 0 && jumlah < daftar.length;
                }}
                disabled={!daftar.some(bisaBeri)}
                onChange={(e) => onBaris(daftar, e.target.checked)}
                aria-label={t("Semua: {0}", t(LABEL_SUMBER[s.resource] ?? s.resource))}
                className="h-4 w-4 cursor-pointer rounded border-line"
              />
            </td>
          </tr>
        );
      })}
    </>
  );
}
