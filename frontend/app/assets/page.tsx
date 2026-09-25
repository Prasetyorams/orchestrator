"use client";

import { useCallback, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Eye, EyeOff, KeyRound, Trash2 } from "lucide-react";
import { ForgeHubApi, errorText, type Asset } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

const TIPE = ["Text", "Integer", "Bool", "Credential", "Secret"];
const RAHASIA = new Set(["Credential", "Secret"]);

/** Pengganti nilai rahasia di layar. Panjangnya tetap, supaya panjang aslinya pun tidak terbaca. */
const SAMARAN = "••••••••";

export default function AsetHalaman() {
  const { t } = useT();
  const klien = useQueryClient();

  const [sunting, setSunting] = useState<Asset | null>(null);
  const [baru, setBaru] = useState(false);
  const [galat, setGalat] = useState("");
  const [saringTipe, setSaringTipe] = useState("");
  const [terbuka, setTerbuka] = useState<Record<string, string>>({});

  // Alamat lama /robots/credentials meneruskan ke sini dengan ?tipe=Credential.
  // Dibaca sekali saat dipasang, lewat window — useSearchParams menuntut
  // pembungkus Suspense di seluruh halaman hanya untuk satu nilai awal ini.
  useEffect(() => {
    const tipe = new URLSearchParams(window.location.search).get("tipe");
    if (tipe && TIPE.includes(tipe)) setSaringTipe(tipe);
  }, []);

  const aset = useQuery({ queryKey: ["assets"], queryFn: ForgeHubApi.assets });

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteAsset,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["assets"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  /** Isi Secret diambil satu per satu, hanya saat diminta. */
  async function lihat(nama: string) {
    try {
      const r = await ForgeHubApi.assetValue(nama);
      setTerbuka((s) => ({ ...s, [nama]: r.value ?? "" }));
    } catch (e) {
      setGalat(errorText(e));
    }
  }

  function tutupNilai(nama: string) {
    setTerbuka((s) => {
      const sisa = { ...s };
      delete sisa[nama];
      return sisa;
    });
  }

  const tutupDialog = useCallback(() => {
    setBaru(false);
    setSunting(null);
  }, []);

  const data = (aset.data ?? []).filter((a) => !saringTipe || a.type === saringTipe);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-lg font-semibold text-ink">{t("Aset")}</h1>

        <select
          value={saringTipe}
          onChange={(e) => setSaringTipe(e.target.value)}
          className={`${kelasIsian} w-40`}
          aria-label={t("Tipe")}
        >
          <option value="">{t("Semua tipe")}</option>
          {TIPE.map((x) => (
            <option key={x} value={x}>
              {x}
            </option>
          ))}
        </select>

        <Button variant="primary" className="ml-auto" onClick={() => setBaru(true)}>
          {t("Tambah")}
        </Button>
      </div>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}

      <p className="text-sm text-muted">
        {t(
          "Nilai yang dipakai bersama oleh banyak proses. Isi aset Secret dan kata sandi aset Credential tidak pernah tampil di daftar.",
        )}
      </p>

      <Card>
        <DataTable
          data={data}
          kunci={(a) => a.name}
          onBuka={setSunting}
          kolom={[
            { judul: "Nama", sel: (a) => <span className="font-medium">{a.name}</span>, urut: (a) => a.name },
            { judul: "Tipe", sel: (a) => <Badge value={a.type.toUpperCase()} />, urut: (a) => a.type },
            {
              judul: "Nilai",
              sel: (a) => (
                <Nilai
                  aset={a}
                  terbuka={terbuka[a.name]}
                  onLihat={() => lihat(a.name)}
                  onTutup={() => tutupNilai(a.name)}
                />
              ),
            },
            { judul: "Keterangan", sel: (a) => <span className="text-muted">{a.description ?? "-"}</span> },
            {
              judul: "Diubah",
              sel: (a) => <span className="text-muted">{dateTimeOf(a.updatedAt ?? a.createdAt)}</span>,
              urut: (a) => a.updatedAt ?? a.createdAt,
            },
            {
              judul: "",
              sel: (a) => (
                <div className="flex justify-end">
                  <IconButton
                    label={t("Hapus")}
                    tone="danger"
                    onClick={() => {
                      if (window.confirm(`${t("Yakin menghapus")} "${a.name}"?`)) hapus.mutate(a.name);
                    }}
                  >
                    <Trash2 size={16} />
                  </IconButton>
                </div>
              ),
            },
          ]}
        />
      </Card>

      {baru || sunting ? (
        <DialogAset
          key={sunting?.name ?? "baru"}
          awal={sunting}
          tipeAwal={saringTipe || "Text"}
          onTutup={tutupDialog}
          onSelesai={() => {
            // Nilai Secret yang sedang terbuka mungkin baru saja diganti.
            if (sunting) tutupNilai(sunting.name);
            klien.invalidateQueries({ queryKey: ["assets"] });
          }}
        />
      ) : null}
    </div>
  );
}

