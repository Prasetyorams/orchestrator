import axios, { AxiosError } from "axios";
import { bahasaAktif, terjemahkan, terjemahkanPesan } from "@/lib/bahasa";

/**
 * Klien HTTP ke backend OpenOrchestrator.
 *
 * Token disimpan di localStorage, bukan cookie. Konsekuensinya disebut di sini
 * supaya tidak jadi kejutan: pilihan ini kebal CSRF (header tidak ikut terkirim
 * sendiri oleh peramban) tapi terbuka terhadap XSS. Yang menjaga sisi itu
 * adalah React, yang meng-escape seluruh teks yang dirender — selama tidak ada
 * dangerouslySetInnerHTML, tidak ada jalan masuknya. (Satu-satunya yang ada,
 * di app/layout.tsx, berisi skrip tema yang tetap — tanpa data apa pun.)
 *
 * BENTUK DATANYA mengikuti kontrak yang sama dengan yang dipakai Studio dan
 * JakRunner. Itu bukan kebetulan: satu API melayani ketiganya, dan tipe di
 * berkas ini adalah satu-satunya tempat bentuk itu dituliskan di sisi
 * peramban.
 *
 * FOLDER dikirim sebagai parameter ?folderId=, bukan header: CORS server hanya
 * mengizinkan Authorization dan Content-Type, dan permintaan TANPA folder tetap
 * berarti seluruh penyewa — bentuk yang dipakai Studio dan JakRunner.
 */
const TOKEN_KEY = "openorchestrator.token";

/**
 * Alamat API untuk peramban.
 *
 * Bawaannya KOSONG: dasbor memanggil `/api/...` di alamatnya sendiri, dan
 * server dasbor meneruskannya ke backend (app/api/[...jalur]/route.ts). Dasbor
 * jadi bisa dibuka dari alamat mana pun — localhost, IP LAN, VM, domain —
 * tanpa build ulang dan tanpa setelan CORS.
 *
 * NEXT_PUBLIC_API_URL hanya dipakai kalau menunjuk ke mesin lain. Alamat
 * localhost DIABAIKAN: di peramban, "localhost" berarti komputer yang membuka
 * dasbor, bukan server-nya — itu sebabnya dasbor yang dibuka dari VM dulu
 * gagal masuk. Jalur lewat alamat sendiri mencakup kasus localhost juga.
 */
function alamatApi(): string {
  const dariBuild = (process.env.NEXT_PUBLIC_API_URL ?? "").trim().replace(/\/+$/, "");
  if (!dariBuild) return "";

  try {
    const host = new URL(dariBuild).hostname;
    return host === "localhost" || host === "127.0.0.1" || host === "[::1]" ? "" : dariBuild;
  } catch {
    return "";
  }
}

export const api = axios.create({
  baseURL: alamatApi(),
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
      if (window.location.pathname !== "/login") window.location.href = alamatMasuk();
    }
    return Promise.reject(error);
  },
);

/**
 * Layar masuk yang membawa alamat halaman ini, supaya sesudah masuk orangnya
 * kembali ke sana — termasuk ke /assistant/connect dengan parameter Open
 * Assistant yang masih utuh.
 */
export function alamatMasuk(): string {
  if (typeof window === "undefined") return "/login";

  const sekarang = window.location.pathname + window.location.search;
  return sekarang === "/" ? "/login" : `/login?next=${encodeURIComponent(sekarang)}`;
}

/**
 * Alamat ?next= yang boleh dituju sesudah masuk: hanya jalur di dasbor ini
 * sendiri. "//situs-lain" dan "https://..." ditolak — tanpa itu tautan masuk
 * bisa dipakai untuk melempar orang ke situs lain sesudah ia memasukkan sandi.
 */
export function jalurKembaliAman(next: string | null): string {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) return "/";
  if (next === "/login" || next.startsWith("/login?")) return "/";
  return next;
}

/**
 * Pesan galat dari server, atau pesan bawaan kalau bentuknya tidak dikenal —
 * dalam bahasa yang sedang dipilih.
 *
 * Server selalu menjawab dalam bahasa Indonesia (Studio dan JakRunner membaca
 * pesan yang sama), jadi terjemahannya terjadi di sini, satu tempat untuk
 * seluruh halaman.
 */
export function errorText(e: unknown, bawaan = "Terjadi kesalahan."): string {
  const err = e as AxiosError<{ error?: string }>;
  const pesan = err?.response?.data?.error ?? err?.message;

  return pesan ? terjemahkanPesan(bahasaAktif(), pesan) : terjemahkan(bahasaAktif(), bawaan);
}

/** Status HTTP sebuah galat, kalau ada. */
export function statusGalat(e: unknown): number | undefined {
  return (e as AxiosError)?.response?.status;
}

// ---------------------------------------------------------------------
// Bentuk data
// ---------------------------------------------------------------------

/**
 * ASSIGNED, PREPARING_SESSION, dan UNRESPONSIVE hanya dipakai job Robot Agent
 * unattended (kontrak v2): diambil agent, sesi Windows sedang disiapkan, dan
 * agent hilang kontak.
 */
export type JobState =
  | "PENDING"
  | "ASSIGNED"
  | "PREPARING_SESSION"
  | "RUNNING"
  | "UNRESPONSIVE"
  | "SUCCESSFUL"
  | "FAULTED"
  | "STOPPING"
  | "STOPPED";

