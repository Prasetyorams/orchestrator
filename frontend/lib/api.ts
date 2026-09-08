import axios, { AxiosError } from "axios";

/**
 * Klien HTTP ke backend ForgeHub.
 *
 * Token disimpan di localStorage, bukan cookie. Konsekuensinya disebut di sini
 * supaya tidak jadi kejutan: pilihan ini kebal CSRF (header tidak ikut terkirim
 * sendiri oleh peramban) tapi terbuka terhadap XSS. Yang menjaga sisi itu
 * adalah React, yang meng-escape seluruh teks yang dirender — selama tidak ada
 * dangerouslySetInnerHTML, tidak ada jalan masuknya.
 *
 * BENTUK DATANYA mengikuti kontrak yang sama dengan yang dipakai Studio dan
 * JakRunner. Itu bukan kebetulan: satu API melayani ketiganya, dan tipe di
 * berkas ini adalah satu-satunya tempat bentuk itu dituliskan di sisi
 * peramban.
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

/** Pesan galat dari server, atau pesan bawaan kalau bentuknya tidak dikenal. */
export function errorText(e: unknown, bawaan = "Terjadi kesalahan."): string {
  const err = e as AxiosError<{ error?: string }>;
  return err?.response?.data?.error ?? err?.message ?? bawaan;
}

// ---------------------------------------------------------------------
// Bentuk data
// ---------------------------------------------------------------------

export type JobState = "PENDING" | "RUNNING" | "SUCCESSFUL" | "FAULTED" | "STOPPING" | "STOPPED";

export type Job = {
  id: string;
  processName: string;
  robotName: string | null;
  machineName: string | null;
  state: JobState;
  source: string;
  priority: string;
  progress: number;
  info: string | null;
  inputJson?: string | null;
  outputJson?: string | null;
  createdAt: string;
  startedAt: string | null;
  endedAt: string | null;
};

export type Robot = {
  id: string;
  name: string;
  machineName: string | null;
  username: string | null;
  type: string;
  environment: string | null;
  description: string | null;
  status: "AVAILABLE" | "BUSY" | "DISCONNECTED";
  cpuPercent: number;
  memoryMb: number;
  lastHeartbeatAt: string | null;
  createdAt: string;
};

export type Machine = {
  id: string;
  name: string;
  type: string;
  licenseKey: string | null;
  description: string | null;
  robotCount: number;
  createdAt: string;
};

export type Environment = {
  id: string;
  name: string;
  description: string | null;
  robotCount: number;
  createdAt: string;
};

export type Process = {
  id: string;
  name: string;
  packageName: string | null;
  packageVersion: string | null;
  environment: string | null;
  description: string | null;
  jobCount: number;
  lastRunAt: string | null;
  createdAt: string;
};

export type Package = {
  id: string;
  name: string;
  version: string;
  description: string | null;
  entryPoint: string | null;
  publishedBy: string | null;
  publishedAt: string;
  sizeBytes: number;
};

export type Trigger = {
  id: string;
  name: string;
  processName: string;
  robotName: string | null;
  type: string;
  cron: string | null;
  intervalMinutes: number;
  priority: string;
  timezone: string;
  runtimeType: string;
  enabled: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  createdAt: string;
};

export type Queue = {
  id: string;
  name: string;
  description: string | null;
  maxRetries: number;
  acceptDuplicates: boolean;
  newCount: number;
  inProgressCount: number;
  successfulCount: number;
  failedCount: number;
  totalCount: number;
  createdAt: string;
};

export type QueueItem = {
  id: string;
  reference: string | null;
  priority: string;
  status: string;
  content: string | null;
  output: string | null;
  exception: string | null;
  retries: number;
  robotName: string | null;
  createdAt: string;
  startedAt: string | null;
  endedAt: string | null;
};

export type Asset = {
  id: string;
  name: string;
  type: string;
  scope: string;
  valueText: string | null;
  hasValue: boolean;
  description: string | null;
  createdAt: string;
  updatedAt: string | null;
};

