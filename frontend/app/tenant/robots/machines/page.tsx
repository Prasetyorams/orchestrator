"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Copy, KeyRound, Pencil, Plus, Trash2 } from "lucide-react";
import { OpenOrchestratorApi, TIPE_RUNTIME, errorText, type Machine } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { BilahAlat } from "@/components/HalamanFolder";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

/**
 * Mesin tempat robot berjalan.
 *
 * Mesin untuk robot unattended punya MACHINE KEY: identitas Robot Agent di
 * mesin itu. Kuncinya dibuat di sini dan terlihat SEKALI; Orchestrator hanya
 * menyimpan hash-nya. Mesin robot attended (JakRunner) tidak butuh kunci —
 * ia ikut terdaftar sendiri saat robotnya pertama kali berdenyut.
 */
export default function Mesin() {
  const { t, tp } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();
  const [galat, setGalat] = useState("");
  const [baru, setBaru] = useState(false);
  const [ubah, setUbah] = useState<Machine | null>(null);
  const [kunci, setKunci] = useState<{ mesin: string; kunci: string } | null>(null);

  const mesin = useQuery({ queryKey: ["machines"], queryFn: OpenOrchestratorApi.machines, refetchInterval: 10_000 });
  const segarkan = () => klien.invalidateQueries({ queryKey: ["machines"] });

  // ?ubah=NAMA — dari tombol mesin di Start Job — membuka setelan mesin itu
  // begitu daftarnya ada. Dibaca lewat window: useSearchParams menuntut
  // pembungkus Suspense di seluruh halaman hanya untuk nilai awal ini.
  const [diminta, setDiminta] = useState<string | null>(null);
  useEffect(() => setDiminta(new URLSearchParams(window.location.search).get("ubah")), []);
  const bolehUbah = boleh("machines.update");
  useEffect(() => {
    if (!diminta || !mesin.data) return;
    const m = mesin.data.find((x) => x.name === diminta);
    if (m && bolehUbah) setUbah(m);
    setDiminta(null);
    // Memuat ulang halaman tidak boleh membuka lagi dialog yang sudah ditutup.
    window.history.replaceState(null, "", window.location.pathname);
  }, [diminta, mesin.data, bolehUbah]);

  const hapus = useMutation({
    mutationFn: OpenOrchestratorApi.deleteMachine,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const buatKunci = useMutation({
    mutationFn: OpenOrchestratorApi.createMachineKey,
    onSuccess: (hasil, nama) => {
      setKunci({ mesin: nama, kunci: hasil.machineKey });
      segarkan();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  const cabutKunci = useMutation({
    mutationFn: OpenOrchestratorApi.revokeMachineKey,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div>
      <BilahAlat
        aksi={
          boleh("machines.create") ? (
            <Button variant="primary" onClick={() => setBaru(true)}>
              <Plus size={16} />
              {t("Tambah mesin")}
            </Button>
          ) : null
        }
      >
        <p className="text-sm text-muted">
          {t("Mesin robot unattended butuh machine key untuk Robot Agent-nya. Mesin robot attended ikut terdaftar sendiri saat robotnya pertama kali berdenyut.")}
        </p>
      </BilahAlat>

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={mesin.data ?? []}
          kunci={(m) => m.name}
          kosong={mesin.isLoading ? "Memuat..." : "Belum ada data."}
          kolom={[
            { judul: "Nama", sel: (m) => <span className="font-medium">{m.name}</span>, urut: (m) => m.name },
            {
              judul: "Robot Agent",
              sel: (m) =>
                m.agentVersion ? (
                  <span className="flex items-center gap-2" title={[m.agentOs, m.agentHostName].filter(Boolean).join(" · ")}>
                    <Badge value={m.agentOnline ? "AVAILABLE" : "OFFLINE"} label={t(m.agentOnline ? "Online" : "Offline")} />
                    <span className="text-xs text-muted">v{m.agentVersion}</span>
                  </span>
                ) : (
                  <span className="text-muted">-</span>
                ),
              urut: (m) => (m.agentOnline ? 1 : 0),
            },
            {
              judul: "Runtime",
              sel: (m) => <RingkasRuntime mesin={m} />,
              urut: (m) => m.slots,
            },
            {
              judul: "Machine key",
              sel: (m) =>
                m.hasKey ? (
                  <span className="text-muted" title={dateTimeOf(m.keyCreatedAt)}>
                    <code className="text-xs">{m.keyPrefix}…</code>
                  </span>
                ) : (
                  <span className="text-muted">{t("Belum ada")}</span>
                ),
            },
            {
              judul: "Robot",
              sel: (m) => (
                <span className="tabular-nums" title={t("Robot unattended / semua robot di mesin ini")}>
                  {m.unattendedRobotCount ?? 0} / {m.robotCount}
                </span>
              ),
              urut: (m) => m.robotCount,
            },
            {
              judul: "Denyut",
              sel: (m) => <span className="text-muted">{dateTimeOf(m.lastAgentHeartbeatAt)}</span>,
              urut: (m) => m.lastAgentHeartbeatAt,
            },
            {
              judul: "Keterangan",
              sel: (m) => <span className="text-muted">{m.description ? tp(m.description) : "-"}</span>,
            },
            {
              judul: "",
              sel: (m) => (
                <div className="flex justify-end gap-1">
                  {boleh("machines.update") ? (
                    <>
                      <IconButton label={t("Ubah")} onClick={() => setUbah(m)}>
                        <Pencil size={16} />
                      </IconButton>
                      <IconButton
                        label={t(m.hasKey ? "Ganti machine key" : "Buat machine key")}
                        onClick={() => {
                          if (
                            !m.hasKey ||
                            window.confirm(t("Kunci lama mesin \"{0}\" langsung berhenti berlaku, dan Robot Agent di sana harus diberi kunci baru. Lanjutkan?", m.name))
                          ) {
                            buatKunci.mutate(m.name);
                          }
                        }}
                      >
                        <KeyRound size={16} />
                      </IconButton>
                    </>
                  ) : null}
                  {boleh("machines.delete") ? (
                    <IconButton
                      label={t("Hapus")}
                      tone="danger"
                      onClick={() => {
                        if (window.confirm(`${t("Yakin menghapus")} "${m.name}"?`)) hapus.mutate(m.name);
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

      {baru ? <DialogMesinBaru onTutup={() => setBaru(false)} onSelesai={segarkan} /> : null}

      {ubah ? (
        <DialogUbahMesin
          mesin={ubah}
          onTutup={() => setUbah(null)}
          onSelesai={segarkan}
          onCabutKunci={() => {
            if (window.confirm(t("Cabut machine key \"{0}\"? Robot Agent di mesin itu langsung berhenti bekerja.", ubah.name))) {
              cabutKunci.mutate(ubah.name);
              setUbah(null);
            }
          }}
        />
      ) : null}

      {kunci ? <DialogKunci mesin={kunci.mesin} kunci={kunci.kunci} onTutup={() => setKunci(null)} /> : null}
    </div>
  );
}

/** Slot yang benar-benar berlaku: Windows 10/11 hanya mengizinkan satu sesi interaktif. */
function slotEfektif(m: Machine): number {
  const slot = m.slots ?? 1;
  return m.maxInteractiveSessions && m.maxInteractiveSessions > 0 ? Math.min(slot, m.maxInteractiveSessions) : slot;
}

/** "Production 2 · Testing 1", dan job Robot Agent yang sedang berjalan dari slot yang berlaku. */
function RingkasRuntime({ mesin: m }: { mesin: Machine }) {
  const { t } = useT();
  const daftar = Object.entries(m.runtimes ?? {});

  if (daftar.length === 0) return <span className="text-danger">{t("Tanpa runtime")}</span>;

  return (
    <span className="flex flex-col">
      <span>{daftar.map(([tipe, n]) => `${t(tipe)} ${n}`).join(" · ")}</span>
      <span className="text-[11px] tabular-nums text-muted" title={t("Job Robot Agent berjalan / slot yang berlaku")}>
        {t("{0} dari {1} slot terpakai", m.activeJobs ?? 0, slotEfektif(m))}
      </span>
    </span>
  );
}

/**
 * Jumlah runtime per tipe. Jumlahnya adalah slot mesin: job tipe itu yang
 * boleh berjalan bersamaan di sana.
 */
function IsianRuntime({ nilai, onUbah }: { nilai: Record<string, string>; onUbah: (n: Record<string, string>) => void }) {
  const { t } = useT();

  return (
    <fieldset className="mb-3">
      <legend className="mb-1 text-xs font-medium text-muted">{t("Runtime")}</legend>
      <div className="grid grid-cols-3 gap-3">
        {TIPE_RUNTIME.map((tipe) => (
          <label key={tipe} className="block">
            <span className="mb-1 block text-xs text-muted">{t(tipe)}</span>
            <input
              type="number"
              inputMode="numeric"
              min={0}
              max={50}
              step={1}
              value={nilai[tipe] ?? "0"}
              onChange={(e) => onUbah({ ...nilai, [tipe]: e.target.value })}
              className={kelasIsian}
            />
          </label>
        ))}
      </div>
      <span className="mt-1 block text-xs text-muted">
        {t("Berapa job tiap tipe yang boleh berjalan bersamaan di mesin ini. Start Job hanya menawarkan tipe yang dimiliki mesin di folder itu. Windows 10/11 hanya mengizinkan satu sesi; lebih dari satu butuh Windows Server dengan RDS.")}
      </span>
    </fieldset>
  );
}

/** Isian runtime → yang dikirim ke server; null kalau ada yang bukan bilangan bulat 0–50. */
function runtimeDariIsian(nilai: Record<string, string>): Record<string, number> | null {
  const hasil: Record<string, number> = {};

  for (const tipe of TIPE_RUNTIME) {
    const teks = (nilai[tipe] ?? "0").trim() || "0";
    if (!/^\d+$/.test(teks) || Number(teks) > 50) return null;
    hasil[tipe] = Number(teks);
  }

  return hasil;
}

/** Mesin baru: satu runtime Production, sama dengan bawaan server. */
function isianAwal(m?: Machine): Record<string, string> {
  return Object.fromEntries(
    TIPE_RUNTIME.map((tipe) => [tipe, String(m ? (m.runtimes?.[tipe] ?? 0) : tipe === "Production" ? 1 : 0)]),
  );
}

function DialogMesinBaru({ onTutup, onSelesai }: { onTutup: () => void; onSelesai: () => void }) {
  const { t } = useT();
  const [nama, setNama] = useState("");
  const [ket, setKet] = useState("");
  const [runtime, setRuntime] = useState(isianAwal());
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: () =>
      OpenOrchestratorApi.saveMachine({
        name: nama.trim(),
        description: ket || undefined,
        runtimes: runtimeDariIsian(runtime) ?? undefined,
      }),
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <Dialog
      judul={t("Tambah mesin")}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button
            variant="primary"
            onClick={() =>
              !nama.trim()
                ? setGalat(t("Nama mesin wajib diisi."))
                : !runtimeDariIsian(runtime)
                  ? setGalat(t("Jumlah runtime harus bilangan bulat 0 sampai 50."))
                  : simpan.mutate()
            }
            disabled={simpan.isPending}
          >
            {simpan.isPending ? t("Menyimpan...") : t("Simpan")}
          </Button>
        </>
      }
    >
      <Isian label={t("Nama")} petunjuk={t("Sebaiknya sama dengan nama komputer Windows-nya.")}>
        <input value={nama} onChange={(e) => setNama(e.target.value)} className={kelasIsian} />
      </Isian>
      <Isian label={t("Keterangan")}>
        <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
      </Isian>
      <IsianRuntime nilai={runtime} onUbah={setRuntime} />
      <Galat pesan={galat} />
    </Dialog>
  );
}

function DialogUbahMesin({
  mesin,
  onTutup,
  onSelesai,
  onCabutKunci,
}: {
  mesin: Machine;
  onTutup: () => void;
  onSelesai: () => void;
  onCabutKunci: () => void;
}) {
  const { t } = useT();
  const [ket, setKet] = useState(mesin.description ?? "");
  const [runtime, setRuntime] = useState(isianAwal(mesin));
  const [lease, setLease] = useState(String(mesin.leaseSeconds ?? 180));
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: () =>
      OpenOrchestratorApi.updateMachine(mesin.name, {
        description: ket,
        runtimes: runtimeDariIsian(runtime) ?? undefined,
        leaseSeconds: Number(lease),
      }),
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <Dialog
      judul={`${t("Ubah mesin")} — ${mesin.name}`}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          {mesin.hasKey ? (
            <Button onClick={onCabutKunci} className="mr-auto text-danger">
              {t("Cabut machine key")}
            </Button>
          ) : null}
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button
            variant="primary"
            onClick={() =>
              runtimeDariIsian(runtime) ? simpan.mutate() : setGalat(t("Jumlah runtime harus bilangan bulat 0 sampai 50."))
            }
            disabled={simpan.isPending}
          >
            {simpan.isPending ? t("Menyimpan...") : t("Simpan")}
          </Button>
        </>
      }
    >
      <IsianRuntime nilai={runtime} onUbah={setRuntime} />
      <Isian label={t("Lease penyiapan (detik)")} petunjuk={t("Batas waktu menyiapkan sesi Windows tanpa kabar dari agent.")}>
        <input
          type="number"
          min={30}
          max={3600}
          value={lease}
          onChange={(e) => setLease(e.target.value)}
          className={cn(kelasIsian, "w-40")}
        />
      </Isian>
      <Isian label={t("Keterangan")}>
        <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
      </Isian>

      {mesin.agentVersion ? (
        <p className="mb-2 text-xs text-muted">
          {t(
            "Robot Agent {0} di {1}, terakhir masuk {2}.",
            mesin.agentVersion,
            mesin.agentOs ?? mesin.agentHostName ?? "-",
            dateTimeOf(mesin.lastAgentLoginAt),
          )}
        </p>
      ) : null}

      <Galat pesan={galat} />
    </Dialog>
  );
}

/** Kuncinya terlihat sekali ini saja; setelah dialog ditutup, yang tersisa hanya hash-nya. */
function DialogKunci({ mesin, kunci, onTutup }: { mesin: string; kunci: string; onTutup: () => void }) {
  const { t } = useT();
  const [tersalin, setTersalin] = useState(false);

  return (
    <Dialog
      judul={`${t("Machine key")} — ${mesin}`}
      terbuka
      onTutup={onTutup}
      aksi={
        <Button variant="primary" onClick={onTutup}>
          {t("Sudah saya simpan")}
        </Button>
      }
    >
      <p className="mb-3 text-sm">
        {t("Salin kunci ini sekarang dan masukkan saat memasang Robot Agent di mesin ini. Kunci ini tidak akan ditampilkan lagi.")}
      </p>

      <div className="mb-3 flex items-center gap-2">
        <code className="block flex-1 break-all rounded-lg bg-neutral-900 px-3 py-2 text-xs text-neutral-100 dark:bg-black/40">
          {kunci}
        </code>
        <IconButton
          label={t(tersalin ? "Tersalin" : "Salin")}
          onClick={() => {
            void navigator.clipboard?.writeText(kunci).then(() => setTersalin(true));
          }}
        >
          {tersalin ? <Check size={16} /> : <Copy size={16} />}
        </IconButton>
      </div>

      <p className="text-xs text-muted">
        {t("Kunci yang hilang tidak bisa ditampilkan ulang — buat kunci baru, dan kunci lama langsung berhenti berlaku.")}
      </p>
    </Dialog>
  );
}
