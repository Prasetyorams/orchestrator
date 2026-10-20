"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Building2, Clock } from "lucide-react";
import { OpenOrchestratorApi, errorText, jalurKembaliAman, setToken } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { mulaiSesiBaru } from "@/lib/sesi";
import { Logo } from "@/components/Logo";
import { Button, Card, CardBody } from "@/components/ui/primitives";

/** Cara masuk terakhir di peramban ini: "lokal" atau "ad". Bukan rahasia. */
const KUNCI_CARA = "openorchestrator.masuk.cara";

export default function LoginPage() {
  const router = useRouter();
  const { t } = useT();

  const [username, setUsername] = useState("OO_Admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // ?alasan=idle: dikeluarkan karena menganggur (V18) — dibaca sesudah dipasang, seperti ?next=.
  const [menganggur, setMenganggur] = useState(false);

  // Masuk Windows AD (V19): tombolnya hanya ada kalau server mengaktifkannya.
  const metode = useQuery({ queryKey: ["auth-methods"], queryFn: OpenOrchestratorApi.authMethods, retry: 1 });
  const adTersedia = !!metode.data?.ad.enabled;
  const labelAd = metode.data?.ad.label ?? "Windows AD";
  const [cara, setCara] = useState<"lokal" | "ad">("lokal");

  useEffect(() => {
    setMenganggur(new URLSearchParams(window.location.search).get("alasan") === "idle");
  }, []);

  // Cara masuk terakhir di peramban ini — kenyamanan saja; server tetap yang memeriksa.
  useEffect(() => {
    if (!adTersedia) return;
    try {
      if (window.localStorage.getItem(KUNCI_CARA) === "ad") gantiCara("ad");
    } catch {
      /* penyimpanan dilarang */
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adTersedia]);

  function gantiCara(baru: "lokal" | "ad") {
    setCara(baru);
    setError(null);
    setPassword("");
    setUsername(baru === "ad" ? "" : "OO_Admin");
    try {
      window.localStorage.setItem(KUNCI_CARA, baru);
    } catch {
      /* penyimpanan dilarang */
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);

    try {
      const result = cara === "ad"
        ? await OpenOrchestratorApi.loginAd(username, password)
        : await OpenOrchestratorApi.login(username, password);
      setToken(result.token);
      mulaiSesiBaru();
      // Kembali ke halaman yang tadi meminta masuk — mis. persetujuan Open
      // Assistant — bukan selalu ke Beranda. Dibaca lewat window, sama seperti
      // halaman lain di dasbor ini.
      router.push(jalurKembaliAman(new URLSearchParams(window.location.search).get("next")));
    } catch (e) {
      // Akun lokal: pesannya sengaja tidak membedakan "pengguna tidak ada" dari
      // "sandi salah" — sama seperti di backend, dan karena alasan yang sama.
      // Windows AD: pesan server sudah aman (sandi salah tetap satu pesan);
      // yang lebih rinci — belum didaftarkan, nonaktif, AD tak terjangkau —
      // hanya muncul SESUDAH sandi AD-nya terbukti benar, atau soal jaringan.
      setError(cara === "ad" ? errorText(e) : t("Nama pengguna atau kata sandi salah."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas p-6">
      <Card className="w-full max-w-sm">
        <CardBody>
          <div className="mb-6 flex items-center gap-2.5">
            <Logo size={36} className="shrink-0" />
            <p className="text-lg font-semibold">Open Orchestrator</p>
          </div>

          {menganggur ? (
            <div role="status" className="mb-4 flex items-start gap-2 rounded-lg border border-line bg-slate-50 px-3 py-2 text-xs text-ink">
              <Clock size={15} className="mt-0.5 shrink-0 text-warn" />
              <span>{t("Sesi Anda berakhir karena tidak ada aktivitas. Silakan masuk lagi.")}</span>
            </div>
          ) : null}

          {cara === "ad" ? (
            <div className="mb-4">
              <p className="flex items-center gap-2 text-sm font-semibold text-ink">
                <Building2 size={16} className="text-brand" />
                {t("Masuk dengan {0}", labelAd)}
              </p>
              <p className="mt-0.5 text-xs text-muted">{t("Pakai nama pengguna dan kata sandi Windows kantor Anda.")}</p>
            </div>
          ) : null}

          <form onSubmit={submit} className="space-y-3.5">
            <div>
              <label htmlFor="username" className="mb-1 block text-xs font-medium text-muted">
                {t("Nama pengguna")}
              </label>
              <input
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
                placeholder={cara === "ad" ? t("nama.pengguna") : undefined}
                className="w-full rounded-lg border border-line bg-card px-3 py-2 text-sm text-ink outline-none focus:border-brand focus:ring-2 focus:ring-brand/15"
              />
            </div>

            <div>
              <label htmlFor="password" className="mb-1 block text-xs font-medium text-muted">
                {t("Kata sandi")}
              </label>
              <input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                className="w-full rounded-lg border border-line bg-card px-3 py-2 text-sm text-ink outline-none focus:border-brand focus:ring-2 focus:ring-brand/15"
              />
            </div>

            {error ? <p className="text-xs text-danger">{error}</p> : null}

            <Button type="submit" variant="primary" disabled={busy} className="w-full">
              {busy ? t("Memeriksa...") : cara === "ad" ? t("Masuk dengan {0}", labelAd) : t("Masuk")}
            </Button>
          </form>

          {adTersedia ? (
            <>
              <div className="my-4 flex items-center gap-3 text-[11px] font-medium uppercase tracking-wide text-muted">
                <span className="h-px flex-1 bg-line" />
                {t("atau")}
                <span className="h-px flex-1 bg-line" />
              </div>
              <Button onClick={() => gantiCara(cara === "ad" ? "lokal" : "ad")} disabled={busy} className="w-full">
                {cara === "ad" ? (
                  t("Masuk dengan akun lokal")
                ) : (
                  <>
                    <Building2 size={15} className="mr-1.5" />
                    {t("Masuk dengan {0}", labelAd)}
                  </>
                )}
              </Button>
            </>
          ) : null}
        </CardBody>
      </Card>
    </div>
  );
}