export type Credential = {
  id: string;
  name: string;
  username: string | null;
  description: string | null;
  createdAt: string;
};

export type Bucket = {
  id: string;
  name: string;
  description: string | null;
  fileCount: number;
  totalBytes: number;
  createdAt: string;
};

export type BucketFile = {
  id: string;
  fileName: string;
  contentType: string | null;
  sizeBytes: number;
  uploadedBy: string | null;
  uploadedAt: string;
};

export type LogLine = {
  id: number;
  level: string;
  message: string;
  robotName: string | null;
  machineName: string | null;
  processName: string | null;
  jobId: string | null;
  loggedAt: string;
};

export type Alert = {
  id: number;
  severity: string;
  title: string;
  message: string | null;
  source: string | null;
  isRead: boolean;
  createdAt: string;
};

export type User = {
  id: string;
  username: string;
  displayName: string;
  email: string | null;
  role: string;
  isActive: boolean;
  createdAt: string;
  lastLoginAt: string | null;
};

export type Role = {
  id: string;
  name: string;
  description: string | null;
  permissions: string | null;
  userCount: number;
};

export type Tenant = {
  id: string;
  name: string;
  displayName: string;
  userCount: number;
  robotCount: number;
  createdAt: string;
};

export type License = {
  id: string;
  product: string;
  total: number;
  used: number;
  expiresAt: string | null;
};

export type Settings = {
  tenant: string;
  serverTime: string;
  displayTimezone: string;
  robotOfflineAfterSeconds: number;
  tokenLifetimeHours: number;
  database: string;
  counts: { users: number; robots: number; processes: number; jobs: number; logs: number };
};

export type Dashboard = {
  robots: { total: number; available: number; busy: number; disconnected: number };
  jobs: {
    running: number;
    pending: number;
    successfulToday: number;
    faultedToday: number;
    totalToday: number;
  };
  queues: { total: number; newItems: number; inProgress: number; failed: number };
  library: {
    processes: number;
    packages: number;
    assets: number;
    machines: number;
    triggers: number;
  };
  successRate: number;
  unreadAlerts: number;
  jobsInProgress: Job[];
  activeRobots: (Robot & { runningJobs: number })[];
  upcomingTriggers: Trigger[];
  recentAlerts: Alert[];
  queueSummary: {
    name: string;
    newCount: number;
    inProgressCount: number;
    successfulCount: number;
    failedCount: number;
  }[];
  serverTime: string;
};

export type HistoryDay = { day: string; successful: number; faulted: number };

export type SearchHit = { kind: string; label: string; detail: string; page: string };

export type LoginResult = {
  token: string;
  expiresInMinutes: number;
  userId: string;
  username: string;
  displayName: string;
  role: string;
  tenantId: string;
  tenantName: string;
};

// ---------------------------------------------------------------------
// Panggilan
// ---------------------------------------------------------------------

const get = <T,>(url: string, params?: Record<string, unknown>) =>
  api.get<T>(url, { params }).then((r) => r.data);

const post = <T,>(url: string, body?: unknown) =>
  api.post<T>(url, body ?? {}).then((r) => r.data);

const del = <T,>(url: string) => api.delete<T>(url).then((r) => r.data);

/** Nama dalam jalur bisa berisi spasi dan garis miring; selalu disandikan. */
const seg = (s: string) => encodeURIComponent(s);

