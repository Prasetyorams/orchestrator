using System.Text.Json;
using Microsoft.Data.Sqlite;
using ForgeHub.Auth;
using ForgeHub.Data;

namespace ForgeHub.Api;

/// <summary>
/// Hal-hal yang dipakai semua endpoint: siapa peminta, penyewa mana, dan cara
/// membaca isi permintaan dengan aman.
/// </summary>
public static class ApiSupport
{
    private const string PrincipalKey = "forgehub.principal";

    /// <summary>
    /// Middleware pemeriksa token.
    ///
    /// Semua jalur di bawah /api WAJIB membawa token, kecuali /api/auth/login
    /// dan /api/health — dua-duanya harus bisa dicapai sebelum ada token.
    /// Daftarnya berupa IZIN, bukan larangan: endpoint baru yang lupa didaftar
    /// akan tertutup, bukan terbuka.
    /// </summary>
    public static void UseForgeHubAuth(this WebApplication app)
    {
        app.Use(async (context, next) =>
        {
            var path = context.Request.Path.Value ?? "";

            if (!path.StartsWith("/api", StringComparison.OrdinalIgnoreCase))
            {
                await next();
                return;
            }

            var open =
                path.Equals("/api/auth/login", StringComparison.OrdinalIgnoreCase) ||
                path.Equals("/api/health", StringComparison.OrdinalIgnoreCase);

            if (open)
            {
                await next();
                return;
            }

            var header = context.Request.Headers.Authorization.ToString();
            var token = header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)
                ? header[7..].Trim()
                : null;

            var principal = Tokens.Read(token);

            if (principal == null)
            {
                context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                await context.Response.WriteAsJsonAsync(new { error = "Token tidak sah atau sudah kedaluwarsa." });
                return;
            }

            context.Items[PrincipalKey] = principal;
            await next();
        });
    }

    public static Principal User(this HttpContext context) =>
        context.Items.TryGetValue(PrincipalKey, out var value) ? (Principal)value : null;

    public static string TenantId(this HttpContext context) => context.User()?.TenantId;

    // ------------------------------------------------------------------
    // Membaca isi permintaan
    // ------------------------------------------------------------------

    /// <summary>
    /// Baca badan permintaan sebagai JsonElement.
    ///
    /// Endpoint di sini menerima bentuk yang longgar — Studio, JakRunner, dan
    /// dasbor mengirim susunan yang sedikit berbeda untuk hal yang sama — jadi
    /// pembacaannya per-medan dengan nilai bawaan, bukan pemetaan kaku ke kelas
    /// yang gagal seluruhnya begitu ada satu medan tak dikenal.
    /// </summary>
    public static async Task<JsonElement> Body(this HttpContext context)
    {
        try
        {
            using var document = await JsonDocument.ParseAsync(context.Request.Body);
            return document.RootElement.Clone();
        }
        catch (Exception)
        {
            return default;
        }
    }

    public static string Str(this JsonElement element, string name, string fallback = null)
    {
        if (element.ValueKind != JsonValueKind.Object) return fallback;
        if (!element.TryGetProperty(name, out var value)) return fallback;

        return value.ValueKind switch
        {
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Null or JsonValueKind.Undefined => fallback,
            _ => value.ToString(),
        };
    }

    public static double Num(this JsonElement element, string name, double fallback = 0)
    {
        if (element.ValueKind != JsonValueKind.Object) return fallback;
        if (!element.TryGetProperty(name, out var value)) return fallback;

        if (value.ValueKind == JsonValueKind.Number) return value.GetDouble();
        if (value.ValueKind == JsonValueKind.String && double.TryParse(value.GetString(), out var parsed))
            return parsed;

        return fallback;
    }

    public static bool Flag(this JsonElement element, string name, bool fallback = false)
    {
        if (element.ValueKind != JsonValueKind.Object) return fallback;
        if (!element.TryGetProperty(name, out var value)) return fallback;

        return value.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.Number => value.GetDouble() != 0,
            _ => fallback,
        };
    }

    public static int IntQuery(this HttpContext context, string name, int fallback, int max = 1000)
    {
        var raw = context.Request.Query[name].ToString();
        if (!int.TryParse(raw, out var value)) return fallback;

        return Math.Clamp(value, 1, max);
    }

    // ------------------------------------------------------------------

    /// <summary>Buka koneksi dan sediakan tenant peminta dalam satu langkah.</summary>
    public static SqliteConnection Db(this HttpContext context, out string tenantId)
    {
        tenantId = context.TenantId();
        return Data.Db.Open();
    }

    /// <summary>Catat kejadian penting supaya muncul di umpan peringatan dasbor.</summary>
    public static void Alert(SqliteConnection connection, string tenantId,
        string severity, string title, string message, string source)
    {
        Sql.Exec(connection,
            @"INSERT INTO alerts (tenant_id, severity, title, message, source, is_read, created_at)
              VALUES (@p0, @p1, @p2, @p3, @p4, 0, @p5)",
            tenantId, severity, title, message, source, Sql.Now());
    }
}
