package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.JobState;
import id.jakforge.openorchestrator.model.RuntimeTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Akses data pekerjaan.
 *
 * <p>SELURUH SQL tentang pekerjaan ada di sini dan tidak di tempat lain. Itu
 * gunanya lapisan ini: kalau kolom {@code jobs} berubah, yang harus dibaca
 * hanya berkas ini.
 *
 * <p>Yang dikembalikan adalah BARIS, bukan entitas bertipe. Bentuk baris SQL
 * adalah bentuk JSON yang dibaca Studio, JakRunner, dan dasbor — memetakannya
 * lewat entitas berarti bentuk itu ditentukan kebetulan penamaan medan, bukan
 * oleh kolom yang benar-benar dipilih di sini.
 */
@Repository
@RequiredArgsConstructor
public class JobRepository {

    /** Keadaan "sedang dipegang robot" untuk SQL. */
    public static final String HELD_STATES = JobState.sqlList(JobState.HELD);

    /**
     * Sasaran, runtime, dan keadaan jeda/matikan — yang menentukan aksi apa
     * yang boleh di menu baris job. {@code paused} hanya benar selama job
     * masih RUNNING: jejak jeda job yang sudah selesai tidak berarti apa-apa.
     */
    private static final String ACTION_COLUMNS = """
            runtime_type, target_robot_name, target_machine_name, restarted_from, kill_requested_at,
            pause_requested, pause_source, paused_seconds,
            (state = 'RUNNING' AND paused_at IS NOT NULL) AS paused,
            (SELECT count(*) FROM job_attachments a
              WHERE a.tenant_id = jobs.tenant_id AND a.job_id = jobs.id) AS attachment_count
            """;

    private static final String LIST_COLUMNS = """
            id, process_name, robot_name, machine_name, state, source, priority,
            progress, info, created_at, started_at, ended_at, folder_id,
            contract_version, attempt, retry_of, error_code, running_at,
            """ + ACTION_COLUMNS;

    private static final String DETAIL_COLUMNS = """
            id, process_name, robot_name, machine_name, state, source, priority,
            progress, info, input_json, output_json, created_at, started_at, ended_at, folder_id,
            contract_version, attempt, retry_of, retried, error_code, failure_inferred,
            lease_expires_at, running_at, unresponsive_since, stop_requested_at,
            session_id, windows_user, executor_pid, timeout_seconds, stop_grace_seconds,
            package_name, package_version, package_sha256, paused_at,
            """ + ACTION_COLUMNS;

    /** Yang dikembalikan saat job berubah keadaan — cukup untuk peringatan dan percobaan ulang. */
    private static final String OUTCOME_COLUMNS = """
            id, tenant_id, folder_id, process_name, robot_name, robot_id, machine_id, target_robot_name,
            state, error_code, info, attempt, retried, running_at, source, priority, input_json
            """;

    private final Database database;

    /** @param folderId null berarti seluruh penyewa. */
    public List<Map<String, Object>> search(UUID tenantId, String state, String processName, UUID folderId,
                                            int limit) {
        // Penyaring dirangkai, bukan dijabarkan jadi empat kueri terpisah.
        // Halaman detail proses memerlukan "jalan milik proses ini saja", dan
        // menambahkannya sebagai cabang baru berarti empat kombinasi yang harus
        // dijaga tetap sama isinya.
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        conditions.add("tenant_id = ?");
        args.add(tenantId);

        if (state != null && !state.isBlank()) {
            conditions.add("state = ?");
            args.add(state);
        }

        if (processName != null && !processName.isBlank()) {
            conditions.add("process_name = ?");
            args.add(processName);
        }

        if (folderId != null) {
            conditions.add("folder_id = ?");
            args.add(folderId);
        }

        args.add(limit);

        return database.queryRows("""
                SELECT %s
                  FROM jobs
                 WHERE %s
                 ORDER BY created_at DESC
                 LIMIT ?
                """.formatted(LIST_COLUMNS, String.join(" AND ", conditions)), args.toArray());
    }

    public Optional<Map<String, Object>> findById(UUID tenantId, UUID jobId) {
        return database.queryRow("SELECT %s FROM jobs WHERE tenant_id = ? AND id = ?".formatted(DETAIL_COLUMNS),
                tenantId, jobId);
    }

    public Optional<String> findProcessName(UUID tenantId, UUID jobId) {
        return database.queryScalar("SELECT process_name FROM jobs WHERE tenant_id = ? AND id = ?", tenantId, jobId)
                .map(String::valueOf);
    }

    /**
     * Job baru yang menunggu robot.
     *
     * @param folderId          folder PROSESNYA. Nama proses unik per folder, jadi folder tidak bisa
     *                          lagi disimpulkan dari nama saja.
     * @param targetRobotName   robot yang diminta; null = robot mana pun di folder itu
     * @param targetMachineName mesin yang diminta; null = mesin mana pun
     * @param runtimeType       tipe runtime yang diminta; null = runtime mana pun
     * @param restartedFrom     job lama yang dijalankan ulang, atau null
     */
    public record NewJob(UUID id, UUID tenantId, UUID folderId, String processName, String targetRobotName,
                         String targetMachineName, String runtimeType, String source, String priority,
                         String info, String inputJson, UUID restartedFrom) {
    }