/**
 * Kolom nilai.
 *
 * - Text, Integer, Bool: nilainya apa adanya.
 * - Credential: nama pengguna, lalu kata sandi yang SELALU tersamar. Kata
 *   sandinya tidak pernah dikirim ke peramban — hanya robot yang memintanya
 *   lewat Get Credential yang menerimanya — jadi yang ada di layar memang
 *   bukan kata sandinya, hanya tanda bahwa kata sandinya ada.
 * - Secret: tersamar, dengan tombol mata untuk membukanya satu per satu dan
 *   menutupnya lagi.
 */
function Nilai({
  aset,
  terbuka,
  onLihat,
  onTutup,
}: {
  aset: Asset;
  terbuka: string | undefined;
  onLihat: () => void;
  onTutup: () => void;
}) {
  const { t } = useT();

  if (aset.type === "Credential") {
    return (
      <span className="inline-flex items-center gap-2 text-xs">
        <KeyRound size={13} className="shrink-0 text-muted" />
        <span className="font-medium text-ink">{aset.username || t("(kosong)")}</span>
        <span className="text-muted">·</span>
        {aset.hasValue ? (
          <code className="font-mono tracking-wider text-muted" aria-label={t("Kata sandi")}>
            {SAMARAN}
          </code>
        ) : (
          <span className="italic text-muted">{t("Tanpa kata sandi")}</span>
        )}
      </span>
    );
  }

  if (aset.type === "Secret") {
    if (!aset.hasValue) return <span className="text-xs italic text-muted">{t("(kosong)")}</span>;

    const dibuka = terbuka !== undefined;

    return (
      <span className="inline-flex items-center gap-1">
        <code className={cn("font-mono text-xs", !dibuka && "tracking-wider text-muted")}>
          {dibuka ? terbuka || t("(kosong)") : SAMARAN}
        </code>
        <IconButton label={dibuka ? t("Sembunyikan") : t("Tampilkan")} onClick={dibuka ? onTutup : onLihat}>
          {dibuka ? <EyeOff size={14} /> : <Eye size={14} />}
        </IconButton>
      </span>
    );
  }

  return <code className="font-mono text-xs">{aset.valueText ?? "-"}</code>;
}

