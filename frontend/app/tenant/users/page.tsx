"use client";

import { useCallback, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Search, Trash2 } from "lucide-react";
import { OpenOrchestratorApi, errorText, type AdLookup, type User } from "@/lib/api";
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
              judul: "Metode masuk",
              sel: (u) =>
                u.authProvider === "AD" ? (
                  <span title={u.adObjectGuid ? `objectGUID ${u.adObjectGuid}` : undefined}>
                    <span className="font-medium">{t("Windows AD")}</span>
                    <span className="text-muted"> · {u.adUsername}</span>
                    {!u.adObjectGuid ? <span className="ml-1 text-xs text-warn">({t("belum tertaut")})</span> : null}
                  </span>
                ) : (
                  <span className="text-muted">{t("Akun lokal")}</span>
                ),
              urut: (u) => u.authProvider ?? "LOCAL",
            },
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

  // Apakah masuk Windows AD aktif di server, dan apakah akun bisa dicari (akun layanan).
  const ad = useQuery({ queryKey: ["ad-lookup"], queryFn: () => OpenOrchestratorApi.adLookup() });
  const adAktif = !!ad.data?.enabled;
  const labelAd = ad.data?.label ?? "Windows AD";

  const [metode, setMetode] = useState<"LOCAL" | "AD">(awal?.authProvider ?? "LOCAL");
  const [namaAd, setNamaAd] = useState(awal?.adUsername ?? "");
  const [hasilCari, setHasilCari] = useState<AdLookup | null>(null);
  const [tertaut, setTertaut] = useState(!!awal?.adObjectGuid);
  const keLokal = awal?.authProvider === "AD" && metode === "LOCAL";

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

  const cari = useMutation({
    mutationFn: () => OpenOrchestratorApi.adLookup(namaAd.trim() || username.trim()),
    onSuccess: (r) => {
      setGalat("");
      setHasilCari(r);
      // Nama dan surel dari direktori, kalau belum diisi.
      if (r.found) {
        if (!namaTampil && r.displayName) setNamaTampil(r.displayName);
        if (!surel && r.email) setSurel(r.email);
        if (r.samAccountName) setNamaAd(r.samAccountName);
      }
    },
    onError: (e) => setGalat(errorText(e)),
  });

  const lepas = useMutation({
    mutationFn: () => OpenOrchestratorApi.unlinkAd(awal!.username),
    onSuccess: () => {
      setTertaut(false);
      onSelesai();
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
              if (metode === "LOCAL" && (!awal || keLokal) && sandi.length < 6) {
                return setGalat(keLokal
                  ? t("Akun yang dikembalikan ke akun lokal butuh kata sandi minimal 6 karakter.")
                  : t("Pengguna baru butuh kata sandi minimal 6 karakter."));
              }

              // Kata sandi kosong TIDAK dikirim. Server memakai syarat "hanya
              // kalau dikirim", jadi menyunting surel tidak akan mengosongkan
              // sandinya — dan yang bersangkutan tidak gagal masuk besok pagi.
              // Pengguna Windows AD tidak pernah punya sandi di sini.
              simpan.mutate({
                username: username.trim(),
                displayName: namaTampil || undefined,
                email: surel || undefined,
                role: namaPeran,
                isActive: aktif,
                authProvider: metode,
                ...(metode === "AD" ? { adUsername: namaAd.trim() || username.trim() } : {}),
                ...(metode === "LOCAL" && sandi ? { password: sandi } : {}),
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

      {adAktif || awal?.authProvider === "AD" ? (
        <Isian label={t("Metode masuk")}>
          <select value={metode} onChange={(e) => setMetode(e.target.value as "LOCAL" | "AD")} className={kelasIsian}>
            <option value="LOCAL">{t("Akun lokal")}</option>
            <option value="AD" disabled={!adAktif && awal?.authProvider !== "AD"}>
              {labelAd}
            </option>
          </select>
        </Isian>
      ) : null}

      {metode === "AD" ? (
        <div className="mb-3 rounded-lg border border-line bg-slate-50 p-3">
          <Isian
            label={t("Nama pengguna Windows AD")}
            petunjuk={ad.data?.lookupAvailable
              ? t("Cari dulu untuk memastikan akunnya ada; identitasnya (objectGUID) disimpan saat disimpan.")
              : t("Tanpa akun layanan di server: identitas akun (objectGUID) ditautkan saat orang ini pertama kali masuk dengan akun Windows-nya.")}
          >
            <div className="flex gap-2">
              <input
                value={namaAd}
                onChange={(e) => {
                  setNamaAd(e.target.value);
                  setHasilCari(null);
                }}
                placeholder={username || t("nama.pengguna")}
                autoComplete="off"
                className={kelasIsian}
              />
              {ad.data?.lookupAvailable ? (
                <Button onClick={() => cari.mutate()} disabled={cari.isPending || !(namaAd.trim() || username.trim())}>
                  <Search size={14} className="mr-1.5" />
                  {t("Cari di AD")}
                </Button>
              ) : null}
            </div>
          </Isian>

          {hasilCari?.found === false ? (
            <p className="text-xs text-danger">{t("Akun tidak ditemukan di Windows AD.")}</p>
          ) : null}
          {hasilCari?.found ? (
            <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs">
              <dt className="text-muted">{t("Nama")}</dt>
              <dd className="text-ink">{hasilCari.displayName ?? "-"}</dd>
              <dt className="text-muted">{t("Surel")}</dt>
              <dd className="text-ink">{hasilCari.email ?? "-"}</dd>
              <dt className="text-muted">{t("Status di AD")}</dt>
              <dd className={hasilCari.accountEnabled ? "text-ok" : "text-danger"}>
                {hasilCari.accountEnabled ? t("Aktif") : t("Dinonaktifkan")}
              </dd>
              {hasilCari.linkedTo && hasilCari.linkedTo !== awal?.username ? (
                <dd className="col-span-2 text-danger">{t("Sudah dipakai pengguna {0}.", hasilCari.linkedTo)}</dd>
              ) : null}
            </dl>
          ) : null}

          {awal?.authProvider === "AD" ? (
            <div className="mt-2 flex flex-wrap items-center justify-between gap-2 border-t border-line pt-2 text-xs">
              <span className="text-muted">
                {tertaut
                  ? t("Tertaut ke objectGUID {0} sejak {1}.", awal.adObjectGuid ?? "", dateTimeOf(awal.adLinkedAt))
                  : t("Belum tertaut — ditautkan saat masuk berikutnya.")}
              </span>
              {tertaut ? (
                <Button
                  variant="ghost"
                  disabled={lepas.isPending}
                  onClick={() => {
                    if (window.confirm(t("Lepas tautan akun Windows AD dari {0}? Sesinya berakhir; akun AD dengan nama itu ditautkan lagi saat masuk berikutnya.", awal.username))) {
                      lepas.mutate();
                    }
                  }}
                >
                  {t("Lepas tautan AD")}
                </Button>
              ) : null}
            </div>
          ) : null}

          <p className="mt-2 text-xs text-muted">{t("Kata sandi Windows tidak pernah disimpan di Open Orchestrator.")}</p>
        </div>
      ) : (
        <Isian
          label={t("Kata sandi")}
          petunjuk={keLokal
            ? t("Wajib: akun ini kembali menjadi akun lokal. Minimal 6 karakter.")
            : awal ? t("Kosongkan kalau tidak ingin menggantinya.") : t("Minimal 6 karakter.")}
        >
          <input
            type="password"
            value={sandi}
            onChange={(e) => setSandi(e.target.value)}
            autoComplete="new-password"
            className={kelasIsian}
          />
        </Isian>
      )}

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

      {awal ? (
        <p className="mt-3 text-xs text-muted">
          {t("Dibuat {0}", dateTimeOf(awal.createdAt))}
          {awal.createdBy ? ` ${t("oleh {0}", awal.createdBy)}` : ""}
          {awal.updatedAt ? ` · ${t("diubah {0}", dateTimeOf(awal.updatedAt))}${awal.updatedBy ? ` ${t("oleh {0}", awal.updatedBy)}` : ""}` : ""}
          {" · "}
          {t("masuk terakhir {0}", dateTimeOf(awal.lastLoginAt))}
        </p>
      ) : null}

      <Galat pesan={galat} className="mt-3" />
    </Dialog>
  );
}