    /**
     * Urutan kolomnya dijaga: sebelas yang pertama sama dengan sebelum
     * sasaran mesin dan runtime ada. machine_name dibiarkan kosong — kolom itu
     * berisi mesin tempat job akhirnya berjalan, diisi saat diambil robot.
     */
    public void insert(NewJob job) {
        database.update("""
                INSERT INTO jobs
                    (id, tenant_id, folder_id, process_name, robot_name, target_robot_name, machine_name,
                     state, source, priority, progress, info, input_json, created_at,
                     target_machine_name, runtime_type, restarted_from)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, 0, ?, ?, now(), ?, ?, ?)
                """, job.id(), job.tenantId(), job.folderId(), job.processName(), job.targetRobotName(),
                job.targetRobotName(), null, job.source(), job.priority(), job.info(), job.inputJson(),
                job.targetMachineName(), job.runtimeType(), job.restartedFrom());
    }

    /**
     * Mesin robot v1 menurut barisnya: mesin Robot Agent kalau terikat, selain
     * itu nama mesin dari denyutnya. Dipakai klaim v1 untuk mencocokkan mesin
     * dan runtime yang diminta job.
     */
    private static final String V1_ROBOT_CTE = """
            WITH robot AS (
                SELECT r.id, COALESCE(bound.name, r.machine_name) AS machine_name,
                       COALESCE(bound.id, named.id) AS machine_id
                  FROM robots r
                  LEFT JOIN machines bound ON bound.id = r.machine_id
                  LEFT JOIN machines named ON named.tenant_id = r.tenant_id AND named.name = r.machine_name
                 WHERE r.tenant_id = ? AND r.name = ?
            )
            """;

    /**
     * Ambil satu pekerjaan untuk sebuah robot v1, dalam SATU langkah tak terbagi.
     *
     * <p>RETURNING mengembalikan baris yang benar-benar diubah, jadi tidak ada
     * kemungkinan salah tebak: mencarinya kembali lewat SELECT terpisah akan
     * memberi baris yang salah begitu satu robot memegang dua pekerjaan.
     *
     * <p>FOR UPDATE SKIP LOCKED menutup sisi satunya: dua robot yang bertanya
     * bersamaan tidak melihat baris yang sama sebagai PENDING, sehingga satu
     * pekerjaan tidak pernah dijalankan dua kali.
     *
     * <p>Robot hanya mengambil pekerjaan dari FOLDER tempat ia ditugaskan.
     * Pekerjaan yang menyebut nama robotnya langsung tetap diambil di mana pun
     * foldernya: yang memilih robot itu sudah menyatakan maksudnya.
     *
     * <p>Job yang meminta mesin tertentu hanya diambil robot di mesin itu, dan
     * job yang meminta tipe runtime hanya oleh robot yang mesinnya punya
     * runtime tipe itu. Job tanpa tipe mendapat tipe pertama mesinnya — yang
     * benar-benar dipakai, dan yang tampil di kolom Runtime.
     */
    public Optional<Map<String, Object>> claimNext(UUID tenantId, String robotName) {
        return database.queryRows(V1_ROBOT_CTE + """
                UPDATE jobs
                   SET state = 'RUNNING',
                       robot_name = ?,
                       started_at = now(),
                       running_at = now(),
                       info = 'Sedang dijalankan.',
                       machine_name = COALESCE((SELECT machine_name FROM robot), machine_name),
                       runtime_type = COALESCE(runtime_type,
                           (SELECT mr.runtime_type FROM machine_runtimes mr
                             WHERE mr.machine_id = (SELECT machine_id FROM robot)
                             ORDER BY %s LIMIT 1))
                 WHERE id = (
                       SELECT j.id FROM jobs j
                        WHERE j.tenant_id = ?
                          AND j.state = 'PENDING'
                          AND (j.robot_name = ?
                               OR ((j.robot_name IS NULL OR j.robot_name = '')
                                   AND EXISTS (SELECT 1 FROM folder_robots fr
                                                WHERE fr.folder_id = j.folder_id
                                                  AND fr.robot_id = (SELECT id FROM robot))))
                          AND (j.target_machine_name IS NULL
                               OR j.target_machine_name = (SELECT machine_name FROM robot))
                          AND (j.runtime_type IS NULL
                               OR EXISTS (SELECT 1 FROM machine_runtimes mr
                                           WHERE mr.machine_id = (SELECT machine_id FROM robot)
                                             AND mr.runtime_type = j.runtime_type))
                        ORDER BY CASE j.priority
                                   WHEN 'High' THEN 0
                                   WHEN 'Normal' THEN 1
                                   ELSE 2
                                 END,
                                 j.created_at
                        LIMIT 1
                        FOR UPDATE OF j SKIP LOCKED)
             RETURNING id, process_name, robot_name, state, priority, input_json,
                       created_at, started_at, folder_id
                """.formatted(RuntimeTypes.sqlOrder("mr.runtime_type")),
                tenantId, robotName, robotName, tenantId, robotName).stream().findFirst();
    }

