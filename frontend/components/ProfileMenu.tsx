"use client";

import { useCallback, useState, type ComponentType, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, KeyRound, LogOut, UserCog } from "lucide-react";
import { ForgeHubApi, errorText, setToken, type Profil } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/primitives";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

/**
 * Menu profil di pojok kanan bilah atas: siapa yang sedang masuk, ubah
 * profil, ubah kata sandi, dan keluar.
 *
 * Keadaan terbuka-tertutupnya milik TopNav, bukan milik menu ini sendiri:
 * hanya satu menu di bilah atas yang boleh terbuka sekaligus, dan yang tahu
 * menu mana yang sedang terbuka hanyalah induknya.
 */
export function ProfileMenu({
  terbuka,
  onAlih,
  onTutup,
}: {
  terbuka: boolean;
  onAlih: () => void;
  onTutup: () => void;
}) {
  const { t } = useT();
  const me = useQuery({ queryKey: ["me"], queryFn: ForgeHubApi.me });
  const [dialog, setDialog] = useState<"profil" | "sandi" | null>(null);

  // Stabil, bukan fungsi baru tiap render. Dialog menjalankan ulang efek
  // fokusnya setiap kali onTutup berganti, dan TopNav dirender ulang tiap
  // sepuluh detik oleh data dasbor — tanpa ini kursor melompat ke isian
  // pertama di tengah orang mengetik.
  const tutupDialog = useCallback(() => setDialog(null), []);

  const nama = me.data?.displayName || me.data?.username || "...";

  function buka(d: "profil" | "sandi") {
    onTutup();
    setDialog(d);
  }

  function keluar() {
    setToken(null);
    window.location.href = "/login";
  }

  return (
    <div className="relative">
      <button
        type="button"
        onClick={onAlih}
        aria-haspopup="menu"
        aria-expanded={terbuka}
        aria-label={t("Menu profil")}
        className="flex items-center gap-2.5 rounded-lg py-1 pl-1 pr-2 transition hover:bg-slate-100"
      >
        <Inisial nama={nama} />
        <span className="hidden text-left leading-tight sm:block">
          <span className="block max-w-[10rem] truncate text-sm font-medium text-ink">{nama}</span>
          <span className="block text-[11px] text-muted">{me.data?.role ?? ""}</span>
        </span>
        <ChevronDown className={cn("hidden h-4 w-4 text-muted transition sm:block", terbuka && "rotate-180")} />
      </button>

      {terbuka ? (
        <div role="menu" className="absolute right-0 z-40 mt-1 w-72 rounded-lg border border-line bg-card py-1 shadow-lg">
          <div className="flex items-center gap-3 border-b border-line px-4 py-3">
            <Inisial nama={nama} besar />
            <div className="min-w-0 leading-tight">
              <p className="truncate text-sm font-semibold text-ink">{nama}</p>
              <p className="mt-0.5 truncate text-xs text-muted">
                @{me.data?.username ?? ""} · {me.data?.role ?? ""}
              </p>
              <p className="mt-0.5 truncate text-xs text-muted">
                {me.data?.email || t("Belum ada surel")}
              </p>
            </div>
          </div>

          <ItemMenu ikon={UserCog} onClick={() => buka("profil")}>
            {t("Ubah profil")}
          </ItemMenu>
          <ItemMenu ikon={KeyRound} onClick={() => buka("sandi")}>
            {t("Ubah kata sandi")}
          </ItemMenu>

          <div className="my-1 border-t border-line" />

          <ItemMenu ikon={LogOut} onClick={keluar} bahaya>
            {t("Keluar")}
          </ItemMenu>
        </div>
      ) : null}

      {/* Dipasang saat dibuka, bukan sekadar disembunyikan: isian mulai dari
          nilai yang tersimpan setiap kali, bukan dari ketikan yang ditinggal
          waktu dialog terakhir ditutup. */}
      {dialog === "profil" && me.data ? <DialogProfil awal={me.data} onTutup={tutupDialog} /> : null}
      {dialog === "sandi" ? <DialogSandi onTutup={tutupDialog} /> : null}
    </div>
  );
}

function ItemMenu({
  ikon: Ikon,
  onClick,
  bahaya,
  children,
}: {
  ikon: ComponentType<{ className?: string }>;
  onClick: () => void;
  bahaya?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onClick}
      className={cn(
        "flex w-full items-center gap-2.5 px-4 py-2 text-left text-sm hover:bg-slate-50",
        bahaya ? "text-danger" : "text-ink",
      )}
    >
      <Ikon className={cn("h-4 w-4", bahaya ? "text-danger" : "text-muted")} />
      {children}
    </button>
  );
}

/**
 * Dua huruf dari nama: huruf pertama dua kata pertama, atau dua huruf pertama
 * kalau namanya satu kata. "ForgeHub Admin" menjadi FA, bukan FO.
 */
function inisialDari(nama: string): string {
  const kata = nama.trim().split(/\s+/).filter(Boolean);
  if (kata.length === 0) return "?";

  const huruf = kata.length > 1 ? kata[0][0] + kata[1][0] : kata[0].slice(0, 2);
  return huruf.toUpperCase();
}

function Inisial({ nama, besar }: { nama: string; besar?: boolean }) {
  return (
    <span
      className={cn(
        "flex shrink-0 items-center justify-center rounded-full bg-brand font-semibold text-white",
        besar ? "h-10 w-10 text-sm" : "h-8 w-8 text-xs",
      )}
    >
      {inisialDari(nama)}
    </span>
  );
}

