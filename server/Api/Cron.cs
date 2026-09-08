namespace ForgeHub.Api;

/// <summary>
/// Penilai ekspresi cron lima ruas: menit, jam, tanggal, bulan, hari.
///
///     * * * * *
///     │ │ │ │ └── hari dalam minggu (0-6, 0 = Minggu; 7 juga Minggu)
///     │ │ │ └──── bulan (1-12)
///     │ │ └────── tanggal (1-31)
///     │ └──────── jam (0-23)
///     └────────── menit (0-59)
///
/// Yang didukung: <c>*</c>, angka, daftar <c>1,15</c>, rentang <c>1-5</c>,
/// dan langkah <c>*/15</c> atau <c>0-30/10</c>.
///
/// Ditulis sendiri, bukan menarik pustaka: yang dibutuhkan hanya "kapan
/// berikutnya", ekspresinya lima ruas, dan aturannya sudah baku sejak puluhan
/// tahun. Menambah paket untuk seratus baris yang bisa diuji tuntas bukan
/// pertukaran yang masuk akal.
///
/// SATU aturan yang mudah terlewat: kalau TANGGAL dan HARI dua-duanya dibatasi,
/// yang berlaku adalah SALAH SATU cocok, bukan keduanya. Itu perilaku cron asli
/// (Vixie), dan orang yang menulis "0 0 1 * 1" mengharapkan tanggal 1 ATAU
/// setiap Senin, bukan tanggal 1 yang kebetulan Senin.
/// </summary>
public static class Cron
{
    /// <summary>Benar kalau ekspresinya bisa dipakai.</summary>
    public static bool IsValid(string expression)
    {
        return Parse(expression) != null;
    }

    /// <summary>
    /// Waktu berjalan berikutnya SESUDAH <paramref name="after"/> (UTC),
    /// atau null kalau ekspresinya tidak sah atau tidak pernah cocok.
    /// </summary>
    public static DateTime? Next(string expression, DateTime after)
    {
        var field = Parse(expression);
        if (field == null) return null;

        // Mulai dari menit BERIKUTNYA: pemicu yang baru saja berjalan pada
        // menit ini tidak boleh langsung berjalan lagi di menit yang sama.
        var t = new DateTime(after.Year, after.Month, after.Day, after.Hour, after.Minute, 0, DateTimeKind.Utc)
                .AddMinutes(1);

        // Empat tahun cukup untuk menutup 29 Februari pada ekspresi yang paling
        // jarang sekalipun. Kalau dalam rentang itu tidak ada yang cocok,
        // ekspresinya memang tidak akan pernah cocok.
        var batas = t.AddYears(4);

        while (t < batas)
        {
            if (!field[3].Contains(t.Month)) { t = new DateTime(t.Year, t.Month, 1, 0, 0, 0, DateTimeKind.Utc).AddMonths(1); continue; }
            if (!HariCocok(field, t)) { t = t.Date.AddDays(1); continue; }
            if (!field[1].Contains(t.Hour)) { t = t.Date.AddHours(t.Hour + 1); continue; }
            if (!field[0].Contains(t.Minute)) { t = t.AddMinutes(1); continue; }

            return t;
        }

        return null;
    }

    /// <summary>
    /// Tanggal dan hari: kalau KEDUANYA dibatasi, cukup salah satu cocok.
    /// </summary>
    private static bool HariCocok(HashSet<int>[] field, DateTime t)
    {
        var tanggalDibatasi = field[2].Count < 31;
        var hariDibatasi = field[4].Count < 7;

        var tanggalCocok = field[2].Contains(t.Day);
        var hariCocok = field[4].Contains((int)t.DayOfWeek);

        if (tanggalDibatasi && hariDibatasi) return tanggalCocok || hariCocok;
        if (tanggalDibatasi) return tanggalCocok;
        if (hariDibatasi) return hariCocok;

        return true;
    }

    /// <summary>Lima himpunan nilai yang diizinkan, atau null kalau tidak sah.</summary>
    private static HashSet<int>[]? Parse(string expression)
    {
        if (string.IsNullOrWhiteSpace(expression)) return null;

        var ruas = expression.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (ruas.Length != 5) return null;

        var batas = new[] { (0, 59), (0, 23), (1, 31), (1, 12), (0, 7) };
        var hasil = new HashSet<int>[5];

        for (var i = 0; i < 5; i++)
        {
            var himpunan = UraiRuas(ruas[i], batas[i].Item1, batas[i].Item2);
            if (himpunan == null) return null;

            // Cron menerima 7 sebagai Minggu, sementara DayOfWeek memakai 0.
            // Disamakan di sini supaya pembandingnya tidak perlu tahu.
            if (i == 4 && himpunan.Remove(7)) himpunan.Add(0);

            if (himpunan.Count == 0) return null;
            hasil[i] = himpunan;
        }

        return hasil;
    }

    private static HashSet<int>? UraiRuas(string ruas, int min, int max)
    {
        var hasil = new HashSet<int>();

        foreach (var bagian in ruas.Split(','))
        {
            if (bagian.Length == 0) return null;

            var langkah = 1;
            var isi = bagian;

            var garis = bagian.IndexOf('/');
            if (garis >= 0)
            {
                isi = bagian.Substring(0, garis);
                if (!int.TryParse(bagian.Substring(garis + 1), out langkah) || langkah < 1) return null;
            }

            int mulai, akhir;

            if (isi == "*")
            {
                mulai = min;
                akhir = max;
            }
            else
            {
                var strip = isi.IndexOf('-');

                if (strip > 0)
                {
                    if (!int.TryParse(isi.Substring(0, strip), out mulai)) return null;
                    if (!int.TryParse(isi.Substring(strip + 1), out akhir)) return null;
                }
                else
                {
                    if (!int.TryParse(isi, out mulai)) return null;

                    // Angka tunggal DENGAN langkah berarti "dari sini sampai
                    // batas atas": "5/10" pada menit berarti 5, 15, 25, ...
                    akhir = garis >= 0 ? max : mulai;
                }
            }

            if (mulai < min || akhir > max || mulai > akhir) return null;

            for (var n = mulai; n <= akhir; n += langkah) hasil.Add(n);
        }

        return hasil;
    }
}