    /**
     * Ambil satu pekerjaan untuk robot unattended lewat Robot Agent (v2).
     *
     * <p>Aturan pilihnya sama dengan {@link #claimNext}, ditambah satu:
     * percobaan ulang yang baru dibuat tidak diambil robot yang baru saja
     * gagal menjalankannya, selama {@code avoidSeconds} — memberi robot lain
     * kesempatan lebih dulu. Sesudah itu siapa pun boleh, termasuk robot yang
     * sama: satu-satunya robot di folder tidak boleh membuat job tertahan.
     *
     * <p>Keadaannya ASSIGNED, bukan RUNNING: agent masih harus menyiapkan
     * sesi Windows dan menyalakan Executor, dan lease-nya menjaga job yang
     * agentnya hilang di tengah penyiapan.
     *
     * <p>Hanya job yang tipe runtime-nya masih punya tempat kosong di mesin ini
     * ({@code freeRuntimeTypes}, dihitung pemanggil); job tanpa tipe mendapat
     * yang pertama dari daftar itu.
     *
     * @param freeRuntimeTypes tipe runtime mesin yang belum penuh, urutan katalog; tidak boleh kosong
     */
    public Optional<Map<String, Object>> claimNextForAgent(UUID tenantId, UUID robotId, String robotName,
                                                           UUID machineId, String machineName, int leaseSeconds,
                                                           long avoidSeconds, List<String> freeRuntimeTypes) {
        List<Object> args = new ArrayList<>(List.of(robotName, robotId, machineName, machineId,
                freeRuntimeTypes.getFirst(), leaseSeconds, tenantId, robotName, robotId, avoidSeconds, robotId,
                machineName));
        args.addAll(freeRuntimeTypes);

        return database.queryRows("""
                UPDATE jobs
                   SET state = 'ASSIGNED',
                       robot_name = ?,
                       robot_id = ?,
                       machine_name = ?,
                       machine_id = ?,
                       runtime_type = COALESCE(runtime_type, ?),
                       contract_version = 2,
                       started_at = now(),
                       lease_expires_at = now() + make_interval(secs => ?),
                       info = 'Diambil Robot Agent; menyiapkan sesi.'
                 WHERE id = (
                       SELECT j.id FROM jobs j
                        WHERE j.tenant_id = ?
                          AND j.state = 'PENDING'
                          AND (j.robot_name = ?
                               OR ((j.robot_name IS NULL OR j.robot_name = '')
                                   AND EXISTS (SELECT 1 FROM folder_robots fr
                                                WHERE fr.folder_id = j.folder_id AND fr.robot_id = ?)))
                          AND NOT (j.retry_of IS NOT NULL
                                   AND j.created_at > now() - make_interval(secs => ?)
                                   AND EXISTS (SELECT 1 FROM jobs x WHERE x.id = j.retry_of AND x.robot_id = ?))
                          AND (j.target_machine_name IS NULL OR j.target_machine_name = ?)
                          AND (j.runtime_type IS NULL OR j.runtime_type IN (%s))
                        ORDER BY CASE j.priority
                                   WHEN 'High' THEN 0
                                   WHEN 'Normal' THEN 1
                                   ELSE 2
                                 END,
                                 j.created_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED)
             RETURNING id, process_name, folder_id, input_json, attempt, priority, lease_expires_at, runtime_type
                """.formatted(Database.placeholders(freeRuntimeTypes.size())), args.toArray())
                .stream().findFirst();
    }

    /**
     * Job Robot Agent yang sedang dipegang di mesin ini, per tipe runtime.
     * Job yang diambil sebelum tipe runtime ada dihitung sebagai Production —
     * tipe yang diberikan migrasi V9 kepada setiap mesin lama.
     */
    public Map<String, Long> countHeldOnMachineByRuntime(UUID machineId) {
        Map<String, Long> counts = new LinkedHashMap<>();

        for (Map<String, Object> row : database.queryRows("""
                SELECT COALESCE(runtime_type, ?) AS runtime_type, count(*) AS held
                  FROM jobs
                 WHERE machine_id = ? AND contract_version = 2 AND state IN %s
                 GROUP BY 1
                """.formatted(HELD_STATES), RuntimeTypes.PRODUCTION, machineId)) {
            counts.put((String) row.get("runtimeType"), ((Number) row.get("held")).longValue());
        }

        return counts;
    }

    /**
     * Paket dan setelan proses sebuah job: proses dicari lewat nama DAN folder.
     *
     * <p>LEFT JOIN ke paket: proses yang paketnya sudah dihapus tetap
     * menghasilkan baris, dengan medan paket kosong — robot yang menerimanya
     * melaporkan PackageNotFound, bukan menunggu selamanya.
     */
    public Optional<Map<String, Object>> findExecutionPlan(UUID tenantId, UUID folderId, String processName) {
        return database.queryRow("""
                SELECT p.package_name, p.package_version, p.timeout_seconds, p.stop_grace_seconds, p.max_retries,
                       k.entry_point, k.sha256, k.size_bytes
                  FROM processes p
                  LEFT JOIN packages k
                         ON k.tenant_id = p.tenant_id AND k.name = p.package_name AND k.version = p.package_version
                 WHERE p.tenant_id = ? AND p.folder_id = ? AND p.name = ?
                """, tenantId, folderId, processName);
    }

    /** Paket yang benar-benar dijalankan, dicatat saat diambil — proses bisa pindah versi sesudahnya. */
    public void recordExecutionPlan(UUID jobId, String packageName, String packageVersion, String sha256,
                                    Integer timeoutSeconds, Integer stopGraceSeconds) {
        database.update("""
                UPDATE jobs
                   SET package_name = ?, package_version = ?, package_sha256 = ?,
                       timeout_seconds = ?, stop_grace_seconds = ?
                 WHERE id = ?
                """, packageName, packageVersion, sha256, timeoutSeconds, stopGraceSeconds, jobId);
    }

    /** Job robot di mesin ini yang sedang dipegang — untuk batas slot mesin. */
    public long countHeldOnMachine(UUID machineId) {
        return database.count("SELECT count(*) FROM jobs WHERE machine_id = ? AND contract_version = 2 AND state IN "
                + HELD_STATES, machineId);
    }

    /** Satu robot = satu akun Windows = satu job pada satu waktu. */
    public boolean robotHoldsJob(UUID robotId) {
        return database.exists("SELECT count(*) FROM jobs WHERE robot_id = ? AND contract_version = 2 AND state IN "
                + HELD_STATES, robotId);
    }

