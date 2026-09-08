using System.Security.Cryptography;

namespace ForgeHub.Auth;

/// <summary>
/// Penyimpanan kata sandi.
///
/// PBKDF2-SHA256 dengan 210.000 putaran — angka yang direkomendasikan OWASP
/// untuk PBKDF2-HMAC-SHA256. Sengaja memakai yang ada di .NET, bukan menarik
/// pustaka BCrypt: satu ketergantungan lebih sedikit untuk sesuatu yang sudah
/// disediakan kerangka kerjanya dengan benar.
///
/// Perbandingannya memakai FixedTimeEquals. Perbandingan biasa berhenti pada
/// bita pertama yang berbeda, dan selisih waktunya — walau kecil — cukup untuk
/// menebak nilai yang benar bita demi bita.
/// </summary>
public static class Passwords
{
    private const int Iterations = 210_000;
    private const int SaltBytes = 16;
    private const int HashBytes = 32;

    public static string Hash(string password)
    {
        var salt = RandomNumberGenerator.GetBytes(SaltBytes);

        var hash = Rfc2898DeriveBytes.Pbkdf2(
            password ?? "", salt, Iterations, HashAlgorithmName.SHA256, HashBytes);

        return string.Join("$", "pbkdf2-sha256", Iterations,
            Convert.ToBase64String(salt), Convert.ToBase64String(hash));
    }

    public static bool Verify(string password, string stored)
    {
        if (string.IsNullOrEmpty(stored)) return false;

        var parts = stored.Split('$');
        if (parts.Length != 4 || parts[0] != "pbkdf2-sha256") return false;

        if (!int.TryParse(parts[1], out var iterations)) return false;

        byte[] salt, expected;
        try
        {
            salt = Convert.FromBase64String(parts[2]);
            expected = Convert.FromBase64String(parts[3]);
        }
        catch (FormatException)
        {
            return false;
        }

        var actual = Rfc2898DeriveBytes.Pbkdf2(
            password ?? "", salt, iterations, HashAlgorithmName.SHA256, expected.Length);

        return CryptographicOperations.FixedTimeEquals(actual, expected);
    }
}
