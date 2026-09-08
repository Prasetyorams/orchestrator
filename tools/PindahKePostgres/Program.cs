using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using Microsoft.Data.Sqlite;

namespace PindahKePostgres;

/// <summary>
/// Memindahkan isi forgehub.db (SQLite) menjadi berkas SQL untuk PostgreSQL.
///
/// Yang dihasilkan adalah BERKAS, bukan sambungan langsung ke PostgreSQL. Itu
/// disengaja: berkasnya bisa dibaca dulu sebelum dijalankan, bisa disimpan
/// sebagai bukti apa yang dipindahkan, dan bisa diulang kalau percobaan
/// pertama gagal di tengah. Alat pindah yang langsung menulis ke basis data
/// tujuan hanya bisa dipercaya kalau sudah dipercaya.
///
/// SQLite menyimpan hampir semuanya sebagai teks — waktu, benar/salah, angka.
/// PostgreSQL tidak, dan justru itu gunanya pindah. Jadi setiap kolom
/// diterjemahkan menurut tipe yang dituju, bukan disalin apa adanya:
///
///   id 32 heksa      -> UUID bertanda hubung
///   "2026-09-08T..." -> TIMESTAMPTZ
///   0 / 1            -> BOOLEAN
///   BLOB             -> BYTEA dalam bentuk \x heksa
///
/// TENANT-nya dipetakan ulang. Migrasi Flyway V2 sudah menanam satu tenant
/// bernama "default" dengan UUID 1111...1111, dan tenant kedua dengan nama
/// yang sama akan ditolak oleh batasan UNIQUE. Jadi tenant lama TIDAK
/// disalin; seluruh baris yang menunjuk kepadanya dialihkan ke UUID benih itu.
/// </summary>
public static class Program
{
    private const string TenantBenih = "11111111-1111-1111-1111-111111111111";


    public static int Main(string[] args)
    {
        var dbPath = args.Length > 0
            ? args[0]
            : Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "JakForge", "ForgeHub", "forgehub.db");

        var keluaran = args.Length > 1
            ? args[1]
            : Path.Combine(Path.GetDirectoryName(Path.GetFullPath(dbPath)) ?? ".", "forgehub-migrasi.sql");

        if (!File.Exists(dbPath))
        {
            Console.Error.WriteLine("Tidak menemukan basis data: " + dbPath);
            return 2;
        }

        Console.WriteLine("Sumber : " + dbPath);
        Console.WriteLine("Tujuan : " + keluaran);
        Console.WriteLine();

        // Dibuka HANYA-BACA. ForgeHub .NET boleh terus berjalan selama ini;
        // yang tidak boleh adalah alat pindah ikut menulis ke basis data yang
        // sedang dipakai.
        var cs = new SqliteConnectionStringBuilder
        {
            DataSource = dbPath,
            Mode = SqliteOpenMode.ReadOnly,
        }.ToString();

        using var db = new SqliteConnection(cs);
        db.Open();

        var tenantLama = Skalar(db, "SELECT id FROM tenants LIMIT 1") as string;

        if (tenantLama == null)
        {
            Console.Error.WriteLine("Tidak ada baris di tabel tenants; tidak ada yang bisa dipindahkan.");
            return 3;
        }

        var jumlahTenant = Convert.ToInt64(Skalar(db, "SELECT COUNT(*) FROM tenants"));
        if (jumlahTenant > 1)
        {
            // Pemetaan ulang hanya benar untuk satu tenant. Diam-diam
            // menggabungkan beberapa tenant menjadi satu berarti data pelanggan
            // yang berbeda bercampur, dan itu tidak boleh terjadi tanpa
            // seseorang memutuskannya.
            Console.Error.WriteLine("Ada " + jumlahTenant + " tenant. Alat ini hanya menangani satu.");
            return 4;
        }

        var sb = new StringBuilder();
        Kepala(sb, dbPath, tenantLama);

        var total = 0L;
        foreach (var tabel in Tabel.Semua)
        {
            total += Tulis(sb, db, tabel, tenantLama);
        }

        sb.AppendLine();
        sb.AppendLine("COMMIT;");
        sb.AppendLine();
        sb.AppendLine("-- Urutan BIGSERIAL disetel ulang: tanpa ini, sisipan berikutnya");
        sb.AppendLine("-- memakai nomor yang sudah terpakai dan gagal karena kunci ganda.");
        sb.AppendLine("SELECT setval(pg_get_serial_sequence('logs',   'id'), COALESCE((SELECT MAX(id) FROM logs),   1));");
        sb.AppendLine("SELECT setval(pg_get_serial_sequence('alerts', 'id'), COALESCE((SELECT MAX(id) FROM alerts), 1));");

