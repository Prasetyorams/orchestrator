"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { OpenOrchestratorApi, jalurKembaliAman, setToken } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { Logo } from "@/components/Logo";
import { Button, Card, CardBody } from "@/components/ui/primitives";

export default function LoginPage() {
  const router = useRouter();
  const { t } = useT();

  const [username, setUsername] = useState("OO_Admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);

    try {
      const result = await OpenOrchestratorApi.login(username, password);
      setToken(result.token);
      // Kembali ke halaman yang tadi meminta masuk — mis. persetujuan Open
      // Assistant — bukan selalu ke Beranda. Dibaca lewat window, sama seperti
      // halaman lain di dasbor ini.
      router.push(jalurKembaliAman(new URLSearchParams(window.location.search).get("next")));
    } catch {
      // Pesannya sengaja tidak membedakan "pengguna tidak ada" dari "sandi
      // salah" — sama seperti di backend, dan karena alasan yang sama.
      setError(t("Nama pengguna atau kata sandi salah."));
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
              {busy ? t("Memeriksa...") : t("Masuk")}
            </Button>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