// ---------------------------------------------------------------------
// Dialog
// ---------------------------------------------------------------------

function DialogProfil({ awal, onTutup }: { awal: Profil; onTutup: () => void }) {
  const { t } = useT();
  const klien = useQueryClient();

  const [nama, setNama] = useState(awal.displayName ?? "");
  const [surel, setSurel] = useState(awal.email ?? "");
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: ForgeHubApi.updateProfile,
    onSuccess: (profil) => {
      // Jawaban server langsung menjadi data "me": bilah atas berganti nama
      // saat itu juga, tanpa menunggu penarikan berikutnya.
      klien.setQueryData(["me"], profil);
      klien.invalidateQueries({ queryKey: ["users"] });
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    setGalat("");
    if (!nama.trim()) return setGalat(t("Nama tampilan wajib diisi."));

    simpan.mutate({ displayName: nama.trim(), email: surel.trim() });
  }

  return (
    <Dialog
      judul={t("Ubah profil")}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" disabled={simpan.isPending} onClick={kirim}>
            {simpan.isPending ? t("Menyimpan...") : t("Simpan")}
          </Button>
        </>
      }
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          kirim();
        }}
      >
        {/* Teks biasa, bukan isian nonaktif: Dialog memfokuskan isian
            PERTAMA, dan isian nonaktif tidak bisa difokus. */}
        <div className="mb-3 rounded-lg bg-canvas px-3 py-2">
          <p className="text-xs font-medium text-muted">{t("Nama pengguna")}</p>
          <p className="text-sm font-medium text-ink">{awal.username}</p>
          <p className="mt-1 text-xs text-muted">
            {t("Nama pengguna dipakai untuk masuk dan hanya bisa diubah Administrator.")}
          </p>
        </div>

        <Isian label={t("Nama tampilan")}>
          <input
            value={nama}
            onChange={(e) => setNama(e.target.value)}
            maxLength={200}
            autoComplete="name"
            className={kelasIsian}
          />
        </Isian>

        <Isian label={t("Surel")} petunjuk={t("Kosongkan untuk menghapus.")}>
          <input
            type="email"
            value={surel}
            onChange={(e) => setSurel(e.target.value)}
            maxLength={160}
            autoComplete="email"
            className={kelasIsian}
          />
        </Isian>

        {galat ? <p className="text-sm text-danger">{galat}</p> : null}

        {/* Supaya Enter di isian mana pun ikut menyimpan. */}
        <button type="submit" className="sr-only" tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
}

function DialogSandi({ onTutup }: { onTutup: () => void }) {
  const { t } = useT();

  const [lama, setLama] = useState("");
  const [baru, setBaru] = useState("");
  const [ulang, setUlang] = useState("");
  const [galat, setGalat] = useState("");
  const [selesai, setSelesai] = useState(false);

  const ganti = useMutation({
    mutationFn: () => ForgeHubApi.changePassword(lama, baru),
    onSuccess: () => {
      // Isian dikosongkan begitu tidak dibutuhkan: kata sandi tidak perlu
      // tinggal di memori halaman lebih lama dari satu permintaan.
      setLama("");
      setBaru("");
      setUlang("");
      setSelesai(true);
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    setGalat("");
    if (!lama || !baru || !ulang) return setGalat(t("Semua isian wajib diisi."));
    if (baru.length < 8) return setGalat(t("Kata sandi baru minimal 8 karakter."));
    if (baru !== ulang) return setGalat(t("Ulangan kata sandi baru tidak sama."));
    if (baru === lama) return setGalat(t("Kata sandi baru harus berbeda dari yang lama."));

    ganti.mutate();
  }

  if (selesai) {
    return (
      <Dialog
        judul={t("Ubah kata sandi")}
        terbuka
        onTutup={onTutup}
        aksi={
          <Button variant="primary" onClick={onTutup}>
            {t("Tutup")}
          </Button>
        }
      >
        <p className="text-sm text-ink">{t("Kata sandi berhasil diganti.")}</p>
      </Dialog>
    );
  }

  return (
    <Dialog
      judul={t("Ubah kata sandi")}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" disabled={ganti.isPending} onClick={kirim}>
            {ganti.isPending ? t("Menyimpan...") : t("Simpan")}
          </Button>
        </>
      }
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          kirim();
        }}
      >
        <Isian label={t("Kata sandi saat ini")}>
          <input
            type="password"
            value={lama}
            onChange={(e) => setLama(e.target.value)}
            autoComplete="current-password"
            className={kelasIsian}
          />
        </Isian>

        <Isian label={t("Kata sandi baru")} petunjuk={t("Minimal 8 karakter.")}>
          <input
            type="password"
            value={baru}
            onChange={(e) => setBaru(e.target.value)}
            autoComplete="new-password"
            className={kelasIsian}
          />
        </Isian>

        <Isian label={t("Ulangi kata sandi baru")}>
          <input
            type="password"
            value={ulang}
            onChange={(e) => setUlang(e.target.value)}
            autoComplete="new-password"
            className={kelasIsian}
          />
        </Isian>

        {galat ? <p className="text-sm text-danger">{galat}</p> : null}

        <button type="submit" className="sr-only" tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
}