        File.WriteAllText(keluaran, sb.ToString(), new UTF8Encoding(false));

        Console.WriteLine();
        Console.WriteLine("Selesai. " + total + " baris ditulis ke " + Path.GetFileName(keluaran)
                          + " (" + new FileInfo(keluaran).Length / 1024 + " KB).");
        return 0;
    }

    private static void Kepala(StringBuilder sb, string dbPath, string tenantLama)
    {
        sb.AppendLine("-- =====================================================================");
        sb.AppendLine("-- Pindahan isi ForgeHub dari SQLite ke PostgreSQL.");
        sb.AppendLine("--");
        sb.AppendLine("-- Dihasilkan : " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"));
        sb.AppendLine("-- Sumber     : " + dbPath);
        sb.AppendLine("-- Tenant     : " + tenantLama + "  ->  " + TenantBenih);
        sb.AppendLine("--");
        sb.AppendLine("-- Dijalankan SESUDAH Flyway selesai, karena bergantung pada tabel dan");
        sb.AppendLine("-- pada baris benih V2 yang sudah ada.");
        sb.AppendLine("--");
        sb.AppendLine("-- Seluruhnya dalam SATU transaksi: pindahan yang berhenti di tengah");
        sb.AppendLine("-- meninggalkan basis data separuh terisi, dan itu keadaan yang paling");
        sb.AppendLine("-- sulit dibereskan — lebih sulit daripada mengulang dari kosong.");
        sb.AppendLine("-- =====================================================================");
        sb.AppendLine();
        sb.AppendLine("BEGIN;");
    }

    private static long Tulis(StringBuilder sb, SqliteConnection db, Tabel tabel, string tenantLama)
    {
        var ada = Convert.ToInt64(Skalar(db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=$n", ("$n", tabel.Nama)));

        if (ada == 0)
        {
            Console.WriteLine(tabel.Nama.PadRight(16) + "  tabel tidak ada di sumber, dilewat");
            return 0;
        }

        var kolom = string.Join(", ", tabel.Kolom);

        using var cmd = db.CreateCommand();
        cmd.CommandText = "SELECT " + kolom + " FROM " + tabel.Nama;

        using var r = cmd.ExecuteReader();

        var baris = 0L;
        sb.AppendLine();
        sb.AppendLine("-- ---------- " + tabel.Nama + " ----------");

        while (r.Read())
        {
            var nilai = new List<string>(tabel.Kolom.Length);

            for (var i = 0; i < tabel.Kolom.Length; i++)
            {
                nilai.Add(Ubah(r, i, tabel.Kolom[i], tabel.Jenis[i], tenantLama));
            }

            sb.Append("INSERT INTO ").Append(tabel.Nama)
              .Append(" (").Append(kolom).Append(") VALUES (")
              .Append(string.Join(", ", nilai)).Append(')');

            // SELALU, untuk setiap tabel.
            //
            // Percobaan pertama membedakan tabel yang sudah ditanam Flyway dari
            // yang belum, dan syaratnya tertulis TERBALIK — justru tabel benih
            // yang tidak mendapat klausa ini, lalu "Administrator" bentrok pada
            // baris ke-22. Pembedaan itu sekarang dibuang seluruhnya, bukan
            // dibetulkan: tanpa target konflik, klausa ini menutup SEMUA
            // batasan unik, dan pemindahannya jadi boleh diulang. Pemindahan
            // yang hanya boleh dijalankan sekali adalah pemindahan yang tidak
            // bisa dicoba lagi setelah gagal di tengah.
            sb.AppendLine(" ON CONFLICT DO NOTHING;");

            baris++;
        }

        Console.WriteLine(tabel.Nama.PadRight(16) + "  " + baris.ToString().PadLeft(7) + " baris");
        return baris;
    }

    private static string Ubah(SqliteDataReader r, int i, string kolom, Jenis jenis, string tenantLama)
    {
        if (r.IsDBNull(i)) return "NULL";

        switch (jenis)
        {
            case Jenis.Uuid:
                {
                    var teks = r.GetString(i);

                    // Seluruh baris milik tenant lama dialihkan ke tenant benih.
                    if (kolom == "tenant_id" && string.Equals(teks, tenantLama, StringComparison.OrdinalIgnoreCase))
                        return "'" + TenantBenih + "'";

                    var uuid = KeUuid(teks);
                    return uuid == null ? "NULL" : "'" + uuid + "'";
                }

            case Jenis.Waktu:
                {
                    var teks = r.GetString(i);
                    if (string.IsNullOrWhiteSpace(teks)) return "NULL";

                    // Waktu ditulis .NET dengan "o" (ISO-8601 lengkap). Yang
                    // gagal diurai dibiarkan NULL, bukan ditulis apa adanya:
                    // teks yang bukan waktu akan menggagalkan seluruh
                    // transaksi di baris terakhir, sesudah semuanya terlanjur.
                    if (!DateTimeOffset.TryParse(teks, CultureInfo.InvariantCulture,
                            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var waktu))
                        return "NULL";

                    return "'" + waktu.ToString("yyyy-MM-dd HH:mm:ss.ffffff+00", CultureInfo.InvariantCulture) + "'";
                }

            case Jenis.Benar:
                return r.GetInt64(i) != 0 ? "TRUE" : "FALSE";

            case Jenis.Angka:
                return Convert.ToString(r.GetValue(i), CultureInfo.InvariantCulture);

            case Jenis.Bita:
                {
                    var bita = (byte[])r.GetValue(i);
                    if (bita.Length == 0) return "NULL";

                    var hex = new StringBuilder(bita.Length * 2 + 4);
                    hex.Append("'\\x");
                    foreach (var b in bita) hex.Append(b.ToString("x2"));
                    hex.Append('\'');

                    return hex.ToString();
                }

            default:
                return Kutip(Convert.ToString(r.GetValue(i)));
        }
    }

    /// <summary>
    /// 32 heksa tanpa tanda hubung menjadi bentuk UUID yang dikenal PostgreSQL.
    ///
    /// Yang panjangnya sudah benar dengan tanda hubung dibiarkan. Yang bukan
    /// keduanya mengembalikan null — id yang dipaksakan menjadi UUID palsu
    /// akan menyambung baris ke baris yang salah, dan itu lebih buruk daripada
    /// kolom kosong.
    /// </summary>
    private static string KeUuid(string teks)
    {
        if (string.IsNullOrWhiteSpace(teks)) return null;

        return Guid.TryParse(teks, out var g) ? g.ToString("D") : null;
    }

    private static string Kutip(string teks)
    {
        if (teks == null) return "NULL";

        // Satu kutip tunggal menjadi dua. Nama proses datang dari pengguna, dan
        // satu tanda kutip di sana sudah cukup untuk mengubah sisa berkas ini
        // menjadi sesuatu yang lain.
        return "'" + teks.Replace("'", "''") + "'";
    }

    private static object Skalar(SqliteConnection db, string sql, params (string, object)[] args)
    {
        using var cmd = db.CreateCommand();
        cmd.CommandText = sql;

        foreach (var (nama, nilai) in args) cmd.Parameters.AddWithValue(nama, nilai);

        var v = cmd.ExecuteScalar();
        return v == DBNull.Value ? null : v;
    }
}

internal enum Jenis { Teks, Uuid, Waktu, Benar, Angka, Bita }

/// <summary>
/// Bentuk setiap tabel: kolom apa yang disalin dan jadi tipe apa.
///
/// Ditulis tangan, tidak dibaca dari PRAGMA. Sumbernya menyimpan semuanya
/// sebagai TEXT, jadi PRAGMA tidak tahu mana yang sebenarnya waktu dan mana
/// yang benar/salah — dan itulah persis yang perlu diketahui di sini.
///
/// Urutannya mengikuti ketergantungan: tenants sudah ada dari benih, sisanya
/// menunjuk kepadanya.
/// </summary>
internal sealed class Tabel
{
    public string Nama;
    public string[] Kolom;
    public Jenis[] Jenis;

    private static Tabel T(string nama, params (string, Jenis)[] kolom)
    {
        var t = new Tabel { Nama = nama, Kolom = new string[kolom.Length], Jenis = new Jenis[kolom.Length] };

        for (var i = 0; i < kolom.Length; i++)
        {
            t.Kolom[i] = kolom[i].Item1;
            t.Jenis[i] = kolom[i].Item2;
        }

        return t;
    }

    private const Jenis U = global::PindahKePostgres.Jenis.Uuid;
    private const Jenis W = global::PindahKePostgres.Jenis.Waktu;
    private const Jenis B = global::PindahKePostgres.Jenis.Benar;
    private const Jenis N = global::PindahKePostgres.Jenis.Angka;
    private const Jenis Y = global::PindahKePostgres.Jenis.Bita;
    private const Jenis S = global::PindahKePostgres.Jenis.Teks;

    public static readonly Tabel[] Semua =
    {
        T("users", ("id", U), ("tenant_id", U), ("username", S), ("password_hash", S),
            ("display_name", S), ("email", S), ("role", S), ("is_active", B), ("created_at", W), ("last_login_at", W)),

        T("roles", ("id", U), ("tenant_id", U), ("name", S), ("description", S),
            ("permissions", S), ("created_at", W)),

        T("machines", ("id", U), ("tenant_id", U), ("name", S), ("type", S),
            ("license_key", S), ("description", S), ("created_at", W)),

        T("environments", ("id", U), ("tenant_id", U), ("name", S), ("description", S), ("created_at", W)),

        T("robots", ("id", U), ("tenant_id", U), ("name", S), ("machine_name", S), ("username", S),
            ("type", S), ("environment", S), ("description", S), ("status", S),
            ("cpu_percent", N), ("memory_mb", N), ("last_heartbeat_at", W), ("created_at", W)),

        T("packages", ("id", U), ("tenant_id", U), ("name", S), ("version", S), ("description", S),
            ("entry_point", S), ("published_by", S), ("published_at", W), ("size_bytes", N), ("content", Y)),

        T("processes", ("id", U), ("tenant_id", U), ("name", S), ("package_name", S),
            ("package_version", S), ("environment", S), ("description", S), ("created_at", W)),

        T("jobs", ("id", U), ("tenant_id", U), ("process_name", S), ("robot_name", S), ("machine_name", S),
            ("state", S), ("source", S), ("priority", S), ("progress", N), ("info", S),
            ("input_json", S), ("output_json", S), ("created_at", W), ("started_at", W), ("ended_at", W)),

        T("triggers", ("id", U), ("tenant_id", U), ("name", S), ("process_name", S), ("robot_name", S),
            ("type", S), ("cron", S), ("interval_minutes", N), ("priority", S), ("timezone", S),
            ("runtime_type", S), ("enabled", B), ("next_run_at", W), ("last_run_at", W), ("created_at", W)),

        T("queues", ("id", U), ("tenant_id", U), ("name", S), ("description", S),
            ("max_retries", N), ("accept_duplicates", B), ("created_at", W)),

        T("queue_items", ("id", U), ("tenant_id", U), ("queue_name", S), ("reference", S), ("priority", S),
            ("status", S), ("content", S), ("output", S), ("exception", S), ("retries", N),
            ("robot_name", S), ("created_at", W), ("started_at", W), ("ended_at", W)),

        T("assets", ("id", U), ("tenant_id", U), ("name", S), ("type", S), ("value_text", S),
            ("description", S), ("scope", S), ("created_at", W), ("updated_at", W)),

        T("credentials", ("id", U), ("tenant_id", U), ("name", S), ("username", S),
            ("password_enc", S), ("description", S), ("created_at", W)),

        T("buckets", ("id", U), ("tenant_id", U), ("name", S), ("description", S), ("created_at", W)),

        T("bucket_files", ("id", U), ("tenant_id", U), ("bucket_name", S), ("file_name", S),
            ("content_type", S), ("size_bytes", N), ("uploaded_by", S), ("uploaded_at", W), ("content", Y)),

        // id ikut disalin supaya nomor di log lama tetap sama dengan yang
        // pernah dilihat orang; urutannya disetel ulang di akhir berkas.
        T("logs", ("id", N), ("tenant_id", U), ("level", S), ("message", S), ("robot_name", S),
            ("machine_name", S), ("process_name", S), ("job_id", U), ("logged_at", W)),

        T("alerts", ("id", N), ("tenant_id", U), ("severity", S), ("title", S), ("message", S),
            ("source", S), ("is_read", B), ("created_at", W)),

        T("licenses", ("id", U), ("tenant_id", U), ("product", S), ("total", N), ("used", N), ("expires_at", W)),
    };
}
