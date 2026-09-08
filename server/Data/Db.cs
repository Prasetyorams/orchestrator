using Microsoft.Data.Sqlite;

namespace ForgeHub.Data;

/// <summary>
/// Basis data ForgeHub.
///
/// SQLite dipilih supaya ForgeHub bisa dinyalakan tanpa memasang apa pun:
/// tidak ada layanan yang perlu dijalankan, tidak ada kata sandi yang perlu
/// dibuat, tidak ada berkas setelan yang perlu diisi lebih dulu. Berkasnya satu,
/// di sebelah program, dan bisa disalin atau dihapus begitu saja.
///
/// Semua tabel milik penyewa memuat kolom tenant_id, dan setiap indeks
/// diawali kolom itu. Susunan tersebut sama persis dengan skema PostgreSQL di
/// backend/, sehingga pindah ke sana nanti tidak mengubah bentuk datanya.
/// </summary>
public static class Db
{
    private static string _connectionString;

    public static void Init(string dataDirectory)
    {
        Directory.CreateDirectory(dataDirectory);

        var path = Path.Combine(dataDirectory, "forgehub.db");
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared,
        }.ToString();

        using var connection = Open();

        // WAL: pembaca tidak menghalangi penulis. Robot mengirim denyut dan log
        // terus-menerus sementara dasbor membaca; tanpa WAL keduanya saling
        // menunggu dan dasbornya tersendat.
        Exec(connection, "PRAGMA journal_mode=WAL;");
        Exec(connection, "PRAGMA busy_timeout=5000;");
        Exec(connection, "PRAGMA foreign_keys=ON;");

        CreateSchema(connection);
        Seed.Apply(connection);
    }

    public static SqliteConnection Open()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        return connection;
    }

    public static void Exec(SqliteConnection connection, string sql)
    {
        using var command = connection.CreateCommand();
        command.CommandText = sql;
        command.ExecuteNonQuery();
    }

    private static void CreateSchema(SqliteConnection connection)
    {
        Exec(connection, Schema);
        Migrasi(connection);
    }

    /// <summary>
    /// Kolom yang ditambahkan SESUDAH basis data pertama kali dibuat.
    ///
    /// "CREATE TABLE IF NOT EXISTS" tidak menyentuh tabel yang sudah ada, jadi
    /// pemasangan lama tidak akan pernah mendapat kolom baru dari sana. SQLite
    /// tidak punya "ADD COLUMN IF NOT EXISTS", jadi kolomnya diperiksa dulu
    /// lewat PRAGMA — kalau langsung ditambahkan, menjalankan ForgeHub kedua
    /// kali akan gagal dengan "duplicate column name".
    /// </summary>
    private static void Migrasi(SqliteConnection connection)
    {
        TambahKolom(connection, "triggers", "priority", "TEXT NOT NULL DEFAULT 'Normal'");
        TambahKolom(connection, "triggers", "timezone", "TEXT NOT NULL DEFAULT 'UTC'");
        TambahKolom(connection, "triggers", "runtime_type", "TEXT NOT NULL DEFAULT 'Unattended'");
    }

    private static void TambahKolom(SqliteConnection connection, string tabel, string kolom, string definisi)
    {
        using (var periksa = connection.CreateCommand())
        {
            periksa.CommandText = "SELECT COUNT(*) FROM pragma_table_info('" + tabel + "') WHERE name = $k";
            periksa.Parameters.AddWithValue("$k", kolom);

            if (Convert.ToInt64(periksa.ExecuteScalar()) > 0) return;
        }

        Exec(connection, "ALTER TABLE " + tabel + " ADD COLUMN " + kolom + " " + definisi);
    }

    /// <summary>
    /// Skema dibuat dengan IF NOT EXISTS, jadi menjalankan ulang ForgeHub tidak
    /// pernah menghapus data yang sudah ada.
    /// </summary>
    private const string Schema = @"