/** Keadaan job yang belum selesai dan bisa dihentikan. */
export const KEADAAN_BERJALAN: JobState[] = ["PENDING", "ASSIGNED", "PREPARING_SESSION", "RUNNING", "UNRESPONSIVE"];

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
  folderId?: string | null;
  /** 2 = dijalankan Robot Agent unattended. */
  contractVersion?: number;
  /** Percobaan ke berapa; percobaan ulang otomatis menunjuk job lama lewat retryOf. */
  attempt?: number;
  retryOf?: string | null;
  retried?: boolean;
  /** Kode kegagalan untuk mesin, mis. SessionPreparationFailed, WorkflowFailed, AgentLost. */
  errorCode?: string | null;
  /** Kegagalannya disimpulkan Orchestrator (robot hilang), bukan dilaporkan robot. */
  failureInferred?: boolean;
  leaseExpiresAt?: string | null;
  runningAt?: string | null;
  unresponsiveSince?: string | null;
  stopRequestedAt?: string | null;
  sessionId?: number | null;
  windowsUser?: string | null;
  executorPid?: number | null;
  timeoutSeconds?: number | null;
  stopGraceSeconds?: number | null;
  packageName?: string | null;
  packageVersion?: string | null;
  packageSha256?: string | null;
  attachmentCount?: number;
  /** Tipe runtime yang diminta — atau yang dipakai, sesudah diambil robot. */
  runtimeType?: string | null;
  /** Robot dan mesin yang DIMINTA saat job dibuat; robotName/machineName adalah yang menjalankannya. */
  targetRobotName?: string | null;
  targetMachineName?: string | null;
  /** Job lama yang dijalankan ulang menjadi job ini. */
  restartedFrom?: string | null;
  /** Diminta dimatikan paksa: robot menerima KillJob tanpa menunggu jeda berhenti. */
  killRequestedAt?: string | null;
  /** Dasbor meminta jeda. Job tetap RUNNING. */
  pauseRequested?: boolean;
  /** Menurut robot: job ini sedang ditahan (hanya selama RUNNING). */
  paused?: boolean;
  /** Siapa yang menjeda, menurut robot: dari dasbor, atau tombol Jeda di PC robot. */
  pauseSource?: "dashboard" | "local" | null;
  pausedAt?: string | null;
  /** Lama jeda yang sudah selesai, detik. */
  pausedSeconds?: number;
};

/** Tipe runtime mesin — katalog tetap, sama dengan server. */
export const TIPE_RUNTIME = ["Production", "Testing", "Development"] as const;

/** Prioritas job. Inherited = ikut prioritas bawaan prosesnya. */
export const PRIORITAS_JOB = ["Inherited", "Low", "Normal", "High"] as const;

/**
 * Status mesin (V12), dihitung server: DISABLED dan MAINTENANCE dari keadaan
 * yang diatur orang; ONLINE kalau agent atau robot v1-nya baru berdenyut;
 * DISCONNECTED kalau pernah tersambung; selebihnya OFFLINE.
 */
export type StatusMesin = "ONLINE" | "OFFLINE" | "DISCONNECTED" | "MAINTENANCE" | "DISABLED";

/** Keadaan mesin yang diatur di halaman Mesin. Hanya Active yang mengambil job baru. */
export type KeadaanMesin = "Active" | "Maintenance" | "Disabled";

export const KEADAAN_MESIN: KeadaanMesin[] = ["Active", "Maintenance", "Disabled"];

/** Mesin yang terdaftar di sebuah folder (GET /api/folders/{id}/machines). */
export type MesinFolder = {
  id: string;
  name: string;
  /** Nama komputer menurut Robot Agent; null kalau belum pernah tersambung. */
  hostname: string | null;
  type: string;
  state: KeadaanMesin;
  status: StatusMesin;
  slots: number;
  /** Robot FOLDER INI yang bekerja di mesin itu. */
  folderRobots: number;
  /** Tipe runtime → jumlah. */
  runtimes: Record<string, number>;
  assignedAt?: string;
  assignedBy?: string | null;
};

/** Mesin Start Job untuk satu proses: yang terdaftar di folder PROSESNYA. */
export type MesinProses = {
  processId: string;
  processName: string;
  folderId: string;
  /** available: bisa dipilih — ONLINE dan punya runtime. Yang lain tampil mati. */
  machines: (MesinFolder & { available: boolean })[];
};

/** Pilihan Execution settings Start Job untuk satu folder (GET /api/jobs/start-options). */
export type PilihanMulai = {
  /** Katalog tipe runtime, urutan tampil. */
  runtimeTypes: string[];
  /** Mesin yang terdaftar di folder ini dan punya runtime. */
  machines: { id: string; name: string; type: string; online: boolean; status: StatusMesin; runtimes: Record<string, number> }[];
  /** Robot folder ini yang bisa mengambil job di salah satu mesin itu. */
  robots: {
    name: string;
    type: string;
    status: "AVAILABLE" | "BUSY" | "DISCONNECTED";
    machineName: string;
    username: string | null;
    userDisplayName: string | null;
    /** Robot milik akun yang sedang membuka dasbor — "jalankan sebagai diri sendiri". */
    self: boolean;
  }[];
};

