"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ForgeHubApi, setToken } from "@/lib/api";
import { Button, Card, CardBody } from "@/components/ui/primitives";

export default function LoginPage() {
  const router = useRouter();

  const [username, setUsername] = useState("FH_Admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);

    try {
      const result = await ForgeHubApi.login(username, password);
      setToken(result.token);
      router.push("/");
    } catch {
      // Pesannya sengaja tidak membedakan "pengguna tidak ada" dari "sandi
      // salah" — sama seperti di backend, dan karena alasan yang sama.
      setError("Nama pengguna atau kata sandi salah.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas p-6">
      <Card className="w-full max-w-sm">
        <CardBody>
          <div className="mb-6 flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-info text-sm font-bold text-white">
              FH
            </div>
            <div>
              <p className="text-base font-semibold">ForgeHub</p>
              <p className="text-xs text-muted">JakForge Orchestrator</p>
            </div>
          </div>

          <form onSubmit={submit} className="space-y-3.5">
            <div>
              <label htmlFor="username" className="mb-1 block text-xs font-medium text-muted">
                Nama pengguna
              </label>
              <input
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
                className="w-full rounded-lg border border-line px-3 py-2 text-sm outline-none focus:border-info"
              />
            </div>

            <div>
              <label htmlFor="password" className="mb-1 block text-xs font-medium text-muted">
                Kata sandi
              </label>
              <input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                className="w-full rounded-lg border border-line px-3 py-2 text-sm outline-none focus:border-info"
              />
            </div>

            {error ? <p className="text-xs text-danger">{error}</p> : null}

            <Button type="submit" variant="primary" disabled={busy} className="w-full">
              {busy ? "Memeriksa..." : "Masuk"}
            </Button>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