export const ForgeHubApi = {
  login: (username: string, password: string) =>
    post<LoginResult>("/api/auth/login", { username, password }),

  me: () => get<User & { tenantName: string }>("/api/auth/me"),

  changePassword: (currentPassword: string, newPassword: string) =>
    post<{ status: string }>("/api/auth/password", { currentPassword, newPassword }),

  // --- dasbor ---
  dashboard: () => get<Dashboard>("/api/dashboard"),
  history: () => get<HistoryDay[]>("/api/dashboard/history"),
  search: (q: string) => get<SearchHit[]>("/api/search", { q }),

  // --- pekerjaan ---
  jobs: (params?: { state?: string; process?: string; limit?: number }) =>
    get<Job[]>("/api/jobs", params),
  job: (id: string) => get<Job>(`/api/jobs/${seg(id)}`),
  startJob: (body: {
    processName: string;
    robotName?: string;
    priority?: string;
    inputJson?: string;
    source?: string;
  }) => post<{ ok: boolean; id: string }>("/api/jobs", body),
  stopJob: (id: string) => post<{ ok: boolean }>(`/api/jobs/${seg(id)}/stop`),
  deleteJob: (id: string) => del<{ ok: boolean }>(`/api/jobs/${seg(id)}`),

  // --- robot dan mesin ---
  robots: () => get<Robot[]>("/api/robots"),
  saveRobot: (body: Partial<Robot> & { name: string }) => post<{ ok: boolean }>("/api/robots", body),
  deleteRobot: (name: string) => del<{ ok: boolean }>(`/api/robots/${seg(name)}`),

  machines: () => get<Machine[]>("/api/machines"),
  saveMachine: (body: { name: string; type?: string; description?: string }) =>
    post<{ ok: boolean }>("/api/machines", body),
  deleteMachine: (name: string) => del<{ ok: boolean }>(`/api/machines/${seg(name)}`),

  environments: () => get<Environment[]>("/api/environments"),
  saveEnvironment: (body: { name: string; description?: string }) =>
    post<{ ok: boolean }>("/api/environments", body),
  deleteEnvironment: (name: string) => del<{ ok: boolean }>(`/api/environments/${seg(name)}`),

  // --- proses dan paket ---
  processes: () => get<Process[]>("/api/processes"),
  saveProcess: (body: { name: string; description?: string; environment?: string }) =>
    post<{ ok: boolean }>("/api/processes", body),
  deleteProcess: (name: string) => del<{ ok: boolean }>(`/api/processes/${seg(name)}`),

  packages: () => get<Package[]>("/api/packages"),
  deletePackage: (name: string, version: string) =>
    del<{ ok: boolean }>(`/api/packages/${seg(name)}/${seg(version)}`),
  packageUrl: (name: string, version: string) =>
    `${api.defaults.baseURL}/api/packages/${seg(name)}/${seg(version)}/content`,

  // --- pemicu ---
  triggers: () => get<Trigger[]>("/api/triggers"),
  saveTrigger: (body: {
    name: string;
    processName: string;
    robotName?: string;
    cron?: string;
    intervalMinutes?: number;
    timezone?: string;
    priority?: string;
    enabled?: boolean;
  }) => post<{ ok: boolean; nextRunAt: string }>("/api/triggers", body),
  toggleTrigger: (name: string) =>
    post<{ ok: boolean; enabled: boolean }>(`/api/triggers/${seg(name)}/toggle`),
  deleteTrigger: (name: string) => del<{ ok: boolean }>(`/api/triggers/${seg(name)}`),

  // --- antrean ---
  queues: () => get<Queue[]>("/api/queues"),
  saveQueue: (body: { name: string; description?: string; maxRetries?: number }) =>
    post<{ ok: boolean }>("/api/queues", body),
  deleteQueue: (name: string) => del<{ ok: boolean }>(`/api/queues/${seg(name)}`),
  queueItems: (name: string, params?: { status?: string; limit?: number }) =>
    get<QueueItem[]>(`/api/queues/${seg(name)}/items`, params),
  deleteQueueItem: (id: string) => del<{ ok: boolean }>(`/api/queues/items/${seg(id)}`),

  // --- aset dan kredensial ---
  assets: () => get<Asset[]>("/api/assets"),
  saveAsset: (body: { name: string; type?: string; value?: string; description?: string }) =>
    post<{ ok: boolean; created: boolean }>("/api/assets", body),
  assetValue: (name: string) =>
    get<{ name: string; type: string; value: string | null }>(`/api/assets/${seg(name)}/value`),
  deleteAsset: (name: string) => del<{ ok: boolean }>(`/api/assets/${seg(name)}`),

  credentials: () => get<Credential[]>("/api/credentials"),
  saveCredential: (body: {
    name: string;
    username?: string;
    password?: string;
    description?: string;
  }) => post<{ ok: boolean }>("/api/credentials", body),
  deleteCredential: (name: string) => del<{ ok: boolean }>(`/api/credentials/${seg(name)}`),

  // --- gudang berkas ---
  buckets: () => get<Bucket[]>("/api/buckets"),
  saveBucket: (body: { name: string; description?: string }) =>
    post<{ ok: boolean }>("/api/buckets", body),
  deleteBucket: (name: string) => del<{ ok: boolean }>(`/api/buckets/${seg(name)}`),
  bucketFiles: (name: string) => get<BucketFile[]>(`/api/buckets/${seg(name)}/files`),
  uploadBucketFile: (name: string, body: { fileName: string; contentBase64: string; contentType?: string }) =>
    post<{ ok: boolean; fileName: string; sizeBytes: number }>(`/api/buckets/${seg(name)}/files`, body),
  deleteBucketFile: (name: string, id: string) =>
    del<{ ok: boolean }>(`/api/buckets/${seg(name)}/files/${seg(id)}`),
  bucketFileUrl: (name: string, id: string) =>
    `${api.defaults.baseURL}/api/buckets/${seg(name)}/files/${seg(id)}/content`,

  // --- catatan dan peringatan ---
  logs: (params?: { level?: string; robot?: string; process?: string; jobId?: string; limit?: number }) =>
    get<LogLine[]>("/api/logs", params),
  clearLogs: (olderThanDays: number) =>
    api.delete<{ ok: boolean; deleted: number }>("/api/logs", { params: { olderThanDays } })
      .then((r) => r.data),

  alerts: (params?: { unread?: string; limit?: number }) => get<Alert[]>("/api/alerts", params),
  readAlert: (id: number) => post<{ ok: boolean }>(`/api/alerts/${id}/read`),
  readAllAlerts: () => post<{ ok: boolean; changed: number }>("/api/alerts/read-all"),

  // --- pengelolaan ---
  users: () => get<User[]>("/api/users"),
  saveUser: (body: {
    username: string;
    password?: string;
    displayName?: string;
    email?: string;
    role?: string;
    isActive?: boolean;
  }) => post<{ ok: boolean; created: boolean }>("/api/users", body),
  deleteUser: (username: string) => del<{ ok: boolean }>(`/api/users/${seg(username)}`),

  roles: () => get<Role[]>("/api/roles"),
  tenants: () => get<Tenant[]>("/api/tenants"),
  licensing: () => get<License[]>("/api/licensing"),
  settings: () => get<Settings>("/api/settings"),
};

/**
 * Unduh berkas yang butuh header Authorization.
 *
 * Tag <a href> biasa TIDAK membawa header apa pun, jadi tautan langsung ke
 * endpoint isi paket akan selalu menerima 401. Berkasnya diambil lewat axios
 * yang membawa tokennya, lalu diserahkan ke peramban sebagai blob.
 */
export async function unduh(url: string, namaBerkas: string) {
  const r = await api.get(url, { responseType: "blob", baseURL: "" });

  const objectUrl = window.URL.createObjectURL(r.data as Blob);
  const a = document.createElement("a");

  a.href = objectUrl;
  a.download = namaBerkas;
  document.body.appendChild(a);
  a.click();

  // Dibersihkan setelah klik: URL objek menahan blob-nya di memori sampai
  // dilepas, dan halaman yang lama terbuka bisa menumpuk puluhan megabita.
  a.remove();
  window.URL.revokeObjectURL(objectUrl);
}
