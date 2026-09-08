using Microsoft.Data.Sqlite;
using ForgeHub.Auth;

namespace ForgeHub.Data;

/// <summary>
/// Isi awal basis data.
///
/// Yang ditanam di sini hanya KERANGKA: penyewa, pengguna pertama, peran,
/// lingkungan, mesin ini sendiri, satu antrean, dan baris lisensi. Robot,
/// proses, pekerjaan, dan log TIDAK dipalsukan — semuanya masuk dari Studio dan
/// JakRunner yang sungguhan.
///
/// Itu keputusan yang disengaja. Dasbor berisi data karangan terlihat lebih
/// meyakinkan pada menit pertama, lalu jadi menyesatkan pada menit kedua, saat
/// orang mengira angka di layar menggambarkan robot yang benar-benar berjalan.
/// </summary>
public static class Seed
{
    public const string DefaultTenant = "default";
    public const string DefaultUser = "FH_Admin";
    public const string DefaultPassword = "forgehub";

    public static void Apply(SqliteConnection connection)
    {
        var now = Sql.Now();

        var tenantId = (string)Sql.Scalar(connection,
            "SELECT id FROM tenants WHERE name = @p0", DefaultTenant);

        if (tenantId == null)
        {
            tenantId = Sql.NewId();
            Sql.Exec(connection,
                "INSERT INTO tenants (id, name, display_name, created_at) VALUES (@p0, @p1, @p2, @p3)",
                tenantId, DefaultTenant, "Default Tenant", now);
        }

        SeedUser(connection, tenantId, now);
        SeedRoles(connection, tenantId, now);
        SeedEnvironments(connection, tenantId, now);
        SeedThisMachine(connection, tenantId, now);
        SeedQueue(connection, tenantId, now);
        SeedBucket(connection, tenantId, now);
        SeedLicense(connection, tenantId);
    }

    public static string TenantId(SqliteConnection connection) =>
        (string)Sql.Scalar(connection, "SELECT id FROM tenants WHERE name = @p0", DefaultTenant);

