"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, Laptop, ShieldAlert, XCircle } from "lucide-react";
import { OpenOrchestratorApi, alamatMasuk, errorText, getToken, setToken } from "@/lib/api";
import { cocokIzin } from "@/lib/izin";
import { useT } from "@/lib/i18n";
import { Logo } from "@/components/Logo";
import { Button, Card, CardBody, Galat } from "@/components/ui/primitives";

/**
 * Persetujuan "Sambungkan Open Assistant" — masuk lewat dasbor, seperti UiPath
 * Assistant (ROBOT-API.md bagian 5).
 *
 * Open Assistant membuka halaman ini dengan state dan code_challenge (PKCE).
 * Orang yang sudah masuk MENEKAN "Buka Open Assistant" — tidak pernah
 * diteruskan otomatis, supaya situs lain tidak bisa memicu sambungan diam-diam
 * — lalu peramban membuka openassistant://signin?code=…&state=…, dan Open
 * Assistant menukar kodenya sendiri. Tidak ada sandi yang diketik di Open
 * Assistant, dan tidak ada token di alamat.
 */

const KLIEN = "open-assistant";
const ALAMAT_KEMBALI = "openassistant://signin";
const POLA_CHALLENGE = /^[A-Za-z0-9_-]{43}$/;
const POLA_STATE = /^[A-Za-z0-9._~=-]{8,512}$/;

type Permintaan = { state: string; codeChallenge: string; machine: string };

type Tahap =
  | { jenis: "memeriksa" }
  | { jenis: "rusak" }
  | { jenis: "menunggu" }
  | { jenis: "dibuka"; tautan: string }
  | { jenis: "dibatalkan" };

/**
 * Serahkan tautan openassistant:// ke peramban lewat klik tautan — cara yang
 * sama dengan tombol "Coba buka lagi" — supaya peramban menanyakan izinnya
 * dengan dialog biasa, dan halaman ini tetap terbuka di belakangnya.
 */
function bukaAplikasi(tautan: string) {
  const a = document.createElement("a");
  a.href = tautan;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  a.remove();
}

/** Parameter dari Open Assistant, diperiksa sama ketatnya dengan server. Null kalau rusak. */
function bacaPermintaan(): Permintaan | null {
  const p = new URLSearchParams(window.location.search);
  const state = p.get("state") ?? "";
  const codeChallenge = p.get("code_challenge") ?? "";

  const sah =
    p.get("client") === KLIEN &&
    p.get("redirect_uri") === ALAMAT_KEMBALI &&
    p.get("code_challenge_method") === "S256" &&
    POLA_CHALLENGE.test(codeChallenge) &&
    POLA_STATE.test(state);

  return sah ? { state, codeChallenge, machine: (p.get("machine") ?? "").trim().slice(0, 160) } : null;
}

