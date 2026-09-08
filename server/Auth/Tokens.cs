using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace ForgeHub.Auth;

/// <summary>Siapa yang sedang meminta, hasil pembacaan token.</summary>
public sealed class Principal
{
    public string UserId { get; init; }
    public string Username { get; init; }
    public string TenantId { get; init; }
    public string TenantName { get; init; }
    public string Role { get; init; }
    public string DisplayName { get; init; }
}

/// <summary>
/// Token bertanda tangan berbentuk JWT (HS256).
///
/// Ditulis sendiri, bukan memakai pustaka JWT, karena yang dibutuhkan hanya
/// satu algoritma dan satu kunci: menandatangani dan memeriksa HS256. Pustaka
/// JWT umum membawa serta penguraian banyak algoritma lain — termasuk "none" —
/// dan yang paling sering jadi lubang keamanan justru bagian itu.
///
/// Kuncinya dibuat acak saat ForgeHub pertama kali dijalankan dan disimpan di
/// sebelah basis data. Kunci yang ditanam di kode akan sama di semua pemasangan,
/// dan token buatan satu orang akan berlaku di ForgeHub milik orang lain.
/// </summary>
public static class Tokens
{
    private static byte[] _key;
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = null };

    /// <summary>Berapa lama token berlaku. Robot menyambung ulang sendiri.</summary>
    public static readonly TimeSpan Lifetime = TimeSpan.FromHours(12);

    public static void Init(string dataDirectory)
    {
        var path = Path.Combine(dataDirectory, "signing.key");

        if (File.Exists(path))
        {
            _key = Convert.FromBase64String(File.ReadAllText(path).Trim());
            if (_key.Length >= 32) return;
        }

        _key = RandomNumberGenerator.GetBytes(64);
        File.WriteAllText(path, Convert.ToBase64String(_key));
    }

    public static string Issue(Principal principal)
    {
        var header = new Dictionary<string, object> { ["alg"] = "HS256", ["typ"] = "JWT" };

        var payload = new Dictionary<string, object>
        {
            ["sub"] = principal.UserId,
            ["usr"] = principal.Username,
            ["tid"] = principal.TenantId,
            ["tnm"] = principal.TenantName,
            ["rol"] = principal.Role,
            ["nam"] = principal.DisplayName,
            ["exp"] = DateTimeOffset.UtcNow.Add(Lifetime).ToUnixTimeSeconds(),
        };

        var signingInput =
            Encode(JsonSerializer.SerializeToUtf8Bytes(header, Json)) + "." +
            Encode(JsonSerializer.SerializeToUtf8Bytes(payload, Json));

        return signingInput + "." + Encode(Sign(signingInput));
    }

    /// <summary>Kembalikan pemilik token, atau null kalau tokennya tidak sah.</summary>
    public static Principal Read(string token)
    {
        if (string.IsNullOrWhiteSpace(token)) return null;

        var parts = token.Split('.');
        if (parts.Length != 3) return null;

        try
        {
            var signingInput = parts[0] + "." + parts[1];

            // Tanda tangan diperiksa SEBELUM isinya diurai. Isi token yang belum
            // terbukti asli tidak boleh dijadikan dasar keputusan apa pun.
            if (!CryptographicOperations.FixedTimeEquals(Sign(signingInput), Decode(parts[2])))
                return null;

            using var document = JsonDocument.Parse(Decode(parts[1]));
            var root = document.RootElement;

            var expiry = root.GetProperty("exp").GetInt64();
            if (DateTimeOffset.UtcNow.ToUnixTimeSeconds() >= expiry) return null;

            return new Principal
            {
                UserId = Text(root, "sub"),
                Username = Text(root, "usr"),
                TenantId = Text(root, "tid"),
                TenantName = Text(root, "tnm"),
                Role = Text(root, "rol"),
                DisplayName = Text(root, "nam"),
            };
        }
        catch (Exception)
        {
            // Token cacat bentuknya sama saja dengan token palsu: ditolak diam-diam.
            return null;
        }
    }

    private static string Text(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) ? value.GetString() : null;

    private static byte[] Sign(string input)
    {
        using var hmac = new HMACSHA256(_key);
        return hmac.ComputeHash(Encoding.UTF8.GetBytes(input));
    }

    private static string Encode(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] Decode(string text)
    {
        var padded = text.Replace('-', '+').Replace('_', '/');
        padded = padded.PadRight(padded.Length + (4 - padded.Length % 4) % 4, '=');
        return Convert.FromBase64String(padded);
    }
}