    private static void SeedUser(SqliteConnection connection, string tenantId, string now)
    {
        var exists = Sql.Count(connection,
            "SELECT COUNT(*) FROM users WHERE tenant_id = @p0 AND username = @p1",
            tenantId, DefaultUser);

        if (exists > 0) return;

        Sql.Exec(connection,
            @"INSERT INTO users (id, tenant_id, username, password_hash, display_name, email, role, is_active, created_at)
              VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, 1, @p7)",
            Sql.NewId(), tenantId, DefaultUser, Passwords.Hash(DefaultPassword),
            "ForgeHub Administrator", null, "Administrator", now);
    }

    private static void SeedRoles(SqliteConnection connection, string tenantId, string now)
    {
        var roles = new (string Name, string Description, string Permissions)[]
        {
            ("Administrator", "Akses penuh ke seluruh ForgeHub.", "*"),
            ("Automation Developer", "Menerbitkan paket dan proses, menjalankan pekerjaan.",
                "packages.*,processes.*,jobs.create,jobs.read,logs.read,assets.read,queues.*"),
            ("Automation User", "Menjalankan proses yang sudah ada dan membaca hasilnya.",
                "jobs.create,jobs.read,processes.read,logs.read,queues.read"),
            ("Auditor", "Hanya membaca — untuk pemeriksaan dan pelaporan.",
                "*.read"),
        };

        foreach (var (name, description, permissions) in roles)
        {
            var exists = Sql.Count(connection,
                "SELECT COUNT(*) FROM roles WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            if (exists > 0) continue;

            Sql.Exec(connection,
                "INSERT INTO roles (id, tenant_id, name, description, permissions, created_at) VALUES (@p0, @p1, @p2, @p3, @p4, @p5)",
                Sql.NewId(), tenantId, name, description, permissions, now);
        }
    }

    private static void SeedEnvironments(SqliteConnection connection, string tenantId, string now)
    {
        var environments = new (string Name, string Description)[]
        {
            ("Development", "Tempat proses diuji sebelum dipakai sungguhan."),
            ("Staging", "Salinan mendekati produksi untuk uji terima."),
            ("Production", "Proses yang berjalan untuk pekerjaan sebenarnya."),
        };

        foreach (var (name, description) in environments)
        {
            var exists = Sql.Count(connection,
                "SELECT COUNT(*) FROM environments WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            if (exists > 0) continue;

            Sql.Exec(connection,
                "INSERT INTO environments (id, tenant_id, name, description, created_at) VALUES (@p0, @p1, @p2, @p3, @p4)",
                Sql.NewId(), tenantId, name, description, now);
        }
    }

    /// <summary>
    /// Mesin tempat ForgeHub berjalan didaftarkan sendiri.
    ///
    /// Tanpa ini, denyut pertama dari JakRunner tiba untuk mesin yang belum
    /// dikenal, dan halaman Machines kosong padahal jelas ada satu yang aktif.
    /// </summary>
    private static void SeedThisMachine(SqliteConnection connection, string tenantId, string now)
    {
        var name = System.Environment.MachineName;

        var exists = Sql.Count(connection,
            "SELECT COUNT(*) FROM machines WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

        if (exists > 0) return;

        Sql.Exec(connection,
            "INSERT INTO machines (id, tenant_id, name, type, description, created_at) VALUES (@p0, @p1, @p2, @p3, @p4, @p5)",
            Sql.NewId(), tenantId, name, "Standard", "Mesin tempat ForgeHub berjalan.", now);
    }

    private static void SeedQueue(SqliteConnection connection, string tenantId, string now)
    {
        var exists = Sql.Count(connection,
            "SELECT COUNT(*) FROM queues WHERE tenant_id = @p0", tenantId);

        if (exists > 0) return;

        Sql.Exec(connection,
            @"INSERT INTO queues (id, tenant_id, name, description, max_retries, accept_duplicates, created_at)
              VALUES (@p0, @p1, @p2, @p3, 3, 0, @p4)",
            Sql.NewId(), tenantId, "TransactionQueue",
            "Antrean bawaan yang dipakai template ReFramework.", now);
    }

    private static void SeedBucket(SqliteConnection connection, string tenantId, string now)
    {
        var exists = Sql.Count(connection,
            "SELECT COUNT(*) FROM buckets WHERE tenant_id = @p0", tenantId);

        if (exists > 0) return;

        Sql.Exec(connection,
            "INSERT INTO buckets (id, tenant_id, name, description, created_at) VALUES (@p0, @p1, @p2, @p3, @p4)",
            Sql.NewId(), tenantId, "Shared",
            "Berkas yang dipakai bersama oleh proses — masukan, keluaran, lampiran.", now);
    }

    private static void SeedLicense(SqliteConnection connection, string tenantId)
    {
        var exists = Sql.Count(connection,
            "SELECT COUNT(*) FROM licenses WHERE tenant_id = @p0", tenantId);

        if (exists > 0) return;

        // Pemasangan mandiri: tanpa batas dan tanpa masa berlaku. Barisnya tetap
        // ada supaya halaman Licensing punya sesuatu untuk ditampilkan dan
        // supaya bentuk datanya sudah siap kalau nanti ada pembatasan.
        Sql.Exec(connection,
            "INSERT INTO licenses (id, tenant_id, product, total, used, expires_at) VALUES (@p0, @p1, @p2, @p3, 0, NULL)",
            Sql.NewId(), tenantId, "JakForge Unattended", 0);

        Sql.Exec(connection,
            "INSERT INTO licenses (id, tenant_id, product, total, used, expires_at) VALUES (@p0, @p1, @p2, @p3, 0, NULL)",
            Sql.NewId(), tenantId, "JakForge Attended", 0);
    }
}