export type JobAttachment = {
  id: string;
  kind: string;
  fileName: string | null;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
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
  /** Nama folder bersama tempat robot ini ditugaskan. */
  folders?: string[] | null;
  /** Mesin tempat Robot Agent melayani robot ini (robot unattended). */
  machineId?: string | null;
  windowsUsername?: string | null;
  /** Sandinya sendiri tidak pernah dikirim ke dasbor. */
  hasWindowsPassword?: boolean;
  windowsPasswordLocal?: boolean;
  sessionPolicy?: "Logoff" | "KeepLoggedIn";
  /** Dari denyut Robot Agent. */
  agentState?: "Idle" | "Busy" | "Error" | null;
  sessionId?: number | null;
  sessionState?: string | null;
  sessionReady?: boolean | null;
  reasonCode?: string | null;
  reasonText?: string | null;
  executorState?: string | null;
  executorPid?: number | null;
  /** Mis. LogonFailed — robot tidak diberi job sampai sandinya diganti. */
  needsAttention?: string | null;
  currentJobId?: string | null;
  currentJobProcess?: string | null;
  currentJobState?: JobState | null;
  /** Resolusi sesi unattended (V13); 0 = bawaan agent (1024x768). */
  resolutionWidth?: number;
  resolutionHeight?: number;
  /** 0 (bawaan), 15, 16, 24, atau 32. */
  resolutionDepth?: number;
  /** Sedang menjalankan automasi lokal Open Assistant (V14) sejak kapan; null = tidak. */
  busyLocalSince?: string | null;
  busyLocalName?: string | null;
  busyLocalTrigger?: PemicuLokal | null;
};

/** Pemicu jalan automasi (V14): job Orchestrator, Play manual, atau jadwal lokal Open Assistant. */
export type PemicuJalan = "job" | "manual" | "local-schedule";
export type PemicuLokal = Exclude<PemicuJalan, "job">;
export const PEMICU_JALAN: PemicuJalan[] = ["job", "manual", "local-schedule"];

export type Machine = {
  id: string;
  name: string;
  type: string;
  licenseKey: string | null;
  description: string | null;
  robotCount: number;
  createdAt: string;
  /** Berapa job Robot Agent boleh berjalan bersamaan. */
  slots?: number;
  leaseSeconds?: number;
  hasKey?: boolean;
  keyPrefix?: string | null;
  keyCreatedAt?: string | null;
  agentVersion?: string | null;
  agentOs?: string | null;
  agentHostName?: string | null;
  maxInteractiveSessions?: number | null;
  agentCpuPercent?: number | null;
  agentMemoryUsedMb?: number | null;
  agentMemoryTotalMb?: number | null;
  lastAgentLoginAt?: string | null;
  lastAgentHeartbeatAt?: string | null;
  agentOnline?: boolean;
  unattendedRobotCount?: number;
  activeJobs?: number;
  /** Tipe runtime → jumlah. Jumlahnya adalah slot mesin: job yang boleh berjalan bersamaan. */
  runtimes?: Record<string, number>;
  state?: KeadaanMesin;
  status?: StatusMesin;
  /** Folder bersama tempat mesin ini terdaftar. */
  folders?: string[] | null;
};

/** Setelan robot yang bisa diubah dari dasbor (POST/PUT /api/robots). */
export type RobotSetelan = {
  type?: string;
  machineName?: string;
  description?: string;
  windowsUsername?: string;
  /** Hanya bisa ditulis. */
  windowsPassword?: string;
  windowsPasswordLocal?: boolean;
  sessionPolicy?: "Logoff" | "KeepLoggedIn";
  /** Resolusi sesi unattended (V13): lebar dan tinggi berpasangan, 0 = bawaan. */
  resolutionWidth?: number;
  resolutionHeight?: number;
  resolutionDepth?: number;
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
  folderId: string;
  /** Pekerjaan yang belum selesai: PENDING, RUNNING, atau STOPPING. */
  activeJobs: number;
  /** Keadaan terjauh di antara pekerjaan itu; null kalau tidak ada. */
  activeState: JobState | null;
  /** Robot unattended: batas waktu job (null = tanpa batas), jeda berhenti, percobaan ulang otomatis. */
  timeoutSeconds?: number | null;
  stopGraceSeconds?: number;
  maxRetries?: number;
  /** Prioritas bawaan job — dipakai job yang dijalankan dengan prioritas Inherited. */
  priority?: string;
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
  sha256?: string | null;
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
  folderId?: string;
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
  folderId: string;
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

/**
 * Aset, termasuk kredensial: sejak V3 kredensial adalah aset bertipe
 * Credential, dengan nama pengguna di `username` dan kata sandi yang tidak
 * pernah dikirim ke peramban.
 */
export type Asset = {
  id: string;
  name: string;
  type: string;
  scope: string;
  /** Hanya untuk tipe Credential. */
  username: string | null;
  /** Selalu null untuk Credential dan Secret; lihat `hasValue`. */
  valueText: string | null;
  hasValue: boolean;
  description: string | null;
  createdAt: string;
  updatedAt: string | null;
  folderId: string;
};

export type Bucket = {
  id: string;
  name: string;
  description: string | null;
  fileCount: number;
  totalBytes: number;
  createdAt: string;
  folderId: string;
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
  /** Akun Windows tempat robot berjalan ("VM-01\robot"), kalau diketahui. */
  hostIdentity?: string | null;
  processName: string | null;
  jobId: string | null;
  loggedAt: string;
  /** Pemicu jalannya (V14); null untuk baris Orchestrator yang tidak menyebut pekerjaan. */
  trigger?: PemicuJalan | null;
};

/**
 * Saringan catatan — sama untuk daftar, pilihan saringan, dan ekspor.
 * Yang kosong tidak membatasi; yang terisi digabung dengan AND di server.
 */
export type SaringanLog = {
  level?: string[];
  robot?: string;
  process?: string;
  jobId?: string;
  folderId?: string | null;
  machine?: string;
  host?: string;
  /** 15m, 30m, 1h, 6h, 12h, 24h, today, yesterday, 7d, custom; kosong = semua waktu. */
  time?: string;
  /** Batas rentang custom, ISO-8601. */
  from?: string;
  to?: string;
  /** Potongan teks di pesan. */
  q?: string;
  /** Pemicu jalan (V14). */
  trigger?: PemicuJalan | "";
};

/**
 * Tingkat dikirim dipisah koma ("WARN,ERROR"), bukan larik: axios mengubah
 * larik menjadi level[]=..., dan nama berkurung itu tidak dikenali server
 * sebagai parameter "level". Nilai kosong tidak dikirim sama sekali.
 */
function parameterLog(p?: SaringanLog & { limit?: number }) {
  return {
    ...p,
    folderId: p?.folderId || undefined,
    level: p?.level?.length ? p.level.join(",") : undefined,
    robot: p?.robot || undefined,
    process: p?.process || undefined,
    jobId: p?.jobId || undefined,
    machine: p?.machine || undefined,
    host: p?.host || undefined,
    time: p?.time || undefined,
    from: p?.from || undefined,
    to: p?.to || undefined,
    q: p?.q?.trim() || undefined,
    trigger: p?.trigger || undefined,
  };
}

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
  /** Nama folder tempat pengguna ini ditugaskan. */
  folders?: string[] | null;
};

