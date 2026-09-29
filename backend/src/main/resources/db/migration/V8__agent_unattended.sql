-- =====================================================================
-- Robot Agent unattended (kontrak API v2, lihat ROBOT-API.md) dan
-- perbaikan kontrak v1 yang sudah dipakai agent lewat akun pengguna.
--
-- Semua kolom baru boleh kosong atau punya nilai bawaan, jadi JakRunner dan
-- Studio yang bicara v1 tidak melihat perbedaan apa pun di tabelnya.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. Peran Robot boleh mengetahui dan mengunduh paket.
--
-- Agent yang masuk dengan akun berperan Robot perlu tahu paket mana yang
-- dijalankan sebuah proses, lalu mengunduhnya. Tanpa dua izin ini job-nya
-- gagal dengan 403 di mesin robot — jauh dari layar Peran tempat sebabnya.
-- Hanya ditambahkan kalau belum tercakup, supaya peran yang sudah disunting
-- orang tidak berubah bentuk tanpa alasan.
-- ---------------------------------------------------------------------
UPDATE roles
   SET permissions = permissions || ',processes.read'
 WHERE name = 'Robot'
   AND permissions <> '*'
   AND (',' || replace(permissions, ' ', '') || ',') NOT LIKE '%,processes.read,%'
   AND (',' || replace(permissions, ' ', '') || ',') NOT LIKE '%,processes.*,%'
   AND (',' || replace(permissions, ' ', '') || ',') NOT LIKE '%,*.read,%';

UPDATE roles
   SET permissions = permissions || ',packages.read'
 WHERE name = 'Robot'
   AND permissions <> '*'
   AND (',' || replace(permissions, ' ', '') || ',') NOT LIKE '%,packages.read,%'
   AND (',' || replace(permissions, ' ', '') || ',') NOT LIKE '%,packages.*,%'
   AND (',' || replace(permissions, ' ', '') || ',') NOT LIKE '%,*.read,%';


-- ---------------------------------------------------------------------
-- 2. Sidik SHA-256 isi paket.
--
-- Robot memeriksa unduhannya terhadap nilai ini sebelum menjalankannya.
-- Paket lama dihitung sekali di sini; yang baru dihitung saat diterbitkan.
-- ---------------------------------------------------------------------
ALTER TABLE packages ADD COLUMN sha256 VARCHAR(64);

UPDATE packages SET sha256 = encode(sha256(content), 'hex') WHERE content IS NOT NULL;


-- ---------------------------------------------------------------------
-- 3. Mesin: kunci agent dan keadaan agent terakhir.
--
-- Yang disimpan hanya HASH kuncinya. Kunci yang bocor dari basis data sama
-- berbahayanya dengan kata sandi yang bocor, dan kunci ini tidak pernah perlu
-- dibaca kembali: yang lupa kuncinya membuat kunci baru.
--
-- Hash-nya unik di SELURUH tabel, bukan per tenant: agent masuk hanya
-- dengan kuncinya, sebelum ada yang tahu tenant mana yang dimaksud.
-- ---------------------------------------------------------------------
ALTER TABLE machines
    ADD COLUMN key_hash                VARCHAR(64),
    ADD COLUMN key_prefix              VARCHAR(16),
    ADD COLUMN key_created_at          TIMESTAMPTZ,
    -- Berapa job boleh berjalan bersamaan; dibatasi lagi oleh jumlah sesi
    -- interaktif yang dilaporkan Windows (satu untuk Windows 10/11).
    ADD COLUMN slots                   INTEGER      NOT NULL DEFAULT 1,
    ADD COLUMN lease_seconds           INTEGER      NOT NULL DEFAULT 180,
    -- Naik setiap setelan mesin atau robotnya berubah; agent yang melihat
    -- angka berbeda di jawaban denyut mengambil setelannya lagi.
    ADD COLUMN settings_version        INTEGER      NOT NULL DEFAULT 1,
    ADD COLUMN agent_version           VARCHAR(40),
    ADD COLUMN agent_os                VARCHAR(160),
    ADD COLUMN agent_host_name         VARCHAR(160),
    ADD COLUMN max_interactive_sessions INTEGER,
    ADD COLUMN agent_cpu_percent       DOUBLE PRECISION,
    ADD COLUMN agent_memory_used_mb    DOUBLE PRECISION,
    ADD COLUMN agent_memory_total_mb   DOUBLE PRECISION,
    ADD COLUMN last_agent_login_at     TIMESTAMPTZ,
    ADD COLUMN last_agent_heartbeat_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_machines_key_hash ON machines(key_hash) WHERE key_hash IS NOT NULL;


-- ---------------------------------------------------------------------
-- 4. Robot unattended: mesinnya, akun Windows-nya, dan keadaan terakhir yang
-- dilaporkan agent.
--
-- windows_password berisi hasil SecretBox, sama seperti nilai kredensial.
-- windows_password_local = sandinya disimpan di mesin robot, bukan di sini;
-- Orchestrator hanya menyimpan nama akunnya.
-- ---------------------------------------------------------------------
ALTER TABLE robots
    ADD COLUMN machine_id             UUID REFERENCES machines(id) ON DELETE SET NULL,
    ADD COLUMN windows_username       VARCHAR(200),
    ADD COLUMN windows_password       TEXT,
    ADD COLUMN windows_password_local BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN session_policy         VARCHAR(24)  NOT NULL DEFAULT 'Logoff',
    ADD COLUMN agent_state            VARCHAR(24),
    ADD COLUMN session_id             INTEGER,
    ADD COLUMN session_state          VARCHAR(24),
    ADD COLUMN session_ready          BOOLEAN,
    ADD COLUMN reason_code            VARCHAR(60),
    ADD COLUMN reason_text            VARCHAR(400),
    ADD COLUMN executor_state         VARCHAR(24),
    ADD COLUMN executor_pid           INTEGER,
    -- Mis. LogonFailed: tetap tampil sampai admin mengganti sandinya.
    ADD COLUMN needs_attention        VARCHAR(60);