    /**
     * Job beserta mesin robotnya, DIKUNCI sampai transaksi selesai.
     *
     * <p>Dikunci supaya dua laporan untuk job yang sama — kiriman ulang dari
     * outbox yang kebetulan bersamaan — tidak sama-sama membaca {@code last_seq}
     * yang lama lalu sama-sama menerapkan dirinya.
     */
    public Optional<Map<String, Object>> lockById(UUID tenantId, UUID jobId) {
        return database.queryRow("""
                SELECT j.id, j.tenant_id, j.folder_id, j.process_name, j.robot_name, j.robot_id, j.machine_id,
                       j.state, j.contract_version, j.failure_inferred, j.retried, j.last_seq, j.attempt,
                       j.stop_requested_at, j.running_at, j.error_code
                  FROM jobs j
                 WHERE j.tenant_id = ? AND j.id = ?
                 FOR UPDATE
                """, tenantId, jobId);
    }

    /**
     * Perbarui keadaan dari laporan v1.
     *
     * <p>COALESCE pada info dan output: medan yang tidak dikirim TIDAK menghapus
     * yang sudah ada. Robot melaporkan kemajuan berkali-kali dan hanya mengisi
     * sebagian medan tiap kali.
     *
     * @param revived kegagalan hasil kesimpulan server digantikan laporan robot
     */
    public void updateState(UUID tenantId, UUID jobId, JobState state, int progress,
                            String info, String outputJson, boolean revived) {
        database.update("""
                UPDATE jobs
                   SET state = ?,
                       progress = ?,
                       info = COALESCE(?, info),
                       output_json = COALESCE(?, output_json),
                       running_at = CASE WHEN ? = 'RUNNING' THEN COALESCE(running_at, now()) ELSE running_at END,
                       ended_at = CASE WHEN ? THEN COALESCE(CASE WHEN ? THEN NULL ELSE ended_at END, now())
                                       ELSE NULL END,
                       failure_inferred = CASE WHEN ? THEN FALSE ELSE failure_inferred END,
                       error_code = CASE WHEN ? THEN NULL ELSE error_code END
                 WHERE tenant_id = ? AND id = ?
                """, state.name(), progress, info, outputJson, state.name(), state.isFinished(), revived,
                revived, revived, tenantId, jobId);
    }

    /**
     * Terapkan laporan agent v2.
     *
     * <p>Konteks eksekusi (sesi, akun Windows, PID Executor) memakai COALESCE:
     * agent mengirimnya sekali saat tahu, bukan di setiap laporan.
     */
    public void applyAgentReport(UUID jobId, JobState state, long seq, Integer progress, String info,
                                 String errorCode, String outputJson, Integer sessionId, String windowsUser,
                                 Integer executorPid, int leaseSeconds, boolean revived) {
        boolean finished = state.isFinished();
        boolean leased = state == JobState.ASSIGNED || state == JobState.PREPARING_SESSION;

        database.update("""
                UPDATE jobs
                   SET state = ?,
                       last_seq = ?,
                       progress = CASE WHEN ? THEN 100 ELSE COALESCE(?, progress) END,
                       info = COALESCE(?, info),
                       error_code = CASE WHEN ? THEN ? WHEN ? THEN NULL ELSE error_code END,
                       output_json = CASE WHEN ? THEN COALESCE(?, output_json) ELSE output_json END,
                       session_id = COALESCE(?, session_id),
                       windows_user = COALESCE(?, windows_user),
                       executor_pid = COALESCE(?, executor_pid),
                       running_at = CASE WHEN ? = 'RUNNING' THEN COALESCE(running_at, now()) ELSE running_at END,
                       lease_expires_at = CASE WHEN ? THEN now() + make_interval(secs => ?) ELSE lease_expires_at END,
                       unresponsive_since = NULL,
                       failure_inferred = CASE WHEN ? THEN FALSE ELSE failure_inferred END,
                       ended_at = CASE WHEN ? THEN COALESCE(CASE WHEN ? THEN NULL ELSE ended_at END, now())
                                       ELSE NULL END
                 WHERE id = ?
                """, state.name(), seq, finished, progress, info, finished, errorCode, revived, finished,
                outputJson, sessionId, windowsUser, executorPid, state.name(), leased, leaseSeconds, revived,
                finished, revived, jobId);
    }

    /** Laporan lama yang terlambat: hanya nomor urutnya yang dicatat, supaya kiriman ulangnya dijawab DUPLICATE. */
    public void recordSeq(UUID jobId, long seq) {
        database.update("UPDATE jobs SET last_seq = GREATEST(last_seq, ?) WHERE id = ?", seq, jobId);
    }

    /**
     * Minta berhenti.
     *
     * <p>Yang sedang dipegang robot menjadi STOPPING, bukan langsung STOPPED:
     * yang benar-benar bisa menghentikan proses adalah robotnya, dan ia baru
     * tahu pada denyut berikutnya. Yang masih PENDING belum dipegang siapa
     * pun, jadi boleh langsung berhenti.
     */
    public int requestStop(UUID tenantId, UUID jobId) {
        return database.update("""
                UPDATE jobs
                   SET state = CASE WHEN state = 'PENDING' THEN 'STOPPED' ELSE 'STOPPING' END,
                       info = 'Diminta berhenti.',
                       stop_requested_at = COALESCE(stop_requested_at, now()),
                       ended_at = CASE WHEN state = 'PENDING' THEN now() ELSE ended_at END
                 WHERE tenant_id = ? AND id = ?
                   AND state IN ('PENDING', 'RUNNING', 'ASSIGNED', 'PREPARING_SESSION', 'UNRESPONSIVE')
                """, tenantId, jobId);
    }

