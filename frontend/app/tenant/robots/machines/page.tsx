"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Copy, KeyRound, Pencil, Plus, Trash2 } from "lucide-react";
import { OpenOrchestratorApi, errorText, type Machine } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf } from "@/lib/utils";
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
              judul: "Slot",
              sel: (m) => (
                <span className="tabular-nums" title={t("Job berjalan / slot")}>
                  {m.activeJobs ?? 0} / {slotEfektif(m)}
                </span>
              ),
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

function DialogMesinBaru({ onTutup, onSelesai }: { onTutup: () => void; onSelesai: () => void }) {
  const { t } = useT();
  const [nama, setNama] = useState("");
  const [ket, setKet] = useState("");
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: () => OpenOrchestratorApi.saveMachine({ name: nama.trim(), description: ket || undefined }),
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
            onClick={() => (nama.trim() ? simpan.mutate() : setGalat(t("Nama mesin wajib diisi.")))}
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
  const [slot, setSlot] = useState(String(mesin.slots ?? 1));
  const [lease, setLease] = useState(String(mesin.leaseSeconds ?? 180));
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: () =>
      OpenOrchestratorApi.updateMachine(mesin.name, {
        description: ket,
        slots: Number(slot),
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
          <Button variant="primary" onClick={() => simpan.mutate()} disabled={simpan.isPending}>
            {simpan.isPending ? t("Menyimpan...") : t("Simpan")}
          </Button>
        </>
      }
    >
      <div className="grid gap-x-4 sm:grid-cols-2">
        <Isian
          label={t("Slot")}
          petunjuk={t("Job yang boleh berjalan bersamaan. Windows 10/11 hanya mengizinkan satu sesi; lebih dari satu butuh Windows Server dengan RDS.")}
        >
          <input type="number" min={1} max={50} value={slot} onChange={(e) => setSlot(e.target.value)} className={kelasIsian} />
        </Isian>
        <Isian label={t("Lease penyiapan (detik)")} petunjuk={t("Batas waktu menyiapkan sesi Windows tanpa kabar dari agent.")}>
          <input type="number" min={30} max={3600} value={lease} onChange={(e) => setLease(e.target.value)} className={kelasIsian} />
        </Isian>
      </div>
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
