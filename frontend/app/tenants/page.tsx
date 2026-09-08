"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText, type User } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, CardHeader } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

export default function PenyewaDanPengguna() {
  const { t } = useT();
  const klien = useQueryClient();

  const [sunting, setSunting] = useState<User | null>(null);
  const [baru, setBaru] = useState(false);
  const [galat, setGalat] = useState("");

  const penyewa = useQuery({ queryKey: ["tenants"], queryFn: ForgeHubApi.tenants });
  const pengguna = useQuery({ queryKey: ["users"], queryFn: ForgeHubApi.users });
  const peran = useQuery({ queryKey: ["roles"], queryFn: ForgeHubApi.roles });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteUser,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["users"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div className="space-y-6">
      <h1 className="text-lg font-semibold text-ink">{t("Penyewa")}</h1>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <Card>
        <CardHeader title={t("Penyewa")} />
        <DataTable
          data={penyewa.data ?? []}
          kunci={(p) => p.name}
          perHalaman={0}
          kolom={[
            { judul: "Nama", sel: (p) => <span className="font-medium">{p.displayName}</span> },
            { judul: "Kode", sel: (p) => <code className="text-xs text-muted">{p.name}</code> },
            { judul: "Pengguna", sel: (p) => <span className="tabular-nums">{p.userCount}</span> },
            { judul: "Robot", sel: (p) => <span className="tabular-nums">{p.robotCount}</span> },
            { judul: "Dibuat", sel: (p) => <span className="text-muted">{dateTimeOf(p.createdAt)}</span> },
          ]}
        />
      </Card>

      <Card>
        <CardHeader
          title={t("Pengguna")}
          action={
            <Button variant="primary" onClick={() => setBaru(true)}>
              {t("Tambah")}
            </Button>
          }
        />
        <DataTable
          data={pengguna.data ?? []}
          kunci={(u) => u.username}
          onBuka={setSunting}
          kolom={[
            {
              judul: "Nama pengguna",
              sel: (u) => <span className="font-medium">{u.username}</span>,
              urut: (u) => u.username,
            },
            { judul: "Nama", sel: (u) => u.displayName, urut: (u) => u.displayName },
            { judul: "Peran", sel: (u) => <Badge value={u.role.toUpperCase()} />, urut: (u) => u.role },
            {
              judul: "Status",
              sel: (u) => <Badge value={u.isActive ? "AVAILABLE" : "STOPPED"} />,
              urut: (u) => String(u.isActive),
            },
            {
              judul: "Masuk terakhir",
              sel: (u) => <span className="text-muted">{dateTimeOf(u.lastLoginAt)}</span>,
              urut: (u) => u.lastLoginAt,
            },
            {
              judul: "",
              sel: (u) => (
                <div className="flex justify-end">
                  <Button
                    variant="ghost"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${u.username}"?`)) hapus.mutate(u.username);
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

      {baru || sunting ? (
        <DialogPengguna
          key={sunting?.username ?? "baru"}
          awal={sunting}
          peran={(peran.data ?? []).map((p) => p.name)}
          onTutup={() => {
            setBaru(false);
            setSunting(null);
          }}
          onSelesai={() => klien.invalidateQueries({ queryKey: ["users"] })}
        />
      ) : null}
    </div>
  );
}

function DialogPengguna({
  awal,
  peran,
  onTutup,
  onSelesai,
}: {
  awal: User | null;
  peran: string[];
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const [username, setUsername] = useState(awal?.username ?? "");
  const [namaTampil, setNamaTampil] = useState(awal?.displayName ?? "");
  const [surel, setSurel] = useState(awal?.email ?? "");
  const [sandi, setSandi] = useState("");
  const [namaPeran, setNamaPeran] = useState(awal?.role ?? "Automation User");
  const [aktif, setAktif] = useState(awal?.isActive ?? true);
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: ForgeHubApi.saveUser,
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <Dialog
      judul={awal ? `${t("Sunting")} — ${awal.username}` : `${t("Tambah")} ${t("Pengguna").toLowerCase()}`}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button
            variant="primary"
            disabled={simpan.isPending}
            onClick={() => {
              if (!username.trim()) return setGalat("Nama pengguna wajib diisi.");
              if (!awal && sandi.length < 6) {
                return setGalat("Pengguna baru butuh kata sandi minimal 6 karakter.");
              }

              // Kata sandi kosong TIDAK dikirim. Server memakai syarat "hanya
              // kalau dikirim", jadi menyunting surel tidak akan mengosongkan
              // sandinya — dan yang bersangkutan tidak gagal masuk besok pagi.
              simpan.mutate({
                username: username.trim(),
                displayName: namaTampil || undefined,
                email: surel || undefined,
                role: namaPeran,
                isActive: aktif,
                ...(sandi ? { password: sandi } : {}),
              });
            }}
          >
            {t("Simpan")}
          </Button>
        </>
      }
    >
      <Isian label={t("Nama pengguna")}>
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          disabled={!!awal}
          className={kelasIsian}
        />
      </Isian>

      <Isian label={t("Nama")}>
        <input value={namaTampil} onChange={(e) => setNamaTampil(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian label="Surel">
        <input type="email" value={surel} onChange={(e) => setSurel(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian
        label={t("Kata sandi")}
        petunjuk={awal ? "Kosongkan kalau tidak ingin menggantinya." : "Minimal 6 karakter."}
      >
        <input type="password" value={sandi} onChange={(e) => setSandi(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian label={t("Peran")}>
        <select value={namaPeran} onChange={(e) => setNamaPeran(e.target.value)} className={kelasIsian}>
          {peran.map((p) => (
            <option key={p}>{p}</option>
          ))}
        </select>
      </Isian>

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={aktif}
          onChange={(e) => setAktif(e.target.checked)}
          className="h-4 w-4 rounded border-line"
        />
        Aktif
      </label>

      {galat ? <p className="mt-3 text-sm text-danger">{galat}</p> : null}
    </Dialog>
  );
}
