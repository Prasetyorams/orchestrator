-- =====================================================================
-- ForgeHub: skema awal (PostgreSQL)
--
-- Bentuknya MENGIKUTI server/ .NET, bukan sebaliknya. Alasannya satu:
-- Studio, JakRunner, dan activity Orchestrator sudah memanggil kontrak
-- itu hari ini, dan pemindahan basis data tidak boleh terasa oleh robot
-- yang sedang berjalan. Skema yang berbeda memaksa lapisan penerjemah di
-- setiap endpoint, dan lapisan seperti itu adalah tempat perbedaan
-- perilaku bersembunyi.
--
-- Akibatnya ada dua hal yang sengaja TIDAK dinormalisasi:
--
--   1. Rujukan antar-tabel memakai NAMA (process_name, robot_name,
--      queue_name), bukan foreign key. Nama proses dan nama robot adalah
--      identitas yang dipakai robot di lapangan — JakRunner mengenal
--      dirinya sebagai "PC-Fajar", bukan sebagai sebuah UUID. Memaksa FK
--      berarti setiap denyut robot harus mencari id-nya lebih dulu.
--
--   2. Beberapa kolom diulang (machine_name di jobs). Itu memang salinan,
--      dan disengaja: riwayat pekerjaan harus tetap terbaca setelah mesin
--      yang menjalankannya dihapus.
--
-- Yang TIDAK ditiru dari SQLite adalah tipenya. Di sana semuanya TEXT,
-- termasuk waktu dan angka. Di sini waktu adalah TIMESTAMPTZ dan benar
-- adalah BOOLEAN, karena basis data yang tahu tipe datanya bisa menolak
-- data yang salah sebelum data itu masuk.
--
-- Semua tabel milik penyewa membawa tenant_id, dan setiap indeks diawali
-- kolom itu. Pemisahan antar-tenant yang hanya diperiksa di lapisan
-- aplikasi akan bocor pada kueri pertama yang lupa menyaringnya, dan
-- bocornya baru ketahuan saat pelanggan melihat data pelanggan lain.
-- =====================================================================

CREATE TABLE tenants (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120)  NOT NULL UNIQUE,
    display_name    VARCHAR(200)  NOT NULL,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Orang dan hak
-- ---------------------------------------------------------------------

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    username        VARCHAR(80)   NOT NULL,
    -- PBKDF2-SHA256, 210.000 putaran, disimpan sebagai
    -- "pbkdf2$<putaran>$<garam b64>$<hash b64>".
    --
    -- Bukan BCrypt, dan itu bukan pilihan gaya: berkas forgehub.db yang
    -- ada sekarang berisi hash PBKDF2, dan kata sandi yang sudah dipakai
    -- orang harus tetap bisa dipakai sesudah pindah. Verifikator BCrypt
    -- tidak akan pernah cocok dengan hash itu.
    password_hash   VARCHAR(255)  NOT NULL,
    display_name    VARCHAR(200)  NOT NULL,
    email           VARCHAR(160),
    role            VARCHAR(48)   NOT NULL DEFAULT 'Administrator',
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ,
    CONSTRAINT uq_users_tenant_username UNIQUE (tenant_id, username)
);

CREATE TABLE roles (
    id              UUID PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(120)  NOT NULL,
    description     VARCHAR(400),
    -- Daftar izin dipisah koma. Tabel penghubung tersendiri akan lebih
    -- rapi, tapi izin di sini hanya dibaca utuh dan tidak pernah dikueri
    -- satu per satu.
    permissions     TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_roles_tenant_name UNIQUE (tenant_id, name)
);

-- ---------------------------------------------------------------------
-- Mesin, lingkungan, robot
-- ---------------------------------------------------------------------