    /**
     * Minta dimatikan paksa: robot menerima KillJob tanpa menunggu jeda
     * berhenti rapi. Hanya untuk yang sudah dipegang robot — yang masih
     * PENDING cukup dihentikan (tidak ada proses yang perlu dimatikan).
     */
    public int requestKill(UUID tenantId, UUID jobId) {
        return database.update("""
                UPDATE jobs
                   SET state = 'STOPPING',
                       info = 'Diminta dimatikan paksa.',
                       stop_requested_at = COALESCE(stop_requested_at, now()),
                       kill_requested_at = COALESCE(kill_requested_at, now())
                 WHERE tenant_id = ? AND id = ?
                   AND state IN ('RUNNING', 'ASSIGNED', 'PREPARING_SESSION', 'UNRESPONSIVE', 'STOPPING')
                """, tenantId, jobId);
    }

    /** Minta jeda. Hanya job RUNNING; robot menerima PauseJob pada denyut berikutnya. */
    public int requestPause(UUID tenantId, UUID jobId) {
        return database.update("""
                UPDATE jobs SET pause_requested = TRUE
                 WHERE tenant_id = ? AND id = ? AND state = 'RUNNING'
                """, tenantId, jobId);
    }

    /** Batalkan permintaan jeda; job yang sudah ditahan robot menerima ResumeJob. */
    public int cancelPause(UUID tenantId, UUID jobId) {
        return database.update("""
                UPDATE jobs SET pause_requested = FALSE
                 WHERE tenant_id = ? AND id = ? AND state = 'RUNNING'
                """, tenantId, jobId);
    }

    public int delete(UUID tenantId, UUID jobId) {
        return database.update("DELETE FROM jobs WHERE tenant_id = ? AND id = ?", tenantId, jobId);
    }

    // -----------------------------------------------------------------
    // Perintah untuk robot
    // -----------------------------------------------------------------

    /**
     * Job robot v1 yang mungkin perlu diberi perintah pada jawaban denyutnya:
     * yang sedang dihentikan (StopJob, KillJob), dan yang berjalan (PauseJob,
     * ResumeJob). Perintahnya sendiri disusun {@code JobCommands.forV1}.
     */
    public List<Map<String, Object>> findCommandStateForV1Robot(UUID tenantId, String robotName) {
        return database.queryRows("""
                SELECT id, state, pause_requested, kill_requested_at IS NOT NULL AS kill
                  FROM jobs
                 WHERE tenant_id = ? AND robot_name = ? AND contract_version = 1
                   AND state IN ('RUNNING', 'STOPPING')
                """, tenantId, robotName);
    }

    /**
     * Job di mesin ini yang diminta berhenti, beserta apakah perintahnya kill:
     * diminta dimatikan paksa, atau jeda berhenti rapinya sudah lewat.
     */
    public List<Map<String, Object>> findStopRequestsForMachine(UUID machineId, int defaultGraceSeconds) {
        return database.queryRows("""
                SELECT id, COALESCE(stop_grace_seconds, ?) AS grace_seconds,
                       kill_requested_at IS NOT NULL
                       OR now() > COALESCE(stop_requested_at, now())
                                  + make_interval(secs => COALESCE(stop_grace_seconds, ?)) AS kill
                  FROM jobs
                 WHERE machine_id = ? AND contract_version = 2 AND state = 'STOPPING'
                """, defaultGraceSeconds, defaultGraceSeconds, machineId);
    }

    /** Job Robot Agent RUNNING di mesin ini yang diminta dijeda, atau yang sedang ditahan agent. */
    public List<Map<String, Object>> findPauseStateForMachine(UUID machineId) {
        return database.queryRows("""
                SELECT id, pause_requested, paused_at IS NOT NULL AS paused, pause_source
                  FROM jobs
                 WHERE machine_id = ? AND contract_version = 2 AND state = 'RUNNING'
                   AND (pause_requested OR paused_at IS NOT NULL)
                """, machineId);
    }

    // -----------------------------------------------------------------
    // Jeda menurut robot
    // -----------------------------------------------------------------

    /**
     * Catat job yang menurut robot v1 sedang ditahan.
     *
     * <p>Job robot ini yang TIDAK disebut dianggap sudah dilanjutkan: lama
     * jedanya ditambahkan ke {@code paused_seconds}, supaya batas waktu tidak
     * menghitungnya.
     *
     * @param pausedJobId job yang ditahan, atau null kalau tidak ada
     * @return job yang baru saja dijeda dan yang baru saja dilanjutkan — untuk log job-nya
     */
    public PauseChanges recordV1Pause(UUID tenantId, String robotName, UUID pausedJobId, String pauseSource) {
        List<Map<String, Object>> resumed = database.queryRows("""
                UPDATE jobs
                   SET paused_seconds = paused_seconds + GREATEST(0, extract(epoch FROM now() - paused_at))::int,
                       paused_at = NULL,
                       pause_source = NULL
                 WHERE tenant_id = ? AND robot_name = ? AND contract_version = 1
                   AND paused_at IS NOT NULL
                   AND id IS DISTINCT FROM ?
             RETURNING id, process_name, robot_name
                """, tenantId, robotName, pausedJobId);

        if (pausedJobId == null) return new PauseChanges(List.of(), resumed);

        List<Map<String, Object>> paused = database.queryRows("""
                UPDATE jobs
                   SET paused_at = now(), pause_source = ?
                 WHERE tenant_id = ? AND robot_name = ? AND contract_version = 1 AND id = ?
                   AND state = 'RUNNING' AND paused_at IS NULL
             RETURNING id, process_name, robot_name, pause_source
                """, pauseSource, tenantId, robotName, pausedJobId);

        // Sumber bisa berganti tanpa jedanya selesai: dijeda di PC robot
        // sesudah dasbor memintanya, atau sebaliknya.
        database.update("""
                UPDATE jobs SET pause_source = ?
                 WHERE tenant_id = ? AND robot_name = ? AND contract_version = 1 AND id = ?
                   AND paused_at IS NOT NULL AND pause_source IS DISTINCT FROM ?
                """, pauseSource, tenantId, robotName, pausedJobId, pauseSource);

        return new PauseChanges(paused, resumed);
    }

