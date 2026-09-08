using System.Security.Cryptography;
using System.Text;

namespace ForgeHub.Auth;

/// <summary>
/// Penyandian nilai yang harus bisa dibaca kembali: kata sandi kredensial dan
/// aset bertipe rahasia.
///
/// Berbeda dari kata sandi pengguna, yang ini TIDAK boleh sekadar diringkas —
/// robot perlu nilai aslinya untuk masuk ke aplikasi yang diotomasi. Jadi
/// dipakai AES-GCM: satu kunci, dan setiap nilai punya nonce sendiri.
///
/// Kuncinya diturunkan dari berkas signing.key yang sama dengan penanda tangan
/// token, lewat HKDF dengan label berbeda. Satu berkas rahasia untuk dijaga,
/// bukan dua, tapi kunci tanda tangan dan kunci penyandian tetap tidak sama —
/// bocornya salah satu tidak otomatis membocorkan yang lain.
/// </summary>
public static class Secrets
{
    private const int NonceBytes = 12;   // ukuran baku AES-GCM
    private const int TagBytes = 16;

    private static byte[] _key;

    public static void Init(string dataDirectory)
    {
        var path = Path.Combine(dataDirectory, "signing.key");
        var master = Convert.FromBase64String(File.ReadAllText(path).Trim());

        _key = HKDF.DeriveKey(
            HashAlgorithmName.SHA256,
            ikm: master,
            outputLength: 32,
            info: Encoding.UTF8.GetBytes("forgehub-secret-box-v1"));
    }

    public static string Protect(string value)
    {
        if (string.IsNullOrEmpty(value)) return null;

        var plain = Encoding.UTF8.GetBytes(value);
        var nonce = RandomNumberGenerator.GetBytes(NonceBytes);
        var cipher = new byte[plain.Length];
        var tag = new byte[TagBytes];

        using (var aes = new AesGcm(_key, TagBytes))
            aes.Encrypt(nonce, plain, cipher, tag);

        // nonce | tag | ciphertext, semuanya dalam satu untai base64.
        var packed = new byte[NonceBytes + TagBytes + cipher.Length];
        Buffer.BlockCopy(nonce, 0, packed, 0, NonceBytes);
        Buffer.BlockCopy(tag, 0, packed, NonceBytes, TagBytes);
        Buffer.BlockCopy(cipher, 0, packed, NonceBytes + TagBytes, cipher.Length);

        return Convert.ToBase64String(packed);
    }

    public static string Unprotect(string packedBase64)
    {
        if (string.IsNullOrEmpty(packedBase64)) return null;

        try
        {
            var packed = Convert.FromBase64String(packedBase64);
            if (packed.Length < NonceBytes + TagBytes) return null;

            var nonce = new byte[NonceBytes];
            var tag = new byte[TagBytes];
            var cipher = new byte[packed.Length - NonceBytes - TagBytes];

            Buffer.BlockCopy(packed, 0, nonce, 0, NonceBytes);
            Buffer.BlockCopy(packed, NonceBytes, tag, 0, TagBytes);
            Buffer.BlockCopy(packed, NonceBytes + TagBytes, cipher, 0, cipher.Length);

            var plain = new byte[cipher.Length];

            using (var aes = new AesGcm(_key, TagBytes))
                aes.Decrypt(nonce, cipher, tag, plain);

            return Encoding.UTF8.GetString(plain);
        }
        catch (Exception)
        {
            // Nilai yang rusak atau disandikan dengan kunci lain: dianggap tidak ada.
            return null;
        }
    }
}
