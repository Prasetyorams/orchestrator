using ForgeHub.Auth;
using ForgeHub.Data;

namespace ForgeHub.Api;

public static class RobotsApi
{
    /// <summary>
    /// Berapa lama robot boleh diam sebelum dianggap putus.
    ///
    /// JakRunner berdenyut tiap 15 detik, jadi 45 detik memberi ruang dua denyut
    /// yang hilang sebelum menyatakannya putus — cukup longgar untuk jaringan
    /// yang tersendat sesaat, cukup ketat untuk ketahuan dalam satu menit.
    /// </summary>
    public const int OfflineAfterSeconds = 45;

    /// <summary>
    /// Keadaan robot yang SEBENARNYA, dihitung saat dibaca.
    ///
    /// Kolom status di basis data hanya menyimpan apa yang terakhir dilaporkan
    /// robot. Robot yang mati mendadak tidak sempat melaporkan apa pun, jadi
    /// nilai terakhirnya selamanya "AVAILABLE" kalau dipercaya begitu saja.
    /// Karena itu status dihitung dari waktu denyut terakhir, bukan dibaca mentah.
    /// </summary>
    private static readonly string StatusExpression = $@"
        CASE
            WHEN last_heartbeat_at IS NULL THEN 'DISCONNECTED'
            WHEN (julianday('now') - julianday(last_heartbeat_at)) * 86400.0 > {OfflineAfterSeconds}
                THEN 'DISCONNECTED'
            ELSE status
        END AS status";