    /**
     * Sama dengan {@link #recordV1Pause} untuk satu robot Robot Agent, dengan
     * beberapa job yang mungkin ditahan sekaligus.
     *
     * @param pausedJobs job yang ditahan → sumber jedanya; kosong berarti tidak ada yang ditahan
     */
    public PauseChanges recordAgentPause(UUID robotId, Map<UUID, String> pausedJobs) {
        List<Object> resumeArgs = new ArrayList<>(List.of(robotId));
        String exclusion = "";

        if (!pausedJobs.isEmpty()) {
            exclusion = " AND id NOT IN (" + Database.placeholders(pausedJobs.size()) + ")";
            resumeArgs.addAll(pausedJobs.keySet());
        }

        List<Map<String, Object>> resumed = database.queryRows("""
                UPDATE jobs
                   SET paused_seconds = paused_seconds + GREATEST(0, extract(epoch FROM now() - paused_at))::int,
                       paused_at = NULL,
                       pause_source = NULL
                 WHERE robot_id = ? AND contract_version = 2 AND paused_at IS NOT NULL%s
             RETURNING id, tenant_id, process_name, robot_name
                """.formatted(exclusion), resumeArgs.toArray());

        List<Map<String, Object>> paused = new ArrayList<>();

        for (Map.Entry<UUID, String> entry : pausedJobs.entrySet()) {
            paused.addAll(database.queryRows("""
                    UPDATE jobs
                       SET paused_at = COALESCE(paused_at, now()), pause_source = ?
                     WHERE robot_id = ? AND contract_version = 2 AND id = ? AND state = 'RUNNING'
                       AND (paused_at IS NULL OR pause_source IS DISTINCT FROM ?)
                 RETURNING id, tenant_id, process_name, robot_name, pause_source,
                           paused_at = now() AS newly_paused
                    """, entry.getValue(), robotId, entry.getKey(), entry.getValue()));
        }

        return new PauseChanges(paused.stream().filter(row -> Boolean.TRUE.equals(row.get("newlyPaused"))).toList(),
                resumed);
    }

    /** Hasil pencatatan jeda: yang baru dijeda dan yang baru dilanjutkan. */
    public record PauseChanges(List<Map<String, Object>> paused, List<Map<String, Object>> resumed) {
    }

    /**
     * Dari job yang dilaporkan agent masih ia pegang: yang menurut
     * Orchestrator sudah selesai. Agent harus menghentikannya — misalnya job
     * yang sudah diulang di robot lain karena lease-nya habis.
     */
    public List<Map<String, Object>> findFinishedAmong(UUID machineId, Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) return List.of();

        List<Object> args = new ArrayList<>(List.of(machineId));
        args.addAll(jobIds);