CREATE TABLE machines (
    id              UUID PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(160)  NOT NULL,
    type            VARCHAR(48)   NOT NULL DEFAULT 'Standard',
    license_key     VARCHAR(160),
    description     VARCHAR(400),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_machines_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE environments (
    id              UUID PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(160)  NOT NULL,
    description     VARCHAR(400),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_environments_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE robots (
    id                UUID PRIMARY KEY,
    tenant_id         UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name              VARCHAR(160)  NOT NULL,
    machine_name      VARCHAR(160),
    username          VARCHAR(160),
    type              VARCHAR(48)   NOT NULL DEFAULT 'Unattended',
    environment       VARCHAR(160),
    description       VARCHAR(400),
    -- DISCONNECTED, AVAILABLE, BUSY. Nilai awalnya DISCONNECTED, bukan
    -- AVAILABLE: robot yang baru didaftarkan belum tentu menyala, dan
    -- menganggapnya siap membuat penjadwal mengirim pekerjaan ke mesin
    -- yang tidak ada.
    status            VARCHAR(32)   NOT NULL DEFAULT 'DISCONNECTED',
    cpu_percent       DOUBLE PRECISION NOT NULL DEFAULT 0,
    memory_mb         DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_heartbeat_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_robots_tenant_name UNIQUE (tenant_id, name)
);

-- ---------------------------------------------------------------------
-- Paket dan proses
-- ---------------------------------------------------------------------

CREATE TABLE packages (
    id              UUID PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(200)  NOT NULL,
    version         VARCHAR(64)   NOT NULL,
    description     VARCHAR(400),
    entry_point     VARCHAR(255),
    published_by    VARCHAR(160),
    published_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    size_bytes      BIGINT        NOT NULL DEFAULT 0,
    -- Isi .nupkg disimpan DI DALAM basis data, sama seperti di SQLite.
    -- Menyimpannya sebagai berkas di disk berarti basis data dan folder
    -- berkas bisa berbeda isi setelah pemulihan cadangan, dan paket yang
    -- terdaftar tapi berkasnya hilang adalah kegagalan yang baru
    -- ketahuan saat robot menjalankannya.
    content         BYTEA,
    CONSTRAINT uq_packages_tenant_name_version UNIQUE (tenant_id, name, version)
);

CREATE TABLE processes (
    id               UUID PRIMARY KEY,
    tenant_id        UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name             VARCHAR(200)  NOT NULL,
    package_name     VARCHAR(200),
    package_version  VARCHAR(64),
    environment      VARCHAR(160),
    description      VARCHAR(400),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_processes_tenant_name UNIQUE (tenant_id, name)
);

-- ---------------------------------------------------------------------
-- Pekerjaan
-- ---------------------------------------------------------------------

CREATE TABLE jobs (
    id            UUID PRIMARY KEY,
    tenant_id     UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    process_name  VARCHAR(200)  NOT NULL,
    robot_name    VARCHAR(160),
    machine_name  VARCHAR(160),
    -- PENDING, RUNNING, SUCCESSFUL, FAULTED, STOPPED
    state         VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    source        VARCHAR(48)   NOT NULL DEFAULT 'Manual',
    priority      VARCHAR(24)   NOT NULL DEFAULT 'Normal',
    progress      INTEGER       NOT NULL DEFAULT 0,
    info          TEXT,
    -- Argumen disimpan sebagai TEKS, bukan JSONB.
    --
    -- Studio mengirimnya sebagai string JSON dan JakRunner membacanya
    -- kembali sebagai string JSON; tidak ada satu pun kueri yang mencari
    -- ke dalamnya. JSONB akan menormalkan urutan kunci dan membuang
    -- spasi, jadi yang dibaca robot bukan lagi persis yang dikirim
    -- Studio — dan itu perbedaan yang tidak ada gunanya di sini.
    input_json    TEXT,
    output_json   TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    ended_at      TIMESTAMPTZ
);

CREATE TABLE triggers (
    id                UUID PRIMARY KEY,
    tenant_id         UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name              VARCHAR(200)  NOT NULL,
    process_name      VARCHAR(200)  NOT NULL,
    robot_name        VARCHAR(160),
    -- Time (berkala) atau Cron. Keduanya ada karena "tiap 15 menit" tidak
    -- perlu dituliskan sebagai cron oleh orang yang tidak menulis cron.
    type              VARCHAR(32)   NOT NULL DEFAULT 'Time',
    cron              VARCHAR(200),
    interval_minutes  INTEGER       NOT NULL DEFAULT 0,
    priority          VARCHAR(24)   NOT NULL DEFAULT 'Normal',
    timezone          VARCHAR(64)   NOT NULL DEFAULT 'UTC',
    runtime_type      VARCHAR(48)   NOT NULL DEFAULT 'Unattended',
    enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
    next_run_at       TIMESTAMPTZ,
    last_run_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_triggers_tenant_name UNIQUE (tenant_id, name)
);

-- ---------------------------------------------------------------------
-- Antrean
-- ---------------------------------------------------------------------

CREATE TABLE queues (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name               VARCHAR(200)  NOT NULL,
    description        VARCHAR(400),
    max_retries        INTEGER       NOT NULL DEFAULT 3,
    accept_duplicates  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_queues_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE queue_items (
    id           UUID PRIMARY KEY,
    tenant_id    UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    queue_name   VARCHAR(200)  NOT NULL,
    reference    VARCHAR(250),
    priority     VARCHAR(24)   NOT NULL DEFAULT 'Normal',
    -- NEW, IN_PROGRESS, SUCCESSFUL, FAILED, RETRIED
    status       VARCHAR(32)   NOT NULL DEFAULT 'NEW',
    content      TEXT,
    output       TEXT,
    exception    TEXT,
    retries      INTEGER       NOT NULL DEFAULT 0,
    robot_name   VARCHAR(160),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at   TIMESTAMPTZ,
    ended_at     TIMESTAMPTZ
);

-- ---------------------------------------------------------------------
-- Aset, kredensial, penyimpanan berkas
-- ---------------------------------------------------------------------

CREATE TABLE assets (
    id           UUID PRIMARY KEY,
    tenant_id    UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name         VARCHAR(200)  NOT NULL,
    type         VARCHAR(32)   NOT NULL DEFAULT 'Text',
    value_text   TEXT,
    description  VARCHAR(400),
    scope        VARCHAR(48)   NOT NULL DEFAULT 'Global',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    CONSTRAINT uq_assets_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE credentials (
    id            UUID PRIMARY KEY,
    tenant_id     UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name          VARCHAR(200)  NOT NULL,
    username      VARCHAR(200),
    -- AES-GCM, kuncinya diturunkan dari signing.key. Namanya diakhiri
    -- _enc supaya tidak ada yang tergoda menuliskan nilai polos ke sini.
    password_enc  TEXT,
    description   VARCHAR(400),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_credentials_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE buckets (
    id           UUID PRIMARY KEY,
    tenant_id    UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name         VARCHAR(200)  NOT NULL,
    description  VARCHAR(400),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_buckets_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE bucket_files (
    id            UUID PRIMARY KEY,
    tenant_id     UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    bucket_name   VARCHAR(200)  NOT NULL,
    file_name     VARCHAR(400)  NOT NULL,
    content_type  VARCHAR(200),
    size_bytes    BIGINT        NOT NULL DEFAULT 0,
    uploaded_by   VARCHAR(160),
    uploaded_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    content       BYTEA
);

-- ---------------------------------------------------------------------
-- Catatan, peringatan, lisensi
-- ---------------------------------------------------------------------

CREATE TABLE logs (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    level         VARCHAR(24)   NOT NULL DEFAULT 'INFO',
    message       TEXT          NOT NULL,
    robot_name    VARCHAR(160),
    machine_name  VARCHAR(160),
    process_name  VARCHAR(200),
    -- Sengaja BUKAN foreign key ke jobs: log adalah catatan sejarah, dan
    -- menghapus pekerjaan lama tidak boleh ikut menghapus jejak apa yang
    -- pernah terjadi. ON DELETE CASCADE di sini akan melakukan persis itu.
    job_id        VARCHAR(64),
    logged_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE alerts (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    severity    VARCHAR(24)   NOT NULL DEFAULT 'Info',
    title       VARCHAR(400)  NOT NULL,
    message     TEXT,
    source      VARCHAR(160),
    is_read     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE licenses (
    id          UUID PRIMARY KEY,
    tenant_id   UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product     VARCHAR(160)  NOT NULL,
    total       INTEGER       NOT NULL DEFAULT 0,
    used        INTEGER       NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ,
    CONSTRAINT uq_licenses_tenant_product UNIQUE (tenant_id, product)
);

-- ---------------------------------------------------------------------
-- Indeks
--
-- Semuanya diawali tenant_id: setiap kueri yang sah SELALU menyaring
-- tenant lebih dulu, jadi indeks yang tidak diawali kolom itu tidak akan
-- terpakai.
-- ---------------------------------------------------------------------
CREATE INDEX ix_robots_tenant_status         ON robots(tenant_id, status);
CREATE INDEX ix_jobs_tenant_state            ON jobs(tenant_id, state);
CREATE INDEX ix_jobs_tenant_created          ON jobs(tenant_id, created_at DESC);
CREATE INDEX ix_jobs_tenant_robot            ON jobs(tenant_id, robot_name);
CREATE INDEX ix_triggers_tenant_next         ON triggers(tenant_id, enabled, next_run_at);
CREATE INDEX ix_queue_items_tenant_q_status  ON queue_items(tenant_id, queue_name, status);
CREATE INDEX ix_queue_items_tenant_created   ON queue_items(tenant_id, created_at DESC);
CREATE INDEX ix_bucket_files_tenant_bucket   ON bucket_files(tenant_id, bucket_name);
CREATE INDEX ix_logs_tenant_id_desc          ON logs(tenant_id, id DESC);
CREATE INDEX ix_logs_tenant_level            ON logs(tenant_id, level);
CREATE INDEX ix_logs_job                     ON logs(job_id, id DESC);
CREATE INDEX ix_alerts_tenant_id_desc        ON alerts(tenant_id, id DESC);
CREATE INDEX ix_alerts_tenant_unread         ON alerts(tenant_id, is_read);
