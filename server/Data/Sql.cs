using Microsoft.Data.Sqlite;

namespace ForgeHub.Data;

/// <summary>
/// Pembantu kueri yang tipis.
///
/// Tidak ada ORM di sini dengan sengaja. Endpoint ForgeHub semuanya berupa
/// "ambil beberapa baris lalu kirim sebagai JSON"; sebuah ORM di tengahnya
/// hanya menambah lapisan yang harus dipahami tanpa menghilangkan satu pun SQL
/// yang benar-benar ditulis.
///
/// SEMUA nilai masuk lewat parameter, tidak pernah lewat perangkaian string.
/// Nama proses dan nama robot datang dari luar, dan satu tanda kutip di sana
/// sudah cukup untuk mengubah kueri menjadi sesuatu yang lain.
/// </summary>
public static class Sql
{
    public static SqliteCommand Cmd(SqliteConnection connection, string sql, params object[] args)
    {
        var command = connection.CreateCommand();
        command.CommandText = sql;

        for (var i = 0; i < args.Length; i++)
            command.Parameters.AddWithValue("@p" + i, args[i] ?? DBNull.Value);

        return command;
    }

    public static int Exec(SqliteConnection connection, string sql, params object[] args)
    {
        using var command = Cmd(connection, sql, args);
        return command.ExecuteNonQuery();
    }

    public static object Scalar(SqliteConnection connection, string sql, params object[] args)
    {
        using var command = Cmd(connection, sql, args);
        var value = command.ExecuteScalar();
        return value == DBNull.Value ? null : value;
    }

    public static long Count(SqliteConnection connection, string sql, params object[] args)
    {
        var value = Scalar(connection, sql, args);
        return value == null ? 0 : Convert.ToInt64(value);
    }

    /// <summary>Semua baris sebagai kamus kolom-ke-nilai, siap jadi JSON.</summary>
    public static List<Dictionary<string, object>> Rows(SqliteConnection connection, string sql, params object[] args)
    {
        var result = new List<Dictionary<string, object>>();

        using var command = Cmd(connection, sql, args);
        using var reader = command.ExecuteReader();

        while (reader.Read())
        {
            var row = new Dictionary<string, object>(reader.FieldCount);

            for (var i = 0; i < reader.FieldCount; i++)
            {
                var name = Camel(reader.GetName(i));
                row[name] = reader.IsDBNull(i) ? null : reader.GetValue(i);
            }

            result.Add(row);
        }

        return result;
    }

    public static Dictionary<string, object> Row(SqliteConnection connection, string sql, params object[] args)
    {
        var rows = Rows(connection, sql, args);
        return rows.Count == 0 ? null : rows[0];
    }

    /// <summary>
    /// snake_case di basis data, camelCase di JSON.
    ///
    /// Kedua sisi memakai kebiasaan masing-masing; penerjemahannya cukup di satu
    /// tempat ini, bukan ditulis ulang di setiap kueri sebagai alias AS.
    /// </summary>
    private static string Camel(string column)
    {
        if (string.IsNullOrEmpty(column) || !column.Contains('_')) return column;

        var parts = column.Split('_');
        var text = parts[0];

        for (var i = 1; i < parts.Length; i++)
        {
            if (parts[i].Length == 0) continue;
            text += char.ToUpperInvariant(parts[i][0]) + parts[i][1..];
        }

        return text;
    }

    public static string Now() => DateTime.UtcNow.ToString("o");

    public static string NewId() => Guid.NewGuid().ToString("N");
}
