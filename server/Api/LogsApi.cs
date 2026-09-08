using System.Text.Json;
using ForgeHub.Data;

namespace ForgeHub.Api;

public static class LogsApi
{
    /// <summary>Berapa banyak baris yang diterima dalam satu kiriman.</summary>
    private const int MaxLinesPerBatch = 1000;

    public static void MapLogs(this WebApplication app)
    {
        // --------------------------------------------------------------
        // Penerimaan log dari robot. Inilah yang dipanggil JakRunner tiap 3 detik.
        //
        // Satu bundel berisi banyak baris dan ditulis dalam SATU transaksi.
        // Seribu penulisan terpisah ke SQLite berarti seribu penyelarasan ke
        // cakram; satu transaksi menjadikannya satu.
        app.MapPost("/api/logs", async (HttpContext context) =>
        {
            var body = await context.Body();

            if (body.ValueKind != JsonValueKind.Object ||
                !body.TryGetProperty("lines", out var lines) ||
                lines.ValueKind != JsonValueKind.Array)
                return Results.BadRequest(new { error = "Butuh { \"lines\": [ ... ] }." });

            if (lines.GetArrayLength() > MaxLinesPerBatch)
                return Results.BadRequest(new
                {
                    error = "Terlalu banyak baris dalam satu kiriman. Batasnya " + MaxLinesPerBatch + ".",
                });

            using var connection = context.Db(out var tenantId);
            using var transaction = connection.BeginTransaction();

            var written = 0;

            foreach (var line in lines.EnumerateArray())
            {
                var message = line.Str("message");
                if (string.IsNullOrWhiteSpace(message)) continue;

                var level = (line.Str("level", "INFO") ?? "INFO").ToUpperInvariant();
                if (level is not ("TRACE" or "DEBUG" or "INFO" or "WARN" or "WARNING" or "ERROR" or "FATAL"))
                    level = "INFO";

                Sql.Exec(connection,
                    @"INSERT INTO logs (tenant_id, level, message, robot_name, machine_name, process_name, job_id, logged_at)
                      VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, @p7)",
                    tenantId, level, message, line.Str("robotName"), line.Str("machineName"),
                    line.Str("processName"), line.Str("jobId"), line.Str("loggedAt", Sql.Now()));

                written++;

                // Kesalahan dari robot juga menjadi peringatan. Log dibaca kalau
                // ada yang sengaja mencarinya; peringatan muncul sendiri.
                if (level is "ERROR" or "FATAL")
                {
                    ApiSupport.Alert(connection, tenantId, "Error",
                        "Kesalahan pada " + (line.Str("robotName") ?? "robot"),
                        message, "logs");
                }
            }

            transaction.Commit();

            return Results.Ok(new { ok = true, written });
        });

        // --------------------------------------------------------------
        // Pembacaan log untuk panel Real-Time Logs.
        //
        // Penjemputannya berdasar afterId, bukan waktu. Stempel waktu datang
        // dari jam robot, dan jam yang meleset beberapa detik membuat baris baru
        // tampak lebih tua daripada yang sudah tampil — lalu tidak pernah muncul.
        // Nomor urut basis data selalu naik apa pun jam si pengirim.
        app.MapGet("/api/logs", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var limit = context.IntQuery("limit", 100);
            var level = context.Request.Query["level"].ToString();
            var robot = context.Request.Query["robot"].ToString();
            var search = context.Request.Query["q"].ToString();

            long afterId = 0;
            long.TryParse(context.Request.Query["afterId"].ToString(), out afterId);

            var sql = @"SELECT id, level, message, robot_name, machine_name, process_name, job_id, logged_at
                          FROM logs
                         WHERE tenant_id = @p0 AND id > @p1";

            var args = new List<object> { tenantId, afterId };

            if (!string.IsNullOrEmpty(level))
            {
                sql += " AND level = @p" + args.Count;
                args.Add(level.ToUpperInvariant());
            }

            if (!string.IsNullOrEmpty(robot))
            {
                sql += " AND robot_name = @p" + args.Count;
                args.Add(robot);
            }

            // Penyaringan per PEKERJAAN: inilah yang membuat panel "log robot
            // yang sedang berjalan" hanya memuat baris dari jalan itu, bukan
            // seluruh riwayat penyewa yang tercampur.
            var job = context.Request.Query["jobId"].ToString();

            if (!string.IsNullOrEmpty(job))
            {
                sql += " AND job_id = @p" + args.Count;
                args.Add(job);
            }

            var process = context.Request.Query["process"].ToString();

            if (!string.IsNullOrEmpty(process))
            {
                sql += " AND process_name = @p" + args.Count;
                args.Add(process);
            }

            if (!string.IsNullOrEmpty(search))
            {
                sql += " AND message LIKE @p" + args.Count;
                args.Add("%" + search + "%");
            }

            // Yang TERBARU diambil lebih dulu lalu dibalik urutannya, supaya
            // permintaan pertama (afterId = 0) memberi 100 baris terakhir,
            // bukan 100 baris pertama dari hari pemasangan.
            sql += " ORDER BY id DESC LIMIT @p" + args.Count;
            args.Add(limit);

            var rows = Sql.Rows(connection, sql, args.ToArray());
            rows.Reverse();

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        app.MapDelete("/api/logs", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection, "DELETE FROM logs WHERE tenant_id = @p0", tenantId);

            return Results.Ok(new { ok = true, removed });
        });

        MapAlerts(app);
    }

    // ------------------------------------------------------------------

    private static void MapAlerts(WebApplication app)
    {
        app.MapGet("/api/alerts", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var limit = context.IntQuery("limit", 50);
            var unreadOnly = context.Request.Query["unread"].ToString() == "1";

            var rows = unreadOnly
                ? Sql.Rows(connection,
                    @"SELECT id, severity, title, message, source, is_read, created_at
                        FROM alerts WHERE tenant_id = @p0 AND is_read = 0
                       ORDER BY id DESC LIMIT @p1", tenantId, limit)
                : Sql.Rows(connection,
                    @"SELECT id, severity, title, message, source, is_read, created_at
                        FROM alerts WHERE tenant_id = @p0
                       ORDER BY id DESC LIMIT @p1", tenantId, limit);

            return Results.Ok(rows);
        });

        app.MapPost("/api/alerts/{id:long}/read", (HttpContext context, long id) =>
        {
            using var connection = context.Db(out var tenantId);

            Sql.Exec(connection, "UPDATE alerts SET is_read = 1 WHERE tenant_id = @p0 AND id = @p1", tenantId, id);

            return Results.Ok(new { ok = true });
        });

        app.MapPost("/api/alerts/read-all", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var changed = Sql.Exec(connection,
                "UPDATE alerts SET is_read = 1 WHERE tenant_id = @p0 AND is_read = 0", tenantId);

            return Results.Ok(new { ok = true, changed });
        });
    }
}