export type Role = {
  id: string;
  name: string;
  description: string | null;
  /** Pola dipisah koma: "*", "processes.*", "*.read", "processes.read". */
  permissions: string | null;
  userCount: number;
  /** Administrator: tidak bisa diubah atau dihapus. */
  locked: boolean;
};

/** Satu baris matriks izin: sumber dan tindakan yang berlaku untuknya. */
export type SumberIzin = { resource: string; actions: string[] };

/** Orang yang sedang masuk, beserta izin perannya. */
/** Open Assistant yang tersambung atas nama pengguna ini (masuk lewat dasbor). */
export type SambunganAssistant = {
  id: string;
  machineName: string;
  robotName: string;
  clientVersion?: string | null;
  createdAt: string;
  lastUsedAt: string;
  expiresAt: string;
  /** Sambungan yang sedang dipakai memanggil API ini. */
  current?: boolean;
};

export type Profil = User & {
  tenantName: string;
  tenantDisplayName?: string | null;
  /** Pola izin peran; lihat lib/izin.ts. */
  permissions?: string[];
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

// ---------------------------------------------------------------------
// Folder
// ---------------------------------------------------------------------

export type FolderNode = {
  id: string;
  parentId: string | null;
  name: string;
  description: string | null;
  isDefault: boolean;
  /** Folder Saya: hanya terlihat oleh pemiliknya. */
  personal: boolean;
  createdAt: string;
  /**
   * false untuk LELUHUR folder yang boleh dibuka: ia tampil supaya pohonnya
   * utuh, tapi tidak bisa dipilih.
   */
  accessible?: boolean;
};

export type FolderTree = {
  folders: FolderNode[];
  personal: FolderNode | null;
  /** Administrator: boleh membuat, mengubah, dan menghapus folder. */
  canManage: boolean;
};

/** Baris halaman pengelolaan folder: termasuk Folder Saya setiap orang, beserta isinya. */
export type FolderKelola = FolderNode & {
  ownerUsername: string | null;
  childCount: number;
  processCount: number;
  triggerCount: number;
  queueCount: number;
  assetCount: number;
  bucketCount: number;
  userCount: number;
  robotCount: number;
};

export type FolderAnggota = {
  folder: FolderNode;
  users: { id: string; username: string; displayName: string; role: string; isActive: boolean; assignedAt: string }[];
  robots: {
    id: string;
    name: string;
    machineName: string | null;
    type: string;
    assignedAt: string;
    /** Mesin tempat robot itu bekerja; null kalau mesinnya belum dikenal. */
    machineId?: string | null;
    /** Mesin robot itu terdaftar di folder ini; kalau tidak, robotnya tidak mengambil job folder ini. */
    machineRegistered?: boolean;
  }[];
  canManageUsers: boolean;
  canManageRobots: boolean;
  /** Boleh mendaftarkan dan mengeluarkan mesin folder ini. */
  canManageMachines: boolean;
};

// ---------------------------------------------------------------------
// Dasbor, audit
// ---------------------------------------------------------------------

/** Rentang waktu di dasbor: hari, minggu, bulan, atau tahun INI — rentang kalender. */
export type Periode = "today" | "week" | "month" | "year";

/** Angka pekerjaan sepanjang satu periode. */
export type AngkaPeriode = {
  /** Hari pertama periodenya, YYYY-MM-DD di zona tampilan server. */
  start: string;
  successful: number;
  faulted: number;
  stopped: number;
  /** Pekerjaan yang DIBUAT sepanjang periode, apa pun keadaannya. */
  total: number;
  /** Dari pekerjaan yang sudah selesai saja; 100 kalau belum ada yang selesai. */
  successRate: number;
};

/**
 * Satu irisan donat per proses. Anggotanya sama untuk keempat periode, dalam
 * urutan yang sama — warnanya mengikuti urutan itu. `other` menggabungkan
 * proses selebihnya.
 */
export type IrisanProses = { name: string | null; count: number; other: boolean; processes?: number };

export type Dashboard = {
  robots: { total: number; available: number; busy: number; disconnected: number };
  jobs: {
    running: number;
    pending: number;
    stopping: number;
    successfulToday: number;
    faultedToday: number;
    totalToday: number;
  };
  /**
   * Keempat periode sekaligus: setiap donat di dasbor memilih rentangnya
   * sendiri, dan berganti pilihan tidak perlu menunggu permintaan baru.
   */
  periods: Record<Periode, AngkaPeriode>;
  processBreakdown: Record<Periode, IrisanProses[]>;
  queues: { total: number; newItems: number; inProgress: number; failed: number };
  library: {
    processes: number;
    packages: number;
    assets: number;
    queues: number;
    triggers: number;
    triggersEnabled: number;
    buckets: number;
    users: number;
    robots: number;
    machines: number;
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

/**
 * Satu batang grafik. `bucket` adalah awal batangnya dalam waktu setempat
 * server, "YYYY-MM-DDTHH:mm" — jam untuk hari ini, hari untuk minggu dan
 * bulan, bulan untuk tahun.
 */
export type HistoryDay = { bucket: string; day: string; successful: number; faulted: number };

export type SearchHit = { kind: string; label: string; detail: string; page: string; folderId: string | null };

export type AuditEntry = {
  id: number;
  username: string | null;
  component: string;
  action: string;
  target: string | null;
  detail: string | null;
  createdAt: string;
};

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

const put = <T,>(url: string, body?: unknown, params?: Record<string, unknown>) =>
  api.put<T>(url, body ?? {}, { params }).then((r) => r.data);

const del = <T,>(url: string, params?: Record<string, unknown>) =>
  api.delete<T>(url, { params }).then((r) => r.data);

/** Nama dalam jalur bisa berisi spasi dan garis miring; selalu disandikan. */
const seg = (s: string) => encodeURIComponent(s);

/** Parameter folder: dihilangkan sama sekali kalau tidak ada, bukan dikirim kosong. */
const dalam = (folderId?: string | null) => (folderId ? { folderId } : undefined);

export const OpenOrchestratorApi = {
  login: (username: string, password: string) =>
    post<LoginResult>("/api/auth/login", { username, password }),

  me: () => get<Profil>("/api/auth/me"),

  /** Nama tampilan dan surel milik sendiri; surel kosong berarti dihapus. */
  updateProfile: (body: { displayName: string; email: string }) => put<Profil>("/api/auth/me", body),

  changePassword: (currentPassword: string, newPassword: string) =>
    post<{ status: string }>("/api/auth/password", { currentPassword, newPassword }),

  // --- Open Assistant: masuk lewat dasbor ---
  /** Persetujuan di /assistant/connect: kode sekali pakai, sudah berbentuk tautan openassistant://. */
  assistantCode: (body: {
    client: string;
    redirectUri: string;
    codeChallenge: string;
    codeChallengeMethod: string;
    state: string;
    machine?: string | null;
  }) => post<{ redirectUrl: string; expiresAt: string }>("/api/auth/assistant/code", body),
  assistantSessions: () => get<SambunganAssistant[]>("/api/auth/assistant/sessions"),
  revokeAssistantSession: (id: string) => del<{ ok: boolean }>(`/api/auth/assistant/sessions/${seg(id)}`),

  // --- folder ---
  folders: () => get<FolderTree>("/api/folders"),
  foldersManage: () => get<FolderKelola[]>("/api/folders/manage"),
  createFolder: (body: { name: string; description?: string; parentId?: string | null }) =>
    post<{ ok: boolean; id: string }>("/api/folders", body),
  /** parentId disertakan hanya kalau foldernya dipindah; null berarti ke akar. */
  updateFolder: (id: string, body: { name: string; description?: string; parentId?: string | null }) =>
    put<{ ok: boolean }>(`/api/folders/${seg(id)}`, body),
  deleteFolder: (id: string) => del<{ ok: boolean }>(`/api/folders/${seg(id)}`),
  personalFolder: () => post<FolderNode>("/api/folders/personal"),
  folderMembers: (id: string) => get<FolderAnggota>(`/api/folders/${seg(id)}/members`),
  assignUser: (id: string, username: string) => post<{ ok: boolean }>(`/api/folders/${seg(id)}/users`, { username }),
  unassignUser: (id: string, username: string) =>
    del<{ ok: boolean }>(`/api/folders/${seg(id)}/users/${seg(username)}`),
  assignRobot: (id: string, robotName: string) =>
    post<{ ok: boolean }>(`/api/folders/${seg(id)}/robots`, { robotName }),
  unassignRobot: (id: string, robotName: string) =>
    del<{ ok: boolean }>(`/api/folders/${seg(id)}/robots/${seg(robotName)}`),
  /** Mesin yang terdaftar di folder itu — hanya mesin ini yang menjalankan job folder itu. */
  folderMachines: (id: string) => get<MesinFolder[]>(`/api/folders/${seg(id)}/machines`),
  /** Mesin yang bisa didaftarkan: belum terdaftar, dan tidak dinonaktifkan. */
  availableFolderMachines: (id: string) => get<MesinFolder[]>(`/api/folders/${seg(id)}/available-machines`),
  /** Satu transaksi: satu mesin yang ditolak menggagalkan semuanya. */
  addFolderMachines: (id: string, machineIds: string[]) =>
    post<{ added: number; skipped: number }>(`/api/folders/${seg(id)}/machines/bulk`, { machineIds }),
  /** Mesinnya tetap ada; ia hanya tidak lagi menjalankan job folder ini. */
  removeFolderMachine: (id: string, machineId: string) =>
    del<{ ok: boolean }>(`/api/folders/${seg(id)}/machines/${seg(machineId)}`),

  // --- dasbor ---
  dashboard: (folderId?: string | null) => get<Dashboard>("/api/dashboard", dalam(folderId)),
  history: (period: Periode) => get<HistoryDay[]>("/api/dashboard/history", { period }),
  search: (q: string) => get<SearchHit[]>("/api/search", { q }),

  // --- pekerjaan ---
  jobs: (params?: { state?: string; process?: string; folderId?: string | null; limit?: number }) =>
    get<Job[]>("/api/jobs", { ...params, folderId: params?.folderId || undefined }),
  job: (id: string) => get<Job>(`/api/jobs/${seg(id)}`),
  /**
   * folderId: folder prosesnya. Nama proses unik per folder, jadi dasbor
   * selalu mengirimnya; tanpa itu server memakai aturan untuk Studio.
   */
  startJob: (body: {
    processName: string;
    folderId?: string;
    /** Bila ada: proses dan foldernya ditentukan dari id ini. */
    processId?: string;
    /** Akun: robot yang diminta; kosong = robot mana pun di folder. */
    robotName?: string;
    /** Mesin yang diminta; kosong = mesin mana pun. */
    machineName?: string;
    /** Sama dengan machineName, lewat id. Harus terdaftar di folder proses dan ONLINE. */
    machineId?: string;
    runtimeType?: string;
    /** Low, Normal, High, atau Inherited (ikut prioritas proses). */
    priority?: string;
    /** Berapa kali prosesnya dijalankan, 1–100. */
    count?: number;
    inputJson?: string;
    source?: string;
  }) => post<{ ok: boolean; id: string; ids: string[] }>("/api/jobs", body),
  /** Pilihan tipe runtime, akun, dan mesin Start Job untuk satu folder. */
  startOptions: (folderId: string) => get<PilihanMulai>("/api/jobs/start-options", { folderId }),
  /** Mesin Start Job: yang terdaftar di folder proses itu, beserta statusnya. */
  processMachines: (processId: string, runtimeType?: string) =>
    get<MesinProses>(`/api/processes/${seg(processId)}/available-machines`, { runtimeType: runtimeType || undefined }),
  stopJob: (id: string) => post<{ ok: boolean }>(`/api/jobs/${seg(id)}/stop`),
  /** Matikan paksa: robot menerima KillJob tanpa menunggu jeda berhenti rapi. */
  killJob: (id: string) => post<{ ok: boolean }>(`/api/jobs/${seg(id)}/kill`),
  pauseJob: (id: string) => post<{ ok: boolean }>(`/api/jobs/${seg(id)}/pause`),
  resumeJob: (id: string) => post<{ ok: boolean }>(`/api/jobs/${seg(id)}/resume`),
  /** Job BARU dengan konfigurasi job ini; job ini sendiri tidak diubah. */
  restartJob: (id: string) => post<{ ok: boolean; id: string; ids: string[] }>(`/api/jobs/${seg(id)}/restart`),
  jobAttachments: (id: string) => get<JobAttachment[]>(`/api/jobs/${seg(id)}/attachments`),
  /** Isi lampiran sebagai Blob — lewat axios supaya tokennya ikut; <img src> biasa tidak membawa header. */
  jobAttachmentBlob: (id: string, attachmentId: string) =>
    api
      .get<Blob>(`/api/jobs/${seg(id)}/attachments/${seg(attachmentId)}/content`, { responseType: "blob" })
      .then((r) => r.data),
  deleteJob: (id: string) => del<{ ok: boolean }>(`/api/jobs/${seg(id)}`),

  // --- robot dan mesin ---
  /** Dengan folder: hanya robot yang ditugaskan ke folder itu. */
  robots: (folderId?: string | null) => get<Robot[]>("/api/robots", dalam(folderId)),
  saveRobot: (body: RobotSetelan & { name: string }) => post<{ ok: boolean }>("/api/robots", body),
  /** Sandi Windows kosong = tetap yang lama; machineName kosong = lepas dari mesin. */
  updateRobot: (name: string, body: RobotSetelan) => put<{ ok: boolean }>(`/api/robots/${seg(name)}`, body),
  deleteRobot: (name: string) => del<{ ok: boolean }>(`/api/robots/${seg(name)}`),

  machines: () => get<Machine[]>("/api/machines"),
  saveMachine: (body: { name: string; type?: string; description?: string; runtimes?: Record<string, number> }) =>
    post<{ ok: boolean }>("/api/machines", body),
  deleteMachine: (name: string) => del<{ ok: boolean }>(`/api/machines/${seg(name)}`),
  updateMachine: (
    name: string,
    body: { description?: string; leaseSeconds?: number; runtimes?: Record<string, number>; state?: KeadaanMesin },
  ) =>
    put<{ ok: boolean }>(`/api/machines/${seg(name)}`, body),
  /** Kuncinya hanya terlihat SEKALI di jawaban ini. */
  createMachineKey: (name: string) =>
    post<{ machineKey: string; keyPrefix: string }>(`/api/machines/${seg(name)}/key`),
  revokeMachineKey: (name: string) => del<{ ok: boolean }>(`/api/machines/${seg(name)}/key`),

  environments: () => get<Environment[]>("/api/environments"),
  saveEnvironment: (body: { name: string; description?: string }) =>
    post<{ ok: boolean }>("/api/environments", body),
  deleteEnvironment: (name: string) => del<{ ok: boolean }>(`/api/environments/${seg(name)}`),

  // --- proses dan paket ---
  processes: (folderId?: string | null) => get<Process[]>("/api/processes", dalam(folderId)),
  saveProcess: (body: {
    name: string;
    packageName?: string;
    packageVersion?: string;
    description?: string;
    environment?: string;
    folderId?: string;
    /** 0 = tanpa batas waktu. */
    timeoutSeconds?: number;
    stopGraceSeconds?: number;
    maxRetries?: number;
    /** Prioritas bawaan job: Low, Normal, atau High. */
    priority?: string;
  }) => post<{ ok: boolean }>("/api/processes", body),
  /** Nama proses unik per folder: dari folder mana, ke folder mana. */
  moveProcess: (name: string, dari: string, ke: string) =>
    put<{ ok: boolean }>(`/api/processes/${seg(name)}/folder`, { folderId: ke }, { folderId: dari }),
  deleteProcess: (name: string, folderId: string) =>
    del<{ ok: boolean }>(`/api/processes/${seg(name)}`, { folderId }),

  /** Dengan folder: paket yang dipakai proses di folder itu; tanpa folder: seluruh umpan. */
  packages: (folderId?: string | null) => get<Package[]>("/api/packages", dalam(folderId)),
  deletePackage: (name: string, version: string) =>
    del<{ ok: boolean }>(`/api/packages/${seg(name)}/${seg(version)}`),
  packageUrl: (name: string, version: string) =>
    `${api.defaults.baseURL}/api/packages/${seg(name)}/${seg(version)}/content`,

  // --- pemicu ---
  triggers: (folderId?: string | null) => get<Trigger[]>("/api/triggers", dalam(folderId)),
  /** Pemicu tinggal di folder prosesnya, dan namanya unik di folder itu. */
  saveTrigger: (body: {
    name: string;
    processName: string;
    folderId?: string;
    robotName?: string;
    cron?: string;
    intervalMinutes?: number;
    timezone?: string;
    priority?: string;
    enabled?: boolean;
  }) => post<{ ok: boolean; nextRunAt: string }>("/api/triggers", body),
  toggleTrigger: (name: string, folderId: string) =>
    api
      .post<{ ok: boolean; enabled: boolean }>(`/api/triggers/${seg(name)}/toggle`, {}, { params: { folderId } })
      .then((r) => r.data),
  deleteTrigger: (name: string, folderId: string) =>
    del<{ ok: boolean }>(`/api/triggers/${seg(name)}`, { folderId }),

  // --- antrean ---
  queues: (folderId?: string | null) => get<Queue[]>("/api/queues", dalam(folderId)),
  saveQueue: (body: {
    name: string;
    description?: string;
    maxRetries?: number;
    acceptDuplicates?: boolean;
    folderId?: string;
  }) => post<{ ok: boolean }>("/api/queues", body),
  moveQueue: (name: string, folderId: string) =>
    put<{ ok: boolean }>(`/api/queues/${seg(name)}/folder`, { folderId }),
  deleteQueue: (name: string) => del<{ ok: boolean }>(`/api/queues/${seg(name)}`),
  queueItems: (name: string, params?: { status?: string; limit?: number }) =>
    get<QueueItem[]>(`/api/queues/${seg(name)}/items`, params),
  deleteQueueItem: (id: string) => del<{ ok: boolean }>(`/api/queues/items/${seg(id)}`),

  // --- aset (termasuk kredensial) ---
  //
  // /api/credentials sengaja tidak dipakai lagi dari sini. Endpoint itu tetap
  // ada di server untuk activity Get Credential, tapi yang dilayaninya sama
  // persis dengan aset bertipe Credential di bawah.
  assets: (folderId?: string | null) => get<Asset[]>("/api/assets", dalam(folderId)),
  saveAsset: (body: {
    name: string;
    type?: string;
    /** Hanya untuk Credential. */
    username?: string;
    /** Untuk Credential: kata sandinya. Kosong pada Credential/Secret yang disunting berarti "biarkan". */
    value?: string;
    description?: string;
    folderId?: string;
  }) => post<{ ok: boolean; created: boolean }>("/api/assets", body),
  moveAsset: (name: string, folderId: string) =>
    put<{ ok: boolean }>(`/api/assets/${seg(name)}/folder`, { folderId }),
  assetValue: (name: string) =>
    get<{ name: string; type: string; value: string | null }>(`/api/assets/${seg(name)}/value`),
  deleteAsset: (name: string) => del<{ ok: boolean }>(`/api/assets/${seg(name)}`),

  // --- ember penyimpanan ---
  buckets: (folderId?: string | null) => get<Bucket[]>("/api/buckets", dalam(folderId)),
  saveBucket: (body: { name: string; description?: string; folderId?: string }) =>
    post<{ ok: boolean }>("/api/buckets", body),
  moveBucket: (name: string, folderId: string) =>
    put<{ ok: boolean }>(`/api/buckets/${seg(name)}/folder`, { folderId }),
  deleteBucket: (name: string) => del<{ ok: boolean }>(`/api/buckets/${seg(name)}`),
  bucketFiles: (name: string) => get<BucketFile[]>(`/api/buckets/${seg(name)}/files`),
  uploadBucketFile: (name: string, body: { fileName: string; contentBase64: string; contentType?: string }) =>
    post<{ ok: boolean; fileName: string; sizeBytes: number }>(`/api/buckets/${seg(name)}/files`, body),
  deleteBucketFile: (name: string, id: string) =>
    del<{ ok: boolean }>(`/api/buckets/${seg(name)}/files/${seg(id)}`),
  bucketFileUrl: (name: string, id: string) =>
    `${api.defaults.baseURL}/api/buckets/${seg(name)}/files/${seg(id)}/content`,

  // --- catatan dan peringatan ---
  //
  // Tingkat boleh lebih dari satu dan dikirim dipisah koma ("WARN,ERROR").
  // Bukan larik: axios mengubah larik menjadi level[]=..., dan nama berkurung
  // itu tidak dikenali server sebagai parameter "level".
  logs: (params?: SaringanLog & { limit?: number }) => get<LogLine[]>("/api/logs", parameterLog(params)),
  /** Pilihan saringan Mesin, Proses, dan Host Identity — nilai yang ada di catatan folder dan rentang waktu itu. */
  logFilterOptions: (params: Pick<SaringanLog, "folderId" | "jobId" | "time" | "from" | "to">) =>
    get<{ machines: string[]; processes: string[]; hostIdentities: string[] }>("/api/logs/filters", parameterLog(params)),
  /** Unduh CSV dengan saringan yang sama dengan tabel (server membatasi 50.000 baris). */
  exportLogs: (params: SaringanLog, namaBerkas: string) => {
    const kueri = new URLSearchParams();
    for (const [kunci, nilai] of Object.entries(parameterLog(params))) {
      if (nilai !== undefined && nilai !== "") kueri.set(kunci, String(nilai));
    }
    return unduh(`${api.defaults.baseURL}/api/logs/export?${kueri}`, namaBerkas);
  },
  clearLogs: (olderThanDays: number) =>
    api.delete<{ ok: boolean; deleted: number }>("/api/logs", { params: { olderThanDays } })
      .then((r) => r.data),

  alerts: (params?: { unread?: string; severity?: string[]; limit?: number }) =>
    get<Alert[]>("/api/alerts", {
      ...params,
      severity: params?.severity?.length ? params.severity.join(",") : undefined,
    }),
  /** Lonceng di bilah atas: jumlah yang belum dibaca dan delapan yang terbaru. */
  alertSummary: () => get<{ unread: number; recent: Alert[] }>("/api/alerts/summary"),
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
  permissionCatalog: () => get<SumberIzin[]>("/api/permissions"),
  createRole: (body: { name: string; description?: string; permissions: string[] }) =>
    post<{ ok: boolean }>("/api/roles", body),
  /** name di badan boleh berbeda dari yang di jalur: itu berarti ganti nama. */
  updateRole: (nama: string, body: { name: string; description?: string; permissions: string[] }) =>
    put<{ ok: boolean }>(`/api/roles/${seg(nama)}`, body),
  deleteRole: (nama: string) => del<{ ok: boolean }>(`/api/roles/${seg(nama)}`),
  tenants: () => get<Tenant[]>("/api/tenants"),
  licensing: () => get<License[]>("/api/licensing"),
  settings: () => get<Settings>("/api/settings"),

  audit: (params?: { component?: string; q?: string; limit?: number }) =>
    get<AuditEntry[]>("/api/audit", {
      component: params?.component || undefined,
      q: params?.q || undefined,
      limit: params?.limit,
    }),
  auditComponents: () => get<{ component: string; total: number }[]>("/api/audit/components"),
};

/**
 * Unduh berkas yang butuh header Authorization.
 *
 * Tag <a href> biasa TIDAK membawa header apa pun, jadi tautan langsung ke
 * endpoint isi paket akan selalu menerima 401. Berkasnya diambil lewat axios
 * yang membawa tokennya, lalu diserahkan ke peramban sebagai blob.
 */
export async function unduh(url: string, namaBerkas: string) {
  let r;

  try {
    r = await api.get(url, { responseType: "blob", baseURL: "" });
  } catch (e) {
    // Galat dari server juga datang sebagai blob — dibaca dulu sebagai JSON,
    // supaya errorText menampilkan pesan server ("Rentang waktu tidak
    // dikenal"), bukan "Request failed with status code 400".
    const err = e as AxiosError;
    if (err.response?.data instanceof Blob) {
      try {
        err.response.data = JSON.parse(await err.response.data.text());
      } catch {
        // Bukan JSON: pesan bawaan axios tetap dipakai.
      }
    }
    throw err;
  }

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
