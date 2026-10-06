"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Pencil, Plus, Trash2 } from "lucide-react";
import { OpenOrchestratorApi, errorText, type Robot, type RobotSetelan } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { BilahAlat } from "@/components/HalamanFolder";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { PekerjaanRobot } from "@/components/PekerjaanRobot";

/**
 * Semua robot penyewa, dengan folder tempat masing-masing ditugaskan.
 *
 * Robot tidak tinggal di satu folder: satu mesin bisa melayani beberapa
 * folder. Menugaskannya ada di Setelan setiap folder; di sini robotnya
 * dilihat, disetel, dan, kalau sudah tidak dipakai, dihapus.
 *
 * Robot UNATTENDED didaftarkan di sini: diikat ke mesin yang Robot Agent-nya
 * akan melayaninya, beserta akun Windows yang dipakai agent untuk login.
 * Sandi Windows hanya bisa ditulis — dasbor tidak pernah menerimanya kembali.
 */
export default function RobotPenyewa() {
  const { t } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();
  const [galat, setGalat] = useState("");
  const [dialog, setDialog] = useState<{ robot: Robot | null } | null>(null);

  const robots = useQuery({ queryKey: ["robots", "semua"], queryFn: () => OpenOrchestratorApi.robots(), refetchInterval: 10_000 });
  const segarkan = () => klien.invalidateQueries({ queryKey: ["robots"] });

  const hapus = useMutation({
    mutationFn: OpenOrchestratorApi.deleteRobot,
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div>
      <BilahAlat
        aksi={
          boleh("robots.create") ? (
            <Button variant="primary" onClick={() => setDialog({ robot: null })}>
              <Plus size={16} />
              {t("Tambah robot")}
            </Button>
          ) : null
        }
      >
        <p className="text-sm text-muted">
          {t(
            "Robot mendaftarkan dirinya sendiri saat JakRunner berdenyut pertama kali, dan langsung ditugaskan ke folder Shared.",
          )}{" "}
          {t("Robot unattended ditambahkan di sini dan diikat ke mesin Robot Agent-nya.")}
        </p>
      </BilahAlat>

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={robots.data ?? []}
          kunci={(r) => r.name}
          kosong={robots.isLoading ? "Memuat..." : "Belum ada robot yang mendaftar."}
          kolom={[
            {
              judul: "Nama",
              sel: (r) => (
                <span className="flex items-center gap-1.5 font-medium">
                  {r.name}
                  {r.needsAttention ? (
                    <span title={t("Perlu perhatian: {0}", r.needsAttention)} className="text-danger">
                      <AlertTriangle size={14} />
                    </span>
                  ) : null}
                </span>
              ),
              urut: (r) => r.name,
            },
            { judul: "Mesin|satu", sel: (r) => <span className="text-muted">{r.machineName ?? "-"}</span>, urut: (r) => r.machineName },
            { judul: "Tipe", sel: (r) => r.type, urut: (r) => r.type },
            {
              judul: "Sesi Windows",
              sel: (r) => <SelSesi robot={r} />,
            },
            {
              judul: "Pekerjaan|satu",
              sel: (r) => <PekerjaanRobot robot={r} />,
            },
            {
              judul: "Folder",
              sel: (r) =>
                r.folders?.length ? (
                  <span className="text-muted" title={r.folders.join(", ")}>
                    {r.folders.length > 3 ? `${r.folders.slice(0, 3).join(", ")} +${r.folders.length - 3}` : r.folders.join(", ")}
                  </span>
                ) : (
                  <span className="text-warn">{t("Tidak di folder mana pun")}</span>
                ),
            },
            {
              judul: "Denyut",
              sel: (r) => <span className="text-muted">{dateTimeOf(r.lastHeartbeatAt)}</span>,
              urut: (r) => r.lastHeartbeatAt,
            },
            { judul: "Status", sel: (r) => <Badge value={r.status} />, urut: (r) => r.status },
            {
              judul: "",
              sel: (r) => (
                <div className="flex justify-end gap-1">
                  {boleh("robots.create") ? (
                    <IconButton label={t("Ubah")} onClick={() => setDialog({ robot: r })}>
                      <Pencil size={16} />
                    </IconButton>
                  ) : null}
                  {boleh("robots.delete") ? (
                    <IconButton
                      label={t("Hapus")}
                      tone="danger"
                      onClick={() => {
                        if (window.confirm(`${t("Yakin menghapus")} "${r.name}"?`)) hapus.mutate(r.name);
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

      {dialog ? <DialogRobot robot={dialog.robot} onTutup={() => setDialog(null)} onSelesai={segarkan} /> : null}
    </div>
  );
}

/** Sesi Windows menurut Robot Agent: nomor, keadaan, dan alasan kalau belum siap. */
function SelSesi({ robot }: { robot: Robot }) {
  const { t, tp } = useT();

  if (!robot.machineId) return <span className="text-muted">-</span>;
  if (!robot.agentState) return <span className="text-muted">{t("Belum ada kabar dari agent")}</span>;

  const siap = robot.sessionReady !== false && robot.agentState !== "Error";
  const keterangan = robot.reasonText ? tp(robot.reasonText) : robot.reasonCode;

  return (
    <span className="flex flex-col">
      <span className="flex items-center gap-2">
        <Badge value={siap ? "AVAILABLE" : "STOPPING"} label={t(siap ? "Siap" : "Belum siap")} />
        <span className="text-xs text-muted">
          {robot.sessionState ?? "-"}
          {robot.sessionId != null ? ` #${robot.sessionId}` : ""}
          {robot.executorState === "Running" && robot.executorPid ? ` · PID ${robot.executorPid}` : ""}
        </span>
      </span>
      {!siap && keterangan ? <span className="mt-0.5 text-xs text-warn">{keterangan}</span> : null}
      {robot.resolutionWidth ? (
        <span className="mt-0.5 text-xs text-muted">
          {t("Resolusi {0}", `${robot.resolutionWidth}×${robot.resolutionHeight}${robot.resolutionDepth ? ` · ${robot.resolutionDepth}-bit` : ""}`)}
        </span>
      ) : null}
    </span>
  );
}

/** Kedalaman warna sesi RDP; 0 = bawaan klien RDP. */
const KEDALAMAN_WARNA = [0, 32, 24, 16, 15];

/** Lebar dan tinggi berpasangan: dua-duanya 0 (bawaan), atau dua-duanya 200–8192 — sama dengan server. */
function periksaResolusi(lebar: string, tinggi: string): boolean {
  const l = Number(lebar.trim() || "0");
  const g = Number(tinggi.trim() || "0");
  const sah = (n: number) => Number.isInteger(n) && n >= 200 && n <= 8192;
  return (l === 0 && g === 0) || (sah(l) && sah(g));
}

const TIPE_ROBOT = ["Unattended", "Attended", "NonProduction"];

function DialogRobot({ robot, onTutup, onSelesai }: { robot: Robot | null; onTutup: () => void; onSelesai: () => void }) {
  const { t } = useT();
  const mesin = useQuery({ queryKey: ["machines"], queryFn: OpenOrchestratorApi.machines });

  const [nama, setNama] = useState(robot?.name ?? "");
  const [tipe, setTipe] = useState(robot?.type ?? "Unattended");
  const [namaMesin, setNamaMesin] = useState(robot?.machineName ?? "");
  const [akun, setAkun] = useState(robot?.windowsUsername ?? "");
  const [sandi, setSandi] = useState("");
  const [sandiLokal, setSandiLokal] = useState(robot?.windowsPasswordLocal ?? false);
  const [kebijakan, setKebijakan] = useState<"Logoff" | "KeepLoggedIn">(robot?.sessionPolicy ?? "Logoff");
  const [lebar, setLebar] = useState(robot?.resolutionWidth ? String(robot.resolutionWidth) : "");
  const [tinggi, setTinggi] = useState(robot?.resolutionHeight ? String(robot.resolutionHeight) : "");
  const [kedalaman, setKedalaman] = useState(robot?.resolutionDepth ?? 0);
  const [ket, setKet] = useState(robot?.description ?? "");
  const [galat, setGalat] = useState("");

  const unattended = tipe !== "Attended";

  const simpan = useMutation({
    mutationFn: () => {
      const setelan: RobotSetelan = {
        type: tipe,
        machineName: namaMesin,
        description: ket,
        ...(unattended
          ? {
              windowsUsername: akun,
              windowsPassword: sandiLokal ? undefined : sandi || undefined,
              windowsPasswordLocal: sandiLokal,
              sessionPolicy: kebijakan,
              resolutionWidth: Number(lebar.trim() || "0"),
              resolutionHeight: Number(tinggi.trim() || "0"),
              resolutionDepth: kedalaman,
            }
          : {}),
      };

      return robot ? OpenOrchestratorApi.updateRobot(robot.name, setelan) : OpenOrchestratorApi.saveRobot({ name: nama.trim(), ...setelan });
    },
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    setGalat("");
    if (!robot && !nama.trim()) return setGalat(t("Nama robot wajib diisi."));
    if (unattended && namaMesin && akun && !/[\\@]/.test(akun)) {
      return setGalat(t("Tulis akun Windows sebagai DOMAIN\\nama, atau .\\nama untuk akun lokal."));
    }
    if (unattended && !periksaResolusi(lebar, tinggi)) {
      return setGalat(t("Resolusi layar: lebar dan tinggi diisi berpasangan, masing-masing 200 sampai 8192 — atau keduanya 0 untuk bawaan agent (1024x768)."));
    }
    simpan.mutate();
  }

  return (
    <Dialog
      judul={robot ? `${t("Ubah robot")} — ${robot.name}` : t("Tambah robot")}
      terbuka
      onTutup={onTutup}
      lebar="max-w-xl"
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" onClick={kirim} disabled={simpan.isPending}>
            {simpan.isPending ? t("Menyimpan...") : t("Simpan")}
          </Button>
        </>
      }
    >
      <div className="grid gap-x-4 sm:grid-cols-2">
        <Isian label={t("Nama")}>
          <input value={nama} onChange={(e) => setNama(e.target.value)} disabled={!!robot} className={kelasIsian} />
        </Isian>
        <Isian label={t("Tipe")}>
          <select value={tipe} onChange={(e) => setTipe(e.target.value)} className={kelasIsian}>
            {TIPE_ROBOT.map((x) => (
              <option key={x} value={x}>
                {x}
              </option>
            ))}
          </select>
        </Isian>
      </div>

      <Isian
        label={t("Mesin|satu")}
        petunjuk={unattended ? t("Robot Agent di mesin ini yang menjalankan job robot ini.") : undefined}
      >
        <select value={namaMesin} onChange={(e) => setNamaMesin(e.target.value)} className={kelasIsian}>
          <option value="">—</option>
          {(mesin.data ?? []).map((m) => (
            <option key={m.name} value={m.name}>
              {m.name}
              {m.hasKey ? "" : ` (${t("belum ada machine key")})`}
            </option>
          ))}
        </select>
      </Isian>

      {unattended ? (
        <>
          <div className="grid gap-x-4 sm:grid-cols-2">
            <Isian label={t("Akun Windows")} petunjuk={t("DOMAIN\\nama, atau .\\nama untuk akun lokal.")}>
              <input value={akun} onChange={(e) => setAkun(e.target.value)} autoComplete="off" className={kelasIsian} />
            </Isian>
            <Isian
              label={t("Sandi Windows")}
              petunjuk={
                robot?.hasWindowsPassword ? t("Sudah tersimpan. Biarkan kosong untuk tetap memakai yang lama.") : undefined
              }
            >
              <input
                type="password"
                value={sandi}
                onChange={(e) => setSandi(e.target.value)}
                disabled={sandiLokal}
                autoComplete="new-password"
                className={kelasIsian}
              />
            </Isian>
          </div>

          <label className="mb-3 flex items-start gap-2 text-sm">
            <input type="checkbox" checked={sandiLokal} onChange={(e) => setSandiLokal(e.target.checked)} className="mt-0.5" />
            <span>
              {t("Sandi disimpan di mesin robot, bukan di Orchestrator")}
              <span className="block text-xs text-muted">
                {t("Orchestrator hanya menyimpan nama akunnya; sandinya diisi saat memasang Robot Agent.")}
              </span>
            </span>
          </label>

          <Isian label={t("Sesudah job selesai")}>
            <select value={kebijakan} onChange={(e) => setKebijakan(e.target.value as "Logoff" | "KeepLoggedIn")} className={kelasIsian}>
              <option value="Logoff">{t("Logoff — tidak ada jendela sisa untuk job berikutnya")}</option>
              <option value="KeepLoggedIn">{t("Tetap login — untuk aplikasi yang harus terus terbuka")}</option>
            </select>
          </Isian>

          <fieldset className="mb-3">
            <legend className="mb-1 text-sm font-medium text-ink">{t("Resolusi layar (unattended)")}</legend>
            <div className="grid grid-cols-3 gap-3">
              <label className="block">
                <span className="mb-1 block text-xs text-muted">{t("Lebar")}</span>
                <input
                  type="number"
                  inputMode="numeric"
                  min={0}
                  max={8192}
                  step={1}
                  value={lebar}
                  placeholder="0"
                  onChange={(e) => setLebar(e.target.value)}
                  className={kelasIsian}
                />
              </label>
              <label className="block">
                <span className="mb-1 block text-xs text-muted">{t("Tinggi")}</span>
                <input
                  type="number"
                  inputMode="numeric"
                  min={0}
                  max={8192}
                  step={1}
                  value={tinggi}
                  placeholder="0"
                  onChange={(e) => setTinggi(e.target.value)}
                  className={kelasIsian}
                />
              </label>
              <label className="block">
                <span className="mb-1 block text-xs text-muted">{t("Kedalaman warna")}</span>
                <select value={kedalaman} onChange={(e) => setKedalaman(Number(e.target.value))} className={kelasIsian}>
                  {KEDALAMAN_WARNA.map((k) => (
                    <option key={k} value={k}>
                      {k === 0 ? t("Bawaan") : `${k}-bit`}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <span className="mt-1 block text-xs text-muted">
              {t("0 = bawaan agent (1024x768). Hanya berlaku untuk robot yang sesinya dibuat agent (sandi Windows disimpan di Orchestrator). Sesi konsol/auto-logon memakai resolusi layar mesin.")}
            </span>
            {sandiLokal && (Number(lebar) > 0 || Number(tinggi) > 0) ? (
              <span className="mt-1 block text-xs text-warn">
                {t("Sandi robot ini disimpan di mesin robot, jadi sesinya bukan buatan agent dan resolusi ini tidak diterapkan.")}
              </span>
            ) : null}
          </fieldset>
        </>
      ) : null}

      <Isian label={t("Keterangan")}>
        <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
      </Isian>

      <Galat pesan={galat} />
    </Dialog>
  );
}