function DialogAset({
  awal,
  tipeAwal,
  onTutup,
  onSelesai,
}: {
  awal: Asset | null;
  tipeAwal: string;
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const [nama, setNama] = useState(awal?.name ?? "");
  const [tipe, setTipe] = useState(awal?.type ?? tipeAwal);
  const [pengguna, setPengguna] = useState(awal?.username ?? "");
  // Rahasia tidak pernah dikirim ke peramban, jadi isiannya selalu mulai
  // kosong — dan kosong saat disimpan berarti "biarkan yang lama".
  const [nilai, setNilai] = useState(awal && !RAHASIA.has(awal.type) ? (awal.valueText ?? "") : "");
  const [ket, setKet] = useState(awal?.description ?? "");
  const [tampak, setTampak] = useState(false);
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: ForgeHubApi.saveAsset,
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  const rahasia = RAHASIA.has(tipe);
  const kredensial = tipe === "Credential";

  // "Biarkan yang lama" hanya berlaku kalau tipenya tidak berganti — sama
  // persis dengan aturan di server (VaultService.tulisAset).
  const bolehKosong = !!awal && awal.type === tipe && rahasia;

  function kirim() {
    setGalat("");
    if (!nama.trim()) return setGalat(t("Nama aset wajib diisi."));

    simpan.mutate({
      name: nama.trim(),
      type: tipe,
      username: kredensial ? pengguna.trim() : undefined,
      value: nilai,
      description: ket,
    });
  }

  return (
    <Dialog
      judul={awal ? `${t("Sunting")} — ${awal.name}` : t("Tambah aset")}
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
        <Isian label={t("Nama")}>
          <input value={nama} onChange={(e) => setNama(e.target.value)} disabled={!!awal} className={kelasIsian} />
        </Isian>

        <Isian label={t("Tipe")}>
          <select
            value={tipe}
            onChange={(e) => {
              const baru = e.target.value;

              // Berpindah antara nilai biasa dan rahasia mengosongkan isiannya:
              // teks lama yang tiba-tiba menjadi kata sandi hampir pasti
              // bukan yang dimaksud.
              if (RAHASIA.has(baru) !== RAHASIA.has(tipe)) setNilai("");
              setTipe(baru);
            }}
            className={kelasIsian}
          >
            {TIPE.map((x) => (
              <option key={x}>{x}</option>
            ))}
          </select>
        </Isian>

        {kredensial ? (
          <Isian label={t("Nama pengguna")}>
            <input
              value={pengguna}
              onChange={(e) => setPengguna(e.target.value)}
              autoComplete="off"
              className={kelasIsian}
            />
          </Isian>
        ) : null}

        <Isian
          label={kredensial ? t("Kata sandi") : t("Nilai")}
          petunjuk={
            bolehKosong
              ? t("Kosongkan kalau tidak ingin menggantinya.")
              : kredensial
                ? t(
                    "Kata sandi aset Credential tidak pernah dikirim ke layar ini — hanya robot yang memintanya lewat activity Get Credential yang menerimanya.",
                  )
                : rahasia
                  ? t("Disandikan sebelum disimpan, dan tidak pernah muncul di daftar.")
                  : undefined
          }
        >
          {rahasia ? (
            <div className="relative">
              <input
                type={tampak ? "text" : "password"}
                value={nilai}
                onChange={(e) => setNilai(e.target.value)}
                placeholder={bolehKosong && awal?.hasValue ? SAMARAN : undefined}
                // new-password: pengelola sandi peramban tidak boleh mengisi
                // kata sandi ForgeHub milik orang yang sedang masuk ke sini.
                autoComplete="new-password"
                className={`${kelasIsian} pr-10`}
              />
              {/* Hanya untuk yang sedang diketik; nilai tersimpan tidak pernah ada di sini. */}
              <button
                type="button"
                onClick={() => setTampak((x) => !x)}
                aria-label={tampak ? t("Sembunyikan kata sandi") : t("Tampilkan kata sandi")}
                title={tampak ? t("Sembunyikan kata sandi") : t("Tampilkan kata sandi")}
                className="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-muted hover:text-ink"
              >
                {tampak ? <EyeOff size={15} /> : <Eye size={15} />}
              </button>
            </div>
          ) : (
            <input value={nilai} onChange={(e) => setNilai(e.target.value)} className={kelasIsian} />
          )}
        </Isian>

        <Isian label={t("Keterangan")}>
          <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
        </Isian>

        {galat ? <p className="text-sm text-danger">{galat}</p> : null}

        {/* Supaya Enter di isian mana pun ikut menyimpan. */}
        <button type="submit" className="sr-only" tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
}