CREATE TABLE IF NOT EXISTS tenants (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL UNIQUE,
    display_name  TEXT NOT NULL,
    created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
    id             TEXT PRIMARY KEY,
    tenant_id      TEXT NOT NULL,
    username       TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    display_name   TEXT NOT NULL,
    email          TEXT,
    role           TEXT NOT NULL DEFAULT 'Administrator',
    is_active      INTEGER NOT NULL DEFAULT 1,
    created_at     TEXT NOT NULL,
    last_login_at  TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_users_tenant_username ON users(tenant_id, username);

CREATE TABLE IF NOT EXISTS roles (
    id           TEXT PRIMARY KEY,
    tenant_id    TEXT NOT NULL,
    name         TEXT NOT NULL,
    description  TEXT,
    permissions  TEXT,
    created_at   TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_roles_tenant_name ON roles(tenant_id, name);

CREATE TABLE IF NOT EXISTS machines (
    id           TEXT PRIMARY KEY,
    tenant_id    TEXT NOT NULL,
    name         TEXT NOT NULL,
    type         TEXT NOT NULL DEFAULT 'Standard',
    license_key  TEXT,
    description  TEXT,
    created_at   TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_machines_tenant_name ON machines(tenant_id, name);

CREATE TABLE IF NOT EXISTS environments (
    id           TEXT PRIMARY KEY,
    tenant_id    TEXT NOT NULL,
    name         TEXT NOT NULL,
    description  TEXT,
    created_at   TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_environments_tenant_name ON environments(tenant_id, name);

CREATE TABLE IF NOT EXISTS robots (
    id                TEXT PRIMARY KEY,
    tenant_id         TEXT NOT NULL,
    name              TEXT NOT NULL,
    machine_name      TEXT,
    username          TEXT,
    type              TEXT NOT NULL DEFAULT 'Unattended',
    environment       TEXT,
    description       TEXT,
    status            TEXT NOT NULL DEFAULT 'DISCONNECTED',
    cpu_percent       REAL NOT NULL DEFAULT 0,
    memory_mb         REAL NOT NULL DEFAULT 0,
    last_heartbeat_at TEXT,
    created_at        TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_robots_tenant_name ON robots(tenant_id, name);
CREATE INDEX IF NOT EXISTS ix_robots_tenant_status ON robots(tenant_id, status);

CREATE TABLE IF NOT EXISTS packages (
    id            TEXT PRIMARY KEY,
    tenant_id     TEXT NOT NULL,
    name          TEXT NOT NULL,
    version       TEXT NOT NULL,
    description   TEXT,
    entry_point   TEXT,
    published_by  TEXT,
    published_at  TEXT NOT NULL,
    size_bytes    INTEGER NOT NULL DEFAULT 0,
    content       BLOB
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_packages_tenant_name_version ON packages(tenant_id, name, version);

CREATE TABLE IF NOT EXISTS processes (
    id               TEXT PRIMARY KEY,
    tenant_id        TEXT NOT NULL,
    name             TEXT NOT NULL,
    package_name     TEXT,
    package_version  TEXT,
    environment      TEXT,
    description      TEXT,
    created_at       TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_processes_tenant_name ON processes(tenant_id, name);

CREATE TABLE IF NOT EXISTS jobs (
    id            TEXT PRIMARY KEY,
    tenant_id     TEXT NOT NULL,
    process_name  TEXT NOT NULL,
    robot_name    TEXT,
    machine_name  TEXT,
    state         TEXT NOT NULL DEFAULT 'PENDING',
    source        TEXT NOT NULL DEFAULT 'Manual',
    priority      TEXT NOT NULL DEFAULT 'Normal',
    progress      INTEGER NOT NULL DEFAULT 0,
    info          TEXT,
    input_json    TEXT,
    output_json   TEXT,
    created_at    TEXT NOT NULL,
    started_at    TEXT,
    ended_at      TEXT
);
CREATE INDEX IF NOT EXISTS ix_jobs_tenant_state ON jobs(tenant_id, state);
CREATE INDEX IF NOT EXISTS ix_jobs_tenant_created ON jobs(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_jobs_tenant_robot ON jobs(tenant_id, robot_name);

CREATE TABLE IF NOT EXISTS triggers (
    id                TEXT PRIMARY KEY,
    tenant_id         TEXT NOT NULL,
    name              TEXT NOT NULL,
    process_name      TEXT NOT NULL,
    robot_name        TEXT,
    type              TEXT NOT NULL DEFAULT 'Time',
    cron              TEXT,
    interval_minutes  INTEGER NOT NULL DEFAULT 0,
    priority          TEXT NOT NULL DEFAULT 'Normal',
    timezone          TEXT NOT NULL DEFAULT 'UTC',
    runtime_type      TEXT NOT NULL DEFAULT 'Unattended',
    enabled           INTEGER NOT NULL DEFAULT 1,
    next_run_at       TEXT,
    last_run_at       TEXT,
    created_at        TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_triggers_tenant_name ON triggers(tenant_id, name);
CREATE INDEX IF NOT EXISTS ix_triggers_tenant_next ON triggers(tenant_id, enabled, next_run_at);

CREATE TABLE IF NOT EXISTS queues (
    id                 TEXT PRIMARY KEY,
    tenant_id          TEXT NOT NULL,
    name               TEXT NOT NULL,
    description        TEXT,
    max_retries        INTEGER NOT NULL DEFAULT 3,
    accept_duplicates  INTEGER NOT NULL DEFAULT 0,
    created_at         TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_queues_tenant_name ON queues(tenant_id, name);

CREATE TABLE IF NOT EXISTS queue_items (
    id           TEXT PRIMARY KEY,
    tenant_id    TEXT NOT NULL,
    queue_name   TEXT NOT NULL,
    reference    TEXT,
    priority     TEXT NOT NULL DEFAULT 'Normal',
    status       TEXT NOT NULL DEFAULT 'NEW',
    content      TEXT,
    output       TEXT,
    exception    TEXT,
    retries      INTEGER NOT NULL DEFAULT 0,
    robot_name   TEXT,
    created_at   TEXT NOT NULL,
    started_at   TEXT,
    ended_at     TEXT
);
CREATE INDEX IF NOT EXISTS ix_queue_items_tenant_queue_status ON queue_items(tenant_id, queue_name, status);
CREATE INDEX IF NOT EXISTS ix_queue_items_tenant_created ON queue_items(tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS assets (
    id            TEXT PRIMARY KEY,
    tenant_id     TEXT NOT NULL,
    name          TEXT NOT NULL,
    type          TEXT NOT NULL DEFAULT 'Text',
    value_text    TEXT,
    description   TEXT,
    scope         TEXT NOT NULL DEFAULT 'Global',
    created_at    TEXT NOT NULL,
    updated_at    TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_assets_tenant_name ON assets(tenant_id, name);

CREATE TABLE IF NOT EXISTS credentials (
    id            TEXT PRIMARY KEY,
    tenant_id     TEXT NOT NULL,
    name          TEXT NOT NULL,
    username      TEXT,
    password_enc  TEXT,
    description   TEXT,
    created_at    TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_credentials_tenant_name ON credentials(tenant_id, name);

CREATE TABLE IF NOT EXISTS buckets (
    id           TEXT PRIMARY KEY,
    tenant_id    TEXT NOT NULL,
    name         TEXT NOT NULL,
    description  TEXT,
    created_at   TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_buckets_tenant_name ON buckets(tenant_id, name);

CREATE TABLE IF NOT EXISTS bucket_files (
    id            TEXT PRIMARY KEY,
    tenant_id     TEXT NOT NULL,
    bucket_name   TEXT NOT NULL,
    file_name     TEXT NOT NULL,
    content_type  TEXT,
    size_bytes    INTEGER NOT NULL DEFAULT 0,
    uploaded_by   TEXT,
    uploaded_at   TEXT NOT NULL,
    content       BLOB
);
CREATE INDEX IF NOT EXISTS ix_bucket_files_tenant_bucket ON bucket_files(tenant_id, bucket_name);

CREATE TABLE IF NOT EXISTS logs (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id     TEXT NOT NULL,
    level         TEXT NOT NULL DEFAULT 'INFO',
    message       TEXT NOT NULL,
    robot_name    TEXT,
    machine_name  TEXT,
    process_name  TEXT,
    job_id        TEXT,
    logged_at     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_logs_tenant_id_desc ON logs(tenant_id, id DESC);
CREATE INDEX IF NOT EXISTS ix_logs_tenant_level ON logs(tenant_id, level);

CREATE TABLE IF NOT EXISTS alerts (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id   TEXT NOT NULL,
    severity    TEXT NOT NULL DEFAULT 'Info',
    title       TEXT NOT NULL,
    message     TEXT,
    source      TEXT,
    is_read     INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_alerts_tenant_id_desc ON alerts(tenant_id, id DESC);
CREATE INDEX IF NOT EXISTS ix_alerts_tenant_unread ON alerts(tenant_id, is_read);

CREATE TABLE IF NOT EXISTS licenses (
    id          TEXT PRIMARY KEY,
    tenant_id   TEXT NOT NULL,
    product     TEXT NOT NULL,
    total       INTEGER NOT NULL DEFAULT 0,
    used        INTEGER NOT NULL DEFAULT 0,
    expires_at  TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_licenses_tenant_product ON licenses(tenant_id, product);
";
}