    public static void MapRobots(this WebApplication app)
    {
        // --------------------------------------------------------------
        app.MapGet("/api/robots", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                $@"SELECT id, name, machine_name, username, type, environment, description,
                          {StatusExpression}, cpu_percent, memory_mb, last_heartbeat_at, created_at
                     FROM robots
                    WHERE tenant_id = @p0
                    ORDER BY name",
                tenantId);

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        app.MapGet("/api/robots/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var row = Sql.Row(connection,
                $@"SELECT id, name, machine_name, username, type, environment, description,
                          {StatusExpression}, cpu_percent, memory_mb, last_heartbeat_at, created_at
                     FROM robots
                    WHERE tenant_id = @p0 AND name = @p1",
                tenantId, name);

            return row == null
                ? Results.NotFound(new { error = "Robot '" + name + "' tidak ada." })
                : Results.Ok(row);
        });

        // --------------------------------------------------------------
        // Denyut. Inilah yang dipanggil JakRunner tiap 15 detik.
        //
        // Robot yang belum dikenal DIDAFTARKAN di sini, tidak ditolak. Kalau
        // pendaftaran harus dilakukan lebih dulu lewat dasbor, setiap pemasangan
        // JakRunner baru butuh langkah manual di tempat lain sebelum berguna.
        app.MapPost("/api/robots/{name}/heartbeat", async (HttpContext context, string name) =>
        {
            var body = await context.Body();

            var status = (body.Str("status", "AVAILABLE") ?? "AVAILABLE").ToUpperInvariant();
            var cpu = body.Num("cpuPercent");
            var memory = body.Num("memoryMb");
            var machine = body.Str("machineName");
            var now = Sql.Now();

            using var connection = context.Db(out var tenantId);

            var exists = Sql.Count(connection,
                "SELECT COUNT(*) FROM robots WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            if (exists == 0)
            {
                Sql.Exec(connection,
                    @"INSERT INTO robots
                        (id, tenant_id, name, machine_name, type, environment, description,
                         status, cpu_percent, memory_mb, last_heartbeat_at, created_at)
                      VALUES (@p0, @p1, @p2, @p3, 'Attended', 'Production',
                              'Terdaftar sendiri saat denyut pertama.', @p4, @p5, @p6, @p7, @p8)",
                    Sql.NewId(), tenantId, name, machine, status, cpu, memory, now, now);

                EnsureMachine(connection, tenantId, machine, now);

                ApiSupport.Alert(connection, tenantId, "Info",
                    "Robot baru terdaftar",
                    "Robot '" + name + "' menyambung untuk pertama kali.", "robots");
            }
            else
            {
                Sql.Exec(connection,
                    @"UPDATE robots
                         SET status = @p0, cpu_percent = @p1, memory_mb = @p2,
                             last_heartbeat_at = @p3,
                             machine_name = COALESCE(@p4, machine_name)
                       WHERE tenant_id = @p5 AND name = @p6",
                    status, cpu, memory, now, machine, tenantId, name);
            }

            return Results.Ok(new { ok = true, serverTime = now });
        });

        // --------------------------------------------------------------
        app.MapPost("/api/robots", async (HttpContext context) =>
        {
            var body = await context.Body();
            var name = body.Str("name");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama robot wajib diisi." });

            using var connection = context.Db(out var tenantId);

            var exists = Sql.Count(connection,
                "SELECT COUNT(*) FROM robots WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            if (exists > 0)
                return Results.Conflict(new { error = "Robot '" + name + "' sudah ada." });

            var now = Sql.Now();
            var machine = body.Str("machineName");

            Sql.Exec(connection,
                @"INSERT INTO robots
                    (id, tenant_id, name, machine_name, username, type, environment, description,
                     status, cpu_percent, memory_mb, last_heartbeat_at, created_at)
                  VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, @p7, 'DISCONNECTED', 0, 0, NULL, @p8)",
                Sql.NewId(), tenantId, name, machine, body.Str("username"),
                body.Str("type", "Unattended"), body.Str("environment", "Production"),
                body.Str("description"), now);

            EnsureMachine(connection, tenantId, machine, now);

            return Results.Ok(new { ok = true });
        });

        // --------------------------------------------------------------
        app.MapDelete("/api/robots/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM robots WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            return removed == 0
                ? Results.NotFound(new { error = "Robot '" + name + "' tidak ada." })
                : Results.Ok(new { ok = true });
        });

        MapMachines(app);
        MapEnvironments(app);
        MapCredentials(app);
    }

    // ------------------------------------------------------------------

    private static void MapMachines(WebApplication app)
    {
        app.MapGet("/api/machines", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT m.id, m.name, m.type, m.description, m.created_at,
                         (SELECT COUNT(*) FROM robots r
                           WHERE r.tenant_id = m.tenant_id AND r.machine_name = m.name) AS robot_count
                    FROM machines m
                   WHERE m.tenant_id = @p0
                   ORDER BY m.name",
                tenantId);

            return Results.Ok(rows);
        });

        app.MapPost("/api/machines", async (HttpContext context) =>
        {
            var body = await context.Body();
            var name = body.Str("name");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama mesin wajib diisi." });

            using var connection = context.Db(out var tenantId);

            if (Sql.Count(connection,
                    "SELECT COUNT(*) FROM machines WHERE tenant_id = @p0 AND name = @p1", tenantId, name) > 0)
                return Results.Conflict(new { error = "Mesin '" + name + "' sudah ada." });

            Sql.Exec(connection,
                "INSERT INTO machines (id, tenant_id, name, type, description, created_at) VALUES (@p0, @p1, @p2, @p3, @p4, @p5)",
                Sql.NewId(), tenantId, name, body.Str("type", "Standard"), body.Str("description"), Sql.Now());

            return Results.Ok(new { ok = true });
        });

        app.MapDelete("/api/machines/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM machines WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            return removed == 0 ? Results.NotFound(new { error = "Mesin tidak ada." }) : Results.Ok(new { ok = true });
        });
    }

    private static void MapEnvironments(WebApplication app)
    {
        app.MapGet("/api/environments", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT e.id, e.name, e.description, e.created_at,
                         (SELECT COUNT(*) FROM robots r
                           WHERE r.tenant_id = e.tenant_id AND r.environment = e.name) AS robot_count
                    FROM environments e
                   WHERE e.tenant_id = @p0
                   ORDER BY e.name",
                tenantId);

            return Results.Ok(rows);
        });

        app.MapPost("/api/environments", async (HttpContext context) =>
        {
            var body = await context.Body();
            var name = body.Str("name");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama lingkungan wajib diisi." });

            using var connection = context.Db(out var tenantId);

            if (Sql.Count(connection,
                    "SELECT COUNT(*) FROM environments WHERE tenant_id = @p0 AND name = @p1", tenantId, name) > 0)
                return Results.Conflict(new { error = "Lingkungan '" + name + "' sudah ada." });

            Sql.Exec(connection,
                "INSERT INTO environments (id, tenant_id, name, description, created_at) VALUES (@p0, @p1, @p2, @p3, @p4)",
                Sql.NewId(), tenantId, name, body.Str("description"), Sql.Now());

            return Results.Ok(new { ok = true });
        });

        app.MapDelete("/api/environments/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM environments WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            return removed == 0 ? Results.NotFound(new { error = "Lingkungan tidak ada." }) : Results.Ok(new { ok = true });
        });
    }

    private static void MapCredentials(WebApplication app)
    {
        // Nilai sandinya TIDAK pernah ikut dalam daftar. Halaman yang menampilkan
        // seluruh kata sandi sekaligus mengubah satu layar yang terlihat sekilas
        // menjadi kebocoran seluruh isi lemari.
        app.MapGet("/api/credentials", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                "SELECT id, name, username, description, created_at FROM credentials WHERE tenant_id = @p0 ORDER BY name",
                tenantId);

            return Results.Ok(rows);
        });

        app.MapPost("/api/credentials", async (HttpContext context) =>
        {
            var body = await context.Body();
            var name = body.Str("name");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama kredensial wajib diisi." });

            using var connection = context.Db(out var tenantId);

            var encrypted = Secrets.Protect(body.Str("password"));

            if (Sql.Count(connection,
                    "SELECT COUNT(*) FROM credentials WHERE tenant_id = @p0 AND name = @p1", tenantId, name) > 0)
            {
                Sql.Exec(connection,
                    "UPDATE credentials SET username = @p0, password_enc = @p1, description = @p2 WHERE tenant_id = @p3 AND name = @p4",
                    body.Str("username"), encrypted, body.Str("description"), tenantId, name);
            }
            else
            {
                Sql.Exec(connection,
                    "INSERT INTO credentials (id, tenant_id, name, username, password_enc, description, created_at) VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6)",
                    Sql.NewId(), tenantId, name, body.Str("username"), encrypted, body.Str("description"), Sql.Now());
            }

            return Results.Ok(new { ok = true });
        });

        // Pengambilan nilai dilakukan satu per satu dan atas permintaan robot
        // yang menjalankan proses — bukan sebagai bagian dari daftar.
        app.MapGet("/api/credentials/{name}/value", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var row = Sql.Row(connection,
                "SELECT username, password_enc FROM credentials WHERE tenant_id = @p0 AND name = @p1",
                tenantId, name);

            if (row == null) return Results.NotFound(new { error = "Kredensial tidak ada." });

            return Results.Ok(new
            {
                username = row["username"],
                password = Secrets.Unprotect((string)row["passwordEnc"]),
            });
        });

        app.MapDelete("/api/credentials/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM credentials WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            return removed == 0 ? Results.NotFound(new { error = "Kredensial tidak ada." }) : Results.Ok(new { ok = true });
        });
    }

    // ------------------------------------------------------------------

    /// <summary>
    /// Mesin yang disebut robot didaftarkan kalau belum ada, supaya halaman
    /// Machines tidak pernah ketinggalan dari kenyataan.
    /// </summary>
    private static void EnsureMachine(Microsoft.Data.Sqlite.SqliteConnection connection,
        string tenantId, string machine, string now)
    {
        if (string.IsNullOrWhiteSpace(machine)) return;

        if (Sql.Count(connection,
                "SELECT COUNT(*) FROM machines WHERE tenant_id = @p0 AND name = @p1", tenantId, machine) > 0)
            return;

        Sql.Exec(connection,
            "INSERT INTO machines (id, tenant_id, name, type, description, created_at) VALUES (@p0, @p1, @p2, 'Standard', @p3, @p4)",
            Sql.NewId(), tenantId, machine, "Terdaftar sendiri lewat denyut robot.", now);
    }
}