        return database.queryRows("""
                SELECT id, stop_grace_seconds FROM jobs
                 WHERE machine_id = ? AND id IN (%s) AND state IN ('SUCCESSFUL', 'FAULTED', 'STOPPED')
                """.formatted(Database.placeholders(jobIds.size())), args.toArray());
    }

    // -----------------------------------------------------------------
    // Rekonsiliasi dari denyut agent
    // -----------------------------------------------------------------

    /**
     * Job yang dilaporkan agent masih ia pegang: perpanjang lease penyiapan,
     * pulihkan yang sempat UNRESPONSIVE, dan hidupkan kembali yang
     * kegagalannya hanya kesimpulan server (selama belum diulang).
     */
    public int confirmHeldJobs(UUID robotId, Collection<UUID> jobIds, int leaseSeconds) {
        if (jobIds.isEmpty()) return 0;

        List<Object> args = new ArrayList<>(List.of(leaseSeconds, robotId));
        args.addAll(jobIds);

        return database.update("""
                UPDATE jobs
                   SET state = CASE
                                 WHEN state IN ('ASSIGNED', 'PREPARING_SESSION', 'RUNNING', 'STOPPING') THEN state
                                 WHEN stop_requested_at IS NOT NULL THEN 'STOPPING'
                                 WHEN running_at IS NULL THEN 'PREPARING_SESSION'
                                 ELSE 'RUNNING'
                               END,
                       lease_expires_at = CASE
                                            WHEN running_at IS NULL THEN now() + make_interval(secs => ?)
                                            ELSE lease_expires_at
                                          END,
                       unresponsive_since = NULL,
                       info = CASE WHEN state IN ('UNRESPONSIVE', 'FAULTED', 'STOPPED')
                                   THEN 'Robot Agent kembali; pekerjaan masih berjalan.' ELSE info END,
                       ended_at = CASE WHEN state IN ('FAULTED', 'STOPPED') THEN NULL ELSE ended_at END,
                       error_code = CASE WHEN state IN ('FAULTED', 'STOPPED') THEN NULL ELSE error_code END,
                       failure_inferred = FALSE
                 WHERE robot_id = ? AND contract_version = 2 AND id IN (%s)
                   AND (state IN ('ASSIGNED', 'PREPARING_SESSION', 'RUNNING', 'STOPPING', 'UNRESPONSIVE')
                        OR (state IN ('FAULTED', 'STOPPED') AND failure_inferred AND NOT retried))
                """.formatted(Database.placeholders(jobIds.size())), args.toArray());
    }

    /**
     * Job yang menurut Orchestrator dipegang robot ini, tapi TIDAK disebut
     * agent — agent-nya sudah tidak menjalankannya (dimulai ulang, atau job
     * hilang). Yang sudah diminta berhenti menjadi STOPPED; sisanya FAULTED
     * dengan AgentRestarted.
     *
     * <p>Job yang baru diambil dalam {@code graceSeconds} terakhir tidak
     * disentuh: denyut yang berangkat sebelum klaimnya selesai memang belum
     * menyebutnya.
     *
     * <p>"Disebut" berarti masih berjalan ATAU laporan akhirnya masih antre di
     * outbox agent — keduanya belum selesai dilaporkan.
     */
    public List<Map<String, Object>> failUnreportedJobs(UUID robotId, Collection<UUID> reportedIds,
                                                        int graceSeconds) {
        List<Object> args = new ArrayList<>(List.of(robotId, graceSeconds));
        String exclusion = "";

        if (!reportedIds.isEmpty()) {
            exclusion = " AND id NOT IN (" + Database.placeholders(reportedIds.size()) + ")";
            args.addAll(reportedIds);
        }

        return database.queryRows("""
                UPDATE jobs
                   SET state = CASE WHEN stop_requested_at IS NOT NULL THEN 'STOPPED' ELSE 'FAULTED' END,
                       error_code = CASE WHEN stop_requested_at IS NOT NULL THEN error_code ELSE 'AgentRestarted' END,
                       failure_inferred = TRUE,
                       progress = 100,
                       ended_at = now(),
                       info = 'Robot Agent tidak lagi menjalankan pekerjaan ini.'
                 WHERE robot_id = ? AND contract_version = 2
                   AND state IN ('ASSIGNED', 'PREPARING_SESSION', 'RUNNING', 'STOPPING', 'UNRESPONSIVE')
                   AND started_at < now() - make_interval(secs => ?)%s
             RETURNING %s
                """.formatted(exclusion, OUTCOME_COLUMNS), args.toArray());
    }

    // -----------------------------------------------------------------
    // Pemantauan berkala (JobSupervisionService)
    // -----------------------------------------------------------------

    /** Lease penyiapan habis tanpa kabar: agent hilang sebelum workflow mulai. */
    public List<Map<String, Object>> expireLeases() {
        return database.queryRows("""
                UPDATE jobs
                   SET state = 'FAULTED',
                       error_code = 'LeaseExpired',
                       failure_inferred = TRUE,
                       progress = 100,
                       ended_at = now(),
                       info = 'Robot Agent tidak memberi kabar selama penyiapan (lease habis).'
                 WHERE contract_version = 2
                   AND state IN ('ASSIGNED', 'PREPARING_SESSION')
                   AND lease_expires_at < now()
             RETURNING %s
                """.formatted(OUTCOME_COLUMNS));
    }

    /** Agent berhenti berdenyut saat job berjalan: hilang kontak, belum gagal. */
    public List<Map<String, Object>> markUnresponsive(long silenceSeconds) {
        return database.queryRows("""
                UPDATE jobs j
                   SET state = 'UNRESPONSIVE',
                       unresponsive_since = now(),
                       info = 'Robot Agent tidak berdenyut; menunggu kabar.'
                  FROM robots r
                 WHERE j.contract_version = 2
                   AND j.state IN ('RUNNING', 'STOPPING')
                   AND r.id = j.robot_id
                   AND (r.last_heartbeat_at IS NULL OR r.last_heartbeat_at < now() - make_interval(secs => ?))
             RETURNING j.id, j.tenant_id, j.process_name, j.robot_name
                """, silenceSeconds);
    }

    /** Terlalu lama tanpa kabar: dianggap gagal (atau berhenti, kalau memang diminta berhenti). */
    public List<Map<String, Object>> markLost(long lostSeconds) {
        return database.queryRows("""
                UPDATE jobs
                   SET state = CASE WHEN stop_requested_at IS NOT NULL THEN 'STOPPED' ELSE 'FAULTED' END,
                       error_code = 'AgentLost',
                       failure_inferred = TRUE,
                       progress = 100,
                       ended_at = now(),
                       info = 'Robot Agent tidak memberi kabar terlalu lama; pekerjaan dianggap gagal.'
                 WHERE contract_version = 2
                   AND state = 'UNRESPONSIVE'
                   AND unresponsive_since < now() - make_interval(secs => ?)
             RETURNING %s
                """.formatted(OUTCOME_COLUMNS), lostSeconds);
    }

    /**
     * Jaring pengaman batas waktu: agent yang seharusnya menghentikan job pada
     * batasnya ternyata tidak melakukannya. Orchestrator memintanya berhenti.
     *
     * <p>Waktu dijeda tidak dihitung — yang sudah selesai ({@code paused_seconds})
     * maupun yang sedang berlangsung. Job yang dijeda sejam tidak boleh
     * dihentikan karena "terlalu lama berjalan".
     */
    public List<Map<String, Object>> requestTimeoutStops(long marginSeconds) {
        return database.queryRows("""
                UPDATE jobs
                   SET state = 'STOPPING',
                       stop_requested_at = now(),
                       info = 'Melewati batas waktu proses; Orchestrator meminta berhenti.'
                 WHERE contract_version = 2
                   AND state = 'RUNNING'
                   AND timeout_seconds IS NOT NULL
                   AND running_at < now() - make_interval(secs => timeout_seconds + COALESCE(stop_grace_seconds, 30) + ?
                                                                  + paused_seconds
                                                                  + COALESCE(extract(epoch FROM now() - paused_at), 0))
             RETURNING id, tenant_id, process_name, robot_name
                """, marginSeconds);
    }

    /**
     * Tandai gagal pekerjaan v1 yang robotnya berhenti berdenyut.
     *
     * <p>RETURNING dipakai supaya peringatan hanya dibuat untuk baris yang
     * BENAR-BENAR berubah. Memilih dulu lalu memperbarui membuka celah:
     * pekerjaan yang selesai di antara kedua langkah tetap mendapat peringatan
     * "terputus" padahal berhasil.
     *
     * <p>Kegagalan ini kesimpulan, jadi {@code failure_inferred}: laporan akhir
     * robot yang datang sesudahnya masih diterima.
     *
     * <p>Yang sedang dihentikan (STOPPING) menjadi STOPPED, bukan FAULTED:
     * orangnya memang meminta berhenti. Tanpa ini, Hentikan atau Matikan pada
     * job yang robotnya mati membuat job itu STOPPING selamanya.
     *
     * @param silenceSeconds berapa lama robotnya diam sebelum dianggap terputus
     */
    public List<Map<String, Object>> markJobsOfSilentRobotsFaulted(int silenceSeconds) {
        return database.queryRows("""
                UPDATE jobs j
                   SET state = CASE WHEN j.state = 'STOPPING' THEN 'STOPPED' ELSE 'FAULTED' END,
                       info = 'Robot ' || COALESCE(j.robot_name, '?')
                              || CASE WHEN j.state = 'STOPPING'
                                      THEN ' berhenti berdenyut saat pekerjaan sedang dihentikan.'
                                      ELSE ' berhenti berdenyut saat pekerjaan masih berjalan.' END,
                       failure_inferred = TRUE,
                       ended_at = now()
                  FROM (SELECT j2.id
                          FROM jobs j2
                          LEFT JOIN robots r
                                 ON r.tenant_id = j2.tenant_id AND r.name = j2.robot_name
                         WHERE j2.state IN ('RUNNING', 'STOPPING')
                           AND j2.contract_version = 1
                           AND (r.last_heartbeat_at IS NULL
                                OR now() - r.last_heartbeat_at > make_interval(secs => ?))
                         FOR UPDATE OF j2 SKIP LOCKED) AS stale
                 WHERE j.id = stale.id AND j.state IN ('RUNNING', 'STOPPING')
             RETURNING j.id, j.tenant_id, j.process_name, j.robot_name, j.state
                """, silenceSeconds);
    }

    // -----------------------------------------------------------------
    // Percobaan ulang
    // -----------------------------------------------------------------

    /** Setelan percobaan ulang proses sebuah job. */
    public int findMaxRetries(UUID tenantId, UUID folderId, String processName) {
        return database.queryScalar("""
                SELECT max_retries FROM processes WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, tenantId, folderId, processName)
                .map(value -> ((Number) value).intValue())
                .orElse(0);
    }

    /**
     * Job baru untuk mengulang yang gagal. Robot sasarannya sama dengan
     * permintaan ASLI — bukan robot yang kebetulan mengambilnya.
     *
     * @return false kalau job lama sudah pernah diulang (dua pemanggil bersamaan)
     */
    public boolean insertRetry(UUID retryId, Map<String, Object> failed, String info) {
        int marked = database.update("UPDATE jobs SET retried = TRUE WHERE id = ? AND NOT retried",
                UUID.fromString((String) failed.get("id")));

        if (marked == 0) return false;

        database.update("""
                INSERT INTO jobs
                    (id, tenant_id, folder_id, process_name, robot_name, target_robot_name, state, source,
                     priority, progress, info, input_json, created_at, attempt, retry_of,
                     target_machine_name, runtime_type)
                SELECT ?, tenant_id, folder_id, process_name, target_robot_name, target_robot_name, 'PENDING',
                       'Retry', priority, 0, ?, input_json, now(), attempt + 1, id,
                       target_machine_name, runtime_type
                  FROM jobs WHERE id = ?
                """, retryId, info, UUID.fromString((String) failed.get("id")));

        return true;
    }

    // -----------------------------------------------------------------
    // Akses agent dan executor
    // -----------------------------------------------------------------

    /** Agent boleh mengunduh paket yang dijalankan job robot di mesinnya. */
    public boolean machineRunsPackage(UUID machineId, String packageName, String packageVersion) {
        return database.exists("""
                SELECT count(*) FROM jobs
                 WHERE machine_id = ? AND contract_version = 2 AND package_name = ? AND package_version = ?
                   AND state IN %s
                """.formatted(HELD_STATES), machineId, packageName, packageVersion);
    }

    /** Pemilik sebuah job — untuk memeriksa bahwa catatan agent memang tentang job mesinnya. */
    public Optional<Map<String, Object>> findOwnership(UUID tenantId, UUID jobId) {
        return database.queryRow("""
                SELECT id, machine_id, robot_name, process_name, contract_version
                  FROM jobs WHERE tenant_id = ? AND id = ?
                """, tenantId, jobId);
    }

    /** Token executor hanya berlaku selama job-nya dipegang robot. */
    public boolean isHeld(UUID tenantId, UUID jobId) {
        return database.exists("SELECT count(*) FROM jobs WHERE tenant_id = ? AND id = ? AND state IN " + HELD_STATES,
                tenantId, jobId);
    }
}