export default function SambungkanAssistant() {
  const { t } = useT();
  const [permintaan, setPermintaan] = useState<Permintaan | null>(null);
  const [tahap, setTahap] = useState<Tahap>({ jenis: "memeriksa" });
  const [galat, setGalat] = useState("");
  const [mengirim, setMengirim] = useState(false);

  // Dibaca sekali lewat window, seperti halaman lain di dasbor ini. Belum
  // masuk: ke layar masuk, yang mengembalikan ke sini dengan parameter yang
  // sama.
  useEffect(() => {
    const p = bacaPermintaan();

    if (!p) return setTahap({ jenis: "rusak" });
    if (!getToken()) {
      window.location.replace(alamatMasuk());
      return;
    }

    setPermintaan(p);
    setTahap({ jenis: "menunggu" });
  }, []);

  // Hanya sesudah parameternya sah dan ada token: tautan rusak tidak perlu
  // mengirim orang ke layar masuk lebih dulu.
  const me = useQuery({
    queryKey: ["me"],
    queryFn: OpenOrchestratorApi.me,
    enabled: tahap.jenis === "menunggu",
  });
  const siap = me.isSuccess;

  async function buka() {
    if (!permintaan || mengirim) return;

    setGalat("");
    setMengirim(true);

    try {
      const hasil = await OpenOrchestratorApi.assistantCode({
        client: KLIEN,
        redirectUri: ALAMAT_KEMBALI,
        codeChallenge: permintaan.codeChallenge,
        codeChallengeMethod: "S256",
        state: permintaan.state,
        machine: permintaan.machine || null,
      });

      setTahap({ jenis: "dibuka", tautan: hasil.redirectUrl });
      bukaAplikasi(hasil.redirectUrl);
    } catch (e) {
      setGalat(errorText(e));
    } finally {
      setMengirim(false);
    }
  }

  function batal() {
    if (!permintaan) return;

    setTahap({ jenis: "dibatalkan" });
    bukaAplikasi(`${ALAMAT_KEMBALI}?error=access_denied&state=${encodeURIComponent(permintaan.state)}`);
  }

  function gantiAkun() {
    setToken(null);
    window.location.replace(alamatMasuk());
  }

  const nama = me.data?.displayName || me.data?.username || "…";
  const mesin = permintaan?.machine || t("komputer ini");
  // Server lama tanpa daftar izin: dianggap boleh, server tetap yang menjaga.
  const bolehRobot = !me.data?.permissions || cocokIzin(me.data.permissions, "robots.update");

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas p-4 sm:p-6">
      <Card className="w-full max-w-md">
        <CardBody>
          <div className="mb-5 flex items-center gap-2.5">
            <Logo size={32} className="shrink-0" />
            <p className="text-base font-semibold text-ink">Open Orchestrator</p>
          </div>

          {tahap.jenis === "memeriksa" ? <p className="text-sm text-muted">{t("Memuat...")}</p> : null}

          {tahap.jenis === "rusak" ? (
            <Pesan ikon={<XCircle className="h-6 w-6 text-danger" />} judul={t("Tautan sambungan tidak sah")}>
              {t("Tautan ini tidak lengkap atau sudah diubah. Mulai lagi dari tombol Masuk lewat dasbor di Open Assistant.")}
            </Pesan>
          ) : null}

          {tahap.jenis === "menunggu" ? (
            <div>
              <div className="mb-4 flex items-start gap-3">
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-brandSoft text-brand">
                  <Laptop className="h-5 w-5" />
                </span>
                <div className="min-w-0">
                  <h1 className="text-lg font-semibold text-ink">{t("Sambungkan Open Assistant?")}</h1>
                  <p className="mt-1 text-sm text-muted">
                    {t("Open Assistant di {0} akan tersambung ke Open Orchestrator sebagai {1}.", mesin, nama)}
                  </p>
                </div>
              </div>

              <ul className="mb-4 space-y-1.5 rounded-lg border border-line bg-slate-50 px-4 py-3 text-sm text-ink">
                <li>{t("Robot attended Anda di komputer itu boleh mengambil dan menjalankan pekerjaan.")}</li>
                <li>{t("Open Assistant menampilkan proses dan jadwal yang boleh Anda lihat.")}</li>
                <li>{t("Sambungan bisa dicabut kapan saja dari menu profil › Open Assistant tersambung.")}</li>
              </ul>

              {siap && !bolehRobot ? (
                <div className="mb-4 flex gap-2 rounded-lg bg-amber-50 px-4 py-3 text-sm text-warn">
                  <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
                  <span>
                    {t("Peran Anda tidak boleh menjalankan robot (izin robots.update), jadi Open Assistant tidak bisa disambungkan atas nama Anda. Minta pengelola penyewa mengubah peran Anda.")}
                  </span>
                </div>
              ) : null}

              <Galat pesan={galat} className="mb-4" />

              <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                <Button onClick={batal}>{t("Batal")}</Button>
                <Button variant="primary" onClick={buka} disabled={mengirim || !me.isSuccess || (siap && !bolehRobot)}>
                  {mengirim ? t("Menyambungkan...") : t("Buka Open Assistant")}
                </Button>
              </div>

              <p className="mt-4 text-xs text-muted">
                {t("Bukan {0}?", nama)}{" "}
                <button type="button" onClick={gantiAkun} className="font-medium text-brand hover:underline">
                  {t("Masuk dengan akun lain")}
                </button>
              </p>
            </div>
          ) : null}

          {tahap.jenis === "dibuka" ? (
            <Pesan ikon={<CheckCircle2 className="h-6 w-6 text-ok" />} judul={t("Membuka Open Assistant…")}>
              <p>
                {t("Kalau peramban bertanya, pilih Buka. Sesudah Open Assistant tersambung, tab ini boleh ditutup.")}
              </p>
              <p className="mt-3">
                {t("Open Assistant tidak terbuka?")}{" "}
                <a href={tahap.tautan} className="font-medium text-brand hover:underline">
                  {t("Coba buka lagi")}
                </a>
                {" · "}
                {t("tautan ini berlaku satu menit.")}
              </p>
            </Pesan>
          ) : null}

          {tahap.jenis === "dibatalkan" ? (
            <Pesan ikon={<XCircle className="h-6 w-6 text-muted" />} judul={t("Sambungan dibatalkan")}>
              {t("Open Assistant tidak disambungkan. Tab ini boleh ditutup.")}
            </Pesan>
          ) : null}
        </CardBody>
      </Card>
    </div>
  );
}

function Pesan({ ikon, judul, children }: { ikon: React.ReactNode; judul: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-3">
      <span className="mt-0.5 shrink-0">{ikon}</span>
      <div className="min-w-0">
        <h1 className="text-lg font-semibold text-ink">{judul}</h1>
        <div className="mt-1 text-sm text-muted">{children}</div>
      </div>
    </div>
  );
}
