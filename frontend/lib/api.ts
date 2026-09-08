import axios, { AxiosError } from "axios";

/**
 * Klien HTTP ke backend ForgeHub.
 *
 * Token disimpan di localStorage, bukan cookie. Konsekuensinya disebut di
 * sini supaya tidak jadi kejutan: pilihan ini kebal CSRF (header tidak ikut
 * terkirim sendiri oleh peramban) tapi terbuka terhadap XSS. Yang menjaga
 * sisi itu adalah React, yang meng-escape seluruh teks yang dirender —
 * selama tidak ada dangerouslySetInnerHTML, tidak ada jalan masuknya.
 */
const TOKEN_KEY = "forgehub.token";

export const api = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080",
  headers: { "Content-Type": "application/json" },
});

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null) {
  if (typeof window === "undefined") return;
  if (token) window.localStorage.setItem(TOKEN_KEY, token);
  else window.localStorage.removeItem(TOKEN_KEY);
}

api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    // Token kedaluwarsa: buang dan kembalikan ke layar masuk. Membiarkannya
    // hanya menghasilkan rentetan 401 di setiap panel yang menarik data.
    if (error.response?.status === 401 && typeof window !== "undefined") {
      setToken(null);
      if (window.location.pathname !== "/login") window.location.href = "/login";
    }
    return Promise.reject(error);
  },
);

// ---------------------------------------------------------------------
// Tipe balasan
// ---------------------------------------------------------------------

export type Summary = {
  totalRobots: number;
  availableRobots: number;
  runningJobs: number;
  pendingJobs: number;
  faultedJobs: number;
  totalAssets: number;
  totalQueues: number;
  totalProcesses: number;
  alerts: number;
};

export type Job = {
  id: string;
  jobName: string;
  status: "PENDING" | "RUNNING" | "SUCCESSFUL" | "FAULTED" | "STOPPED";
  robotName: string;
  machineName: string;
  startedAt: string | null;
  finishedAt: string | null;
  duration: string;
  errorMessage: string | null;
};

export type Robot = {
  id: string;
  name: string;
  robotType: string;
  status: string;
  cpuPercent: number;
  memoryMb: number;
  machineName: string | null;
  environmentName: string | null;
  lastHeartbeat: string | null;
};

export type LogLine = {
  id: number;
  level: string;
  message: string;
  robotName: string | null;
  machineName: string | null;
  processName: string | null;
  loggedAt: string;
};

export type QueueView = {
  id: string;
  name: string;
  description: string | null;
  newCount: number;
  inProgressCount: number;
  successCount: number;
  failedCount: number;
};

export type AssetView = {
  id: string;
  name: string;
  assetType: string;
  value: string | null;
  description: string | null;
};

export type ProcessView = {
  id: string;
  name: string;
  description: string | null;
  entryPoint: string;
  packageName: string | null;
  packageVersion: string | null;
};

export type TriggerView = {
  id: string;
  name: string;
  cronExpression: string | null;
  enabled: boolean;
  nextRunAt: string | null;
};

// ---------------------------------------------------------------------
// Panggilan
// ---------------------------------------------------------------------

export const ForgeHubApi = {
  login: (username: string, password: string) =>
    api.post<{ token: string; username: string; fullName: string; role: string }>(
      "/api/auth/login",
      { username, password },
    ).then((r) => r.data),

  summary: () => api.get<Summary>("/api/dashboard/summary").then((r) => r.data),

  jobs: (status?: string) =>
    api.get<Job[]>("/api/jobs", { params: status ? { status } : undefined }).then((r) => r.data),

  robots: () => api.get<Robot[]>("/api/robots").then((r) => r.data),

  processes: () => api.get<ProcessView[]>("/api/processes").then((r) => r.data),

  queues: () => api.get<QueueView[]>("/api/queues").then((r) => r.data),

  assets: () => api.get<AssetView[]>("/api/assets").then((r) => r.data),

  triggers: () => api.get<TriggerView[]>("/api/jobs/triggers").then((r) => r.data),

  logs: (afterId?: number) =>
    api.get<LogLine[]>("/api/logs", { params: afterId ? { afterId } : undefined }).then((r) => r.data),
};
