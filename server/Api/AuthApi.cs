using ForgeHub.Auth;
using ForgeHub.Data;

namespace ForgeHub.Api;

public static class AuthApi
{
    public static void MapAuth(this WebApplication app)
    {
        app.MapGet("/api/health", () => Results.Ok(new
        {
            product = "ForgeHub",
            status = "OK",
            time = Sql.Now(),
        }));

        // --------------------------------------------------------------
        app.MapPost("/api/auth/login", async (HttpContext context) =>
        {
            var body = await context.Body();
            var username = body.Str("username");
            var password = body.Str("password");

            if (string.IsNullOrWhiteSpace(username))
                return Results.BadRequest(new { error = "Nama pengguna wajib diisi." });

            using var connection = Db.Open();

            var user = Sql.Row(connection,
                @"SELECT u.id, u.username, u.password_hash, u.display_name, u.role, u.is_active,
                         t.id AS tenant_id, t.name AS tenant_name
                    FROM users u
                    JOIN tenants t ON t.id = u.tenant_id
                   WHERE u.username = @p0",
                username);

            // Pesan yang sama untuk pengguna tak dikenal dan kata sandi salah.
            // Pesan yang berbeda memberi tahu penebak nama mana yang benar-benar ada.
            var invalid = Results.Json(
                new { error = "Nama pengguna atau kata sandi salah." },
                statusCode: StatusCodes.Status401Unauthorized);

            if (user == null) return invalid;
            if (Convert.ToInt64(user["isActive"]) == 0) return invalid;
            if (!Passwords.Verify(password, (string)user["passwordHash"])) return invalid;

            var principal = new Principal
            {
                UserId = (string)user["id"],
                Username = (string)user["username"],
                TenantId = (string)user["tenantId"],
                TenantName = (string)user["tenantName"],
                Role = (string)user["role"],
                DisplayName = (string)user["displayName"],
            };

            Sql.Exec(connection, "UPDATE users SET last_login_at = @p0 WHERE id = @p1",
                Sql.Now(), principal.UserId);

            return Results.Ok(new
            {
                token = Tokens.Issue(principal),
                expiresInSeconds = (int)Tokens.Lifetime.TotalSeconds,
                user = new
                {
                    principal.Username,
                    principal.DisplayName,
                    principal.Role,
                    tenant = principal.TenantName,
                },
            });
        });

        // --------------------------------------------------------------
        app.MapGet("/api/auth/me", (HttpContext context) =>
        {
            var principal = context.User();

            return Results.Ok(new
            {
                principal.Username,
                principal.DisplayName,
                principal.Role,
                tenant = principal.TenantName,
            });
        });

        // --------------------------------------------------------------
        app.MapPost("/api/auth/password", async (HttpContext context) =>
        {
            var body = await context.Body();
            var current = body.Str("currentPassword");
            var next = body.Str("newPassword");

            if (string.IsNullOrEmpty(next) || next.Length < 6)
                return Results.BadRequest(new { error = "Kata sandi baru minimal 6 karakter." });

            using var connection = Db.Open();

            var stored = (string)Sql.Scalar(connection,
                "SELECT password_hash FROM users WHERE id = @p0", context.User().UserId);

            if (!Passwords.Verify(current, stored))
                return Results.BadRequest(new { error = "Kata sandi sekarang salah." });

            Sql.Exec(connection, "UPDATE users SET password_hash = @p0 WHERE id = @p1",
                Passwords.Hash(next), context.User().UserId);

            return Results.Ok(new { ok = true });
        });
    }
}