CREATE INDEX ix_robots_machine ON robots(machine_id) WHERE machine_id IS NOT NULL;


-- ---------------------------------------------------------------------
-- 5. Job: konteks eksekusi, lease, dan riwayat percobaan.
--
-- contract_version = 2 untuk job yang diambil lewat /api/agent. Aturan v2
-- (lease, tidak responsif, rekonsiliasi) hanya berlaku untuk job itu;
-- job v1 tetap diperlakukan seperti sebelumnya.
--
-- failure_inferred = kegagalan ini DISIMPULKAN Orchestrator (robot hilang,
-- lease habis), bukan dilaporkan robotnya. Laporan asli yang datang
-- belakangan boleh menggantikannya selama belum ada percobaan ulang.
--
-- running_at = pertama kali mencapai RUNNING. Percobaan ulang otomatis
-- hanya untuk job yang belum pernah menjalankan workflow-nya.
-- ---------------------------------------------------------------------
ALTER TABLE jobs
    ADD COLUMN contract_version   SMALLINT     NOT NULL DEFAULT 1,
    ADD COLUMN attempt            INTEGER      NOT NULL DEFAULT 1,
    ADD COLUMN retry_of           UUID,
    ADD COLUMN retried            BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN error_code         VARCHAR(40),
    ADD COLUMN failure_inferred   BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN lease_expires_at   TIMESTAMPTZ,
    ADD COLUMN running_at         TIMESTAMPTZ,
    ADD COLUMN unresponsive_since TIMESTAMPTZ,
    ADD COLUMN stop_requested_at  TIMESTAMPTZ,
    ADD COLUMN session_id         INTEGER,
    ADD COLUMN windows_user       VARCHAR(200),
    ADD COLUMN executor_pid       INTEGER,
    ADD COLUMN last_seq           BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN machine_id         UUID,
    ADD COLUMN robot_id           UUID,
    ADD COLUMN timeout_seconds    INTEGER,
    ADD COLUMN stop_grace_seconds INTEGER,
    ADD COLUMN package_name       VARCHAR(200),
    ADD COLUMN package_version    VARCHAR(64),
    ADD COLUMN package_sha256     VARCHAR(64),
    -- Robot yang DIMINTA saat job dibuat (kosong = robot mana pun di folder).
    -- robot_name berubah menjadi robot yang mengambilnya, jadi tanpa kolom
    -- ini percobaan ulang tidak tahu apakah boleh berpindah robot.
    ADD COLUMN target_robot_name  VARCHAR(160);

UPDATE jobs SET target_robot_name = robot_name WHERE state = 'PENDING' AND robot_name <> '';

CREATE INDEX ix_jobs_active_robot ON jobs(tenant_id, robot_id)
    WHERE state IN ('ASSIGNED', 'PREPARING_SESSION', 'RUNNING', 'STOPPING', 'UNRESPONSIVE');

CREATE INDEX ix_jobs_active_machine ON jobs(machine_id)
    WHERE state IN ('ASSIGNED', 'PREPARING_SESSION', 'RUNNING', 'STOPPING', 'UNRESPONSIVE');


-- ---------------------------------------------------------------------
-- 6. Setelan per proses untuk robot unattended.
-- ---------------------------------------------------------------------
ALTER TABLE processes
    ADD COLUMN timeout_seconds    INTEGER,
    ADD COLUMN stop_grace_seconds INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN max_retries        INTEGER NOT NULL DEFAULT 1;


-- ---------------------------------------------------------------------
-- 7. Catatan: nomor urut per job, sumber, dan sesi Windows.
--
-- (job_id, seq) unik supaya kiriman ulang dari antrean robot tidak membuat
-- baris ganda. Baris tanpa seq (v1) tidak diperiksa.
-- ---------------------------------------------------------------------
ALTER TABLE logs
    ADD COLUMN seq        BIGINT,
    ADD COLUMN source     VARCHAR(160),
    ADD COLUMN session_id INTEGER;

CREATE UNIQUE INDEX uq_logs_job_seq ON logs(job_id, seq) WHERE job_id IS NOT NULL AND seq IS NOT NULL;


-- ---------------------------------------------------------------------
-- 8. Lampiran job: screenshot saat gagal.
-- ---------------------------------------------------------------------
CREATE TABLE job_attachments (
    id            UUID PRIMARY KEY,
    tenant_id     UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    job_id        UUID          NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    kind          VARCHAR(24)   NOT NULL DEFAULT 'Screenshot',
    file_name     VARCHAR(200),
    content_type  VARCHAR(80)   NOT NULL,
    size_bytes    INTEGER       NOT NULL,
    content       BYTEA         NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ix_job_attachments_job ON job_attachments(tenant_id, job_id);
