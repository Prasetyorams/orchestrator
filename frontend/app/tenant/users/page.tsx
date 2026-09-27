"use client";

import { useCallback, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Trash2 } from "lucide-react";
import { OpenOrchestratorApi, errorText, type User } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { BilahAlat } from "@/components/HalamanFolder";

export default function Pengguna() {
  const { t } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();

  const [sunting, setSunting] = useState<User | null>(null);
  const [baru, setBaru] = useState(false);
  const [galat, setGalat] = useState("");

  const pengguna = useQuery({ queryKey: ["users"], queryFn: OpenOrchestratorApi.users });
  const peran = useQuery({ queryKey: ["roles"], queryFn: OpenOrchestratorApi.roles });

  const hapus = useMutation({
    mutationFn: OpenOrchestratorApi.deleteUser,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["users"] }),
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
          boleh("users.create") ? (
            <Button variant="primary" onClick={() => setBaru(true)}>
              {t("Tambah pengguna")}
            </Button>
          ) : null
        }
      >
        <p className="text-sm text-muted">
          {t("Pengguna baru langsung ditugaskan ke folder Shared. Atur folder lainnya di Setelan setiap folder.")}
        </p>
      </BilahAlat>

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={pengguna.data ?? []}
          kunci={(u) => u.username}
          onBuka={boleh("users.update") ? setSunting : undefined}
          kosong={pengguna.isLoading ? "Memuat..." : "Belum ada data."}
          kolom={[
            {
              judul: "Nama pengguna",
              sel: (u) => <span className="font-medium">{u.username}</span>,
              urut: (u) => u.username,
            },
            { judul: "Nama", sel: (u) => u.displayName, urut: (u) => u.displayName },
            { judul: "Peran", sel: (u) => <Badge value={u.role.toUpperCase()} label={u.role} />, urut: (u) => u.role },
            {
              judul: "Folder",
              sel: (u) =>
                u.folders?.length ? (
                  <span className="text-muted" title={u.folders.join(", ")}>
                    {u.folders.length > 3 ? `${u.folders.slice(0, 3).join(", ")} +${u.folders.length - 3}` : u.folders.join(", ")}
                  </span>
                ) : (
                  <span className="text-muted">-</span>
                ),
            },
            {
              judul: "Status",
              sel: (u) => (
                <Badge value={u.isActive ? "AVAILABLE" : "STOPPED"} label={t(u.isActive ? "Aktif" : "Nonaktif")} />
              ),
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
                <div className="flex justify-end gap-0.5">
                  {boleh("users.update") ? (
                    <IconButton label={t("Ubah")} onClick={() => setSunting(u)}>
                      <Pencil size={15} />
                    </IconButton>
                  ) : null}
                  {boleh("users.delete") ? (
                    <IconButton
                      label={t("Hapus")}
                      tone="danger"
                      onClick={() => {
                        if (window.confirm(`${t("Yakin menghapus")} "${u.username}"?`)) hapus.mutate(u.username);
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

      {baru || sunting ? (
        <DialogPengguna
          key={sunting?.username ?? "baru"}
          awal={sunting}
          peran={(peran.data ?? []).map((p) => p.name)}
          onTutup={tutup}
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
    mutationFn: OpenOrchestratorApi.saveUser,
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <Dialog
      judul={awal ? `${t("Sunting")} — ${awal.username}` : t("Tambah pengguna")}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button
            variant="primary"
            disabled={simpan.isPending}
            onClick={() => {
              if (!username.trim()) return setGalat(t("Nama pengguna wajib diisi."));
              if (!awal && sandi.length < 6) {
                return setGalat(t("Pengguna baru butuh kata sandi minimal 6 karakter."));
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
        <input value={username} onChange={(e) => setUsername(e.target.value)} disabled={!!awal} className={kelasIsian} />
      </Isian>

      <Isian label={t("Nama")}>
        <input value={namaTampil} onChange={(e) => setNamaTampil(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian label={t("Surel")}>
        <input type="email" value={surel} onChange={(e) => setSurel(e.target.value)} className={kelasIsian} />
      </Isian>

      <Isian
        label={t("Kata sandi")}
        petunjuk={awal ? t("Kosongkan kalau tidak ingin menggantinya.") : t("Minimal 6 karakter.")}
      >
        <input
          type="password"
          value={sandi}
          onChange={(e) => setSandi(e.target.value)}
          autoComplete="new-password"
          className={kelasIsian}
        />
      </Isian>

      <Isian label={t("Peran")} petunjuk={t("Izin setiap peran diatur di tab Peran.")}>
        <select value={namaPeran} onChange={(e) => setNamaPeran(e.target.value)} className={kelasIsian}>
          {/* Peran yang sudah terhapus tetap ditampilkan untuk pengguna yang
              masih memegangnya, supaya pilihannya tidak diam-diam berganti. */}
          {(peran.includes(namaPeran) ? peran : [namaPeran, ...peran]).map((p) => (
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
        {t("Aktif")}
      </label>

      <Galat pesan={galat} className="mt-3" />
    </Dialog>
  );
}
