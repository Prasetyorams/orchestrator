using ForgeHub.Auth;
using ForgeHub.Data;

namespace ForgeHub.Api;

public static class AdminApi
{
    public static void MapAdmin(this WebApplication app)
    {
        // --------------------------------------------------------------
        app.MapGet("/api/tenants", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT t.id, t.name, t.display_name, t.created_at,
                         (SELECT COUNT(*) FROM users u WHERE u.tenant_id = t.id) AS user_count,
                         (SELECT COUNT(*) FROM robots r WHERE r.tenant_id = t.id) AS robot_count
                    FROM tenants t
                   ORDER BY t.name");

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        // Ringkasan pengguna. Ringkasan kata sandi TIDAK pernah ikut, bahkan
        // dalam bentuk teracaknya: ringkasan yang bocor masih bisa ditebak
        // di luar sini tanpa batas percobaan.
        app.MapGet("/api/users", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT id, username, display_name, email, role, is_active, created_at, last_login_at
                    FROM users
                   WHERE tenant_id = @p0
                   ORDER BY username",
                tenantId);

            return Results.Ok(rows);
        });

        app.MapPost("/api/users", async (HttpContext context) =>
        {
            if (!IsAdministrator(context))
                return Forbidden("Hanya Administrator yang boleh mengubah pengguna.");

            var body = await context.Body();

            var username = body.Str("username");
            var password = body.Str("password");

            if (string.IsNullOrWhiteSpace(username))
                return Results.BadRequest(new { error = "Nama pengguna wajib diisi." });

            using var connection = context.Db(out var tenantId);

            var existing = (string)Sql.Scalar(connection,
                "SELECT id FROM users WHERE tenant_id = @p0 AND username = @p1", tenantId, username);

            if (existing != null)
            {
                Sql.Exec(connection,
                    @"UPDATE users
                         SET display_name = COALESCE(@p0, display_name),
                             email = COALESCE(@p1, email),
                             role = COALESCE(@p2, role),
                             is_active = @p3
                       WHERE id = @p4",
                    body.Str("displayName"), body.Str("email"), body.Str("role"),
                    body.Flag("isActive", true) ? 1 : 0, existing);

                // Kata sandi hanya diganti kalau memang dikirim. Tanpa syarat ini,
                // menyunting alamat surel akan diam-diam mengosongkan sandinya.
                if (!string.IsNullOrEmpty(password))
                {
                    Sql.Exec(connection, "UPDATE users SET password_hash = @p0 WHERE id = @p1",
                        Passwords.Hash(password), existing);
                }

                return Results.Ok(new { ok = true, created = false });
            }

            if (string.IsNullOrEmpty(password) || password.Length < 6)
                return Results.BadRequest(new { error = "Pengguna baru butuh kata sandi minimal 6 karakter." });

            Sql.Exec(connection,
                @"INSERT INTO users (id, tenant_id, username, password_hash, display_name, email, role, is_active, created_at)
                  VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, @p7, @p8)",
                Sql.NewId(), tenantId, username, Passwords.Hash(password),
                body.Str("displayName", username), body.Str("email"),
                body.Str("role", "Automation User"), body.Flag("isActive", true) ? 1 : 0, Sql.Now());

            return Results.Ok(new { ok = true, created = true });
        });

        app.MapDelete("/api/users/{username}", (HttpContext context, string username) =>
        {
            if (!IsAdministrator(context))
                return Forbidden("Hanya Administrator yang boleh menghapus pengguna.");

            // Menghapus diri sendiri akan mengunci orangnya keluar dari ForgeHub
            // miliknya sendiri, dan tidak ada jalan masuk lain untuk membatalkannya.
            if (string.Equals(username, context.User()?.Username, StringComparison.OrdinalIgnoreCase))
                return Results.BadRequest(new { error = "Tidak bisa menghapus akun yang sedang dipakai." });

            using var connection = context.Db(out var tenantId);

            // Penyewa tanpa satu pun administrator aktif tidak bisa diurus lagi.
            var admins = Sql.Count(connection,
                "SELECT COUNT(*) FROM users WHERE tenant_id = @p0 AND role = 'Administrator' AND is_active = 1",
                tenantId);

            var isAdmin = Sql.Count(connection,
                "SELECT COUNT(*) FROM users WHERE tenant_id = @p0 AND username = @p1 AND role = 'Administrator'",
                tenantId, username) > 0;

            if (isAdmin && admins <= 1)
                return Results.BadRequest(new { error = "Ini satu-satunya Administrator yang tersisa." });

            var removed = Sql.Exec(connection,
                "DELETE FROM users WHERE tenant_id = @p0 AND username = @p1", tenantId, username);

            return removed == 0 ? Results.NotFound(new { error = "Pengguna tidak ada." }) : Results.Ok(new { ok = true });
        });

        // --------------------------------------------------------------
        app.MapGet("/api/roles", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT r.id, r.name, r.description, r.permissions, r.created_at,
                         (SELECT COUNT(*) FROM users u
                           WHERE u.tenant_id = r.tenant_id AND u.role = r.name) AS user_count
                    FROM roles r
                   WHERE r.tenant_id = @p0
                   ORDER BY r.name",
                tenantId);

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        app.MapGet("/api/licensing", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                "SELECT id, product, total, used, expires_at FROM licenses WHERE tenant_id = @p0 ORDER BY product",
                tenantId);

            // "Terpakai" dihitung dari robot yang benar-benar ada, bukan dari
            // angka yang pernah dituliskan seseorang ke kolom used.
            var attended = Sql.Count(connection,
                "SELECT COUNT(*) FROM robots WHERE tenant_id = @p0 AND type = 'Attended'", tenantId);

            var unattended = Sql.Count(connection,
                "SELECT COUNT(*) FROM robots WHERE tenant_id = @p0 AND type <> 'Attended'", tenantId);

            foreach (var row in rows)
            {
                var product = (string)row["product"] ?? "";
                row["used"] = product.Contains("Attended") && !product.Contains("Unattended")
                    ? attended : unattended;
            }

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        app.MapGet("/api/settings", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            return Results.Ok(new
            {
                tenant = context.User()?.TenantName,
                serverTime = Sql.Now(),
                machineName = System.Environment.MachineName,
                robotOfflineAfterSeconds = RobotsApi.OfflineAfterSeconds,
                tokenLifetimeHours = (int)Tokens.Lifetime.TotalHours,
                dataDirectory = Program.DataDirectory,
                counts = new
                {
                    users = Sql.Count(connection, "SELECT COUNT(*) FROM users WHERE tenant_id = @p0", tenantId),
                    robots = Sql.Count(connection, "SELECT COUNT(*) FROM robots WHERE tenant_id = @p0", tenantId),
                    processes = Sql.Count(connection, "SELECT COUNT(*) FROM processes WHERE tenant_id = @p0", tenantId),
                    jobs = Sql.Count(connection, "SELECT COUNT(*) FROM jobs WHERE tenant_id = @p0", tenantId),
                    logs = Sql.Count(connection, "SELECT COUNT(*) FROM logs WHERE tenant_id = @p0", tenantId),
                },
            });
        });
    }

    // ------------------------------------------------------------------

    private static bool IsAdministrator(HttpContext context) =>
        context.User()?.Role == "Administrator";

    private static IResult Forbidden(string message) =>
        Results.Json(new { error = message }, statusCode: StatusCodes.Status403Forbidden);
}
