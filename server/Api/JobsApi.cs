using ForgeHub.Data;

namespace ForgeHub.Api;

public static class JobsApi
{
    public static void MapJobs(this WebApplication app)
    {
        // --------------------------------------------------------------
        app.MapGet("/api/jobs", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var state = context.Request.Query["state"].ToString();
            var process = context.Request.Query["process"].ToString();
            var limit = context.IntQuery("limit", 100);

            // Penyaring dirangkai, bukan dijabarkan jadi empat query terpisah.
            // Halaman detail proses memerlukan "jalan milik proses ini saja",
            // dan menambahkannya sebagai cabang baru berarti empat kombinasi
            // yang harus dijaga tetap sama isinya.
            var where = new List<string> { "tenant_id = @p0" };
            var args = new List<object> { tenantId };

            if (!string.IsNullOrEmpty(state))
            {
                where.Add("state = @p" + args.Count);
                args.Add(state.ToUpperInvariant());
            }

            if (!string.IsNullOrEmpty(process))
            {
                where.Add("process_name = @p" + args.Count);
                args.Add(process);
            }

            var sql = @"SELECT id, process_name, robot_name, machine_name, state, source, priority,
                               progress, info, created_at, started_at, ended_at
                          FROM jobs
                         WHERE " + string.Join(" AND ", where) + @"
                         ORDER BY created_at DESC
                         LIMIT @p" + args.Count;

            args.Add(limit);

            return Results.Ok(Sql.Rows(connection, sql, args.ToArray()));
        });

        // --------------------------------------------------------------
        app.MapGet("/api/jobs/{id}", (HttpContext context, string id) =>
        {
            using var connection = context.Db(out var tenantId);

            var row = Sql.Row(connection,
                @"SELECT id, process_name, robot_name, machine_name, state, source, priority,
                         progress, info, input_json, output_json, created_at, started_at, ended_at
                    FROM jobs
                   WHERE tenant_id = @p0 AND id = @p1",
                tenantId, id);

            return row == null ? Results.NotFound(new { error = "Pekerjaan tidak ada." }) : Results.Ok(row);
        });

        // --------------------------------------------------------------
        // Menjadwalkan pekerjaan. Dipakai tombol "Start Job" di dasbor, pemicu
        // terjadwal, dan Studio.
        app.MapPost("/api/jobs", async (HttpContext context) =>
        {
            var body = await context.Body();
            var process = body.Str("processName");

            if (string.IsNullOrWhiteSpace(process))
                return Results.BadRequest(new { error = "processName wajib diisi." });

            using var connection = context.Db(out var tenantId);

            if (Sql.Count(connection,
                    "SELECT COUNT(*) FROM processes WHERE tenant_id = @p0 AND name = @p1", tenantId, process) == 0)
                return Results.BadRequest(new { error = "Proses '" + process + "' belum diterbitkan ke ForgeHub." });

            var id = Sql.NewId();

            Sql.Exec(connection,
                @"INSERT INTO jobs
                    (id, tenant_id, process_name, robot_name, machine_name, state, source, priority,
                     progress, info, input_json, created_at)
                  VALUES (@p0, @p1, @p2, @p3, @p4, 'PENDING', @p5, @p6, 0, @p7, @p8, @p9)",
                id, tenantId, process, body.Str("robotName"), body.Str("machineName"),
                body.Str("source", "Manual"), body.Str("priority", "Normal"),
                "Menunggu robot yang tersedia.", body.Str("inputJson"), Sql.Now());

            Sql.Exec(connection,
                @"INSERT INTO logs (tenant_id, level, message, process_name, robot_name, job_id, logged_at)
                  VALUES (@p0, 'INFO', @p1, @p2, @p3, @p4, @p5)",
                tenantId, "Pekerjaan dijadwalkan untuk " + process + ".",
                process, body.Str("robotName"), id, Sql.Now());

            return Results.Ok(new { ok = true, id });
        });

        // --------------------------------------------------------------
        // Robot mengambil pekerjaan berikutnya untuk dirinya.
        //
        // Pengambilannya HARUS satu langkah yang tak terbagi. Kalau baca-lalu-tulis
        // dipisah, dua robot yang bertanya bersamaan akan sama-sama melihat
        // pekerjaan yang sama sebagai PENDING dan menjalankannya dua kali.
        app.MapGet("/api/jobs/next", (HttpContext context) =>
        {
            var robot = context.Request.Query["robot"].ToString();

            if (string.IsNullOrWhiteSpace(robot))
                return Results.BadRequest(new { error = "Parameter 'robot' wajib diisi." });

            using var connection = context.Db(out var tenantId);
            using var transaction = connection.BeginTransaction();

            // Pekerjaan yang ditujukan ke robot ini, atau yang belum ditujukan
            // ke siapa pun. Prioritas tinggi lebih dulu, lalu yang paling lama menunggu.
            var claimed = Sql.Exec(connection,
                @"UPDATE jobs
                     SET state = 'RUNNING',
                         robot_name = @p0,
                         started_at = @p1,
                         info = 'Sedang dijalankan.'
                   WHERE id = (
                         SELECT id FROM jobs
                          WHERE tenant_id = @p2
                            AND state = 'PENDING'
                            AND (robot_name IS NULL OR robot_name = '' OR robot_name = @p0)
                          ORDER BY CASE priority
                                     WHEN 'High' THEN 0
                                     WHEN 'Normal' THEN 1
                                     ELSE 2
                                   END,
                                   created_at
                          LIMIT 1)",
                robot, Sql.Now(), tenantId);

            if (claimed == 0)
            {
                transaction.Commit();
                return Results.Ok(new { job = (object)null });
            }

            var row = Sql.Row(connection,
                @"SELECT id, process_name, robot_name, state, priority, input_json, created_at, started_at
                    FROM jobs
                   WHERE tenant_id = @p0 AND robot_name = @p1 AND state = 'RUNNING'
                   ORDER BY started_at DESC
                   LIMIT 1",
                tenantId, robot);

            transaction.Commit();

            return Results.Ok(new { job = row });
        });

        // --------------------------------------------------------------
        // Robot melaporkan kemajuan dan hasil akhir.
        app.MapPost("/api/jobs/{id}/state", async (HttpContext context, string id) =>
        {
            var body = await context.Body();
            var state = (body.Str("state") ?? "").ToUpperInvariant();

            var allowed = new[] { "PENDING", "RUNNING", "SUCCESSFUL", "FAULTED", "STOPPING", "STOPPED" };
            if (!allowed.Contains(state))
                return Results.BadRequest(new { error = "Keadaan tidak dikenal: '" + state + "'." });

            var progress = (int)Math.Clamp(body.Num("progress", 0), 0, 100);
            var info = body.Str("info");
            var output = body.Str("outputJson");
            var finished = state is "SUCCESSFUL" or "FAULTED" or "STOPPED";
            var now = Sql.Now();

            using var connection = context.Db(out var tenantId);

            var process = (string)Sql.Scalar(connection,
                "SELECT process_name FROM jobs WHERE tenant_id = @p0 AND id = @p1", tenantId, id);

            if (process == null) return Results.NotFound(new { error = "Pekerjaan tidak ada." });

            Sql.Exec(connection,
                @"UPDATE jobs
                     SET state = @p0,
                         progress = @p1,
                         info = COALESCE(@p2, info),
                         output_json = COALESCE(@p3, output_json),
                         ended_at = CASE WHEN @p4 = 1 THEN @p5 ELSE ended_at END
                   WHERE tenant_id = @p6 AND id = @p7",
                state, finished ? 100 : progress, info, output, finished ? 1 : 0, now, tenantId, id);

            if (state == "FAULTED")
            {
                ApiSupport.Alert(connection, tenantId, "Error",
                    "Pekerjaan gagal",
                    process + " gagal: " + (info ?? "tanpa keterangan"), "jobs");
            }

            return Results.Ok(new { ok = true });
        });

        // --------------------------------------------------------------
        // Permintaan berhenti. Statusnya STOPPING, bukan langsung STOPPED:
        // yang benar-benar bisa menghentikan proses adalah robotnya, dan ia baru
        // tahu pada denyut berikutnya.
        app.MapPost("/api/jobs/{id}/stop", (HttpContext context, string id) =>
        {
            using var connection = context.Db(out var tenantId);

            var changed = Sql.Exec(connection,
                @"UPDATE jobs
                     SET state = CASE WHEN state = 'PENDING' THEN 'STOPPED' ELSE 'STOPPING' END,
                         info = 'Diminta berhenti.',
                         ended_at = CASE WHEN state = 'PENDING' THEN @p0 ELSE ended_at END
                   WHERE tenant_id = @p1 AND id = @p2 AND state IN ('PENDING', 'RUNNING')",
                Sql.Now(), tenantId, id);

            return changed == 0
                ? Results.BadRequest(new { error = "Pekerjaan itu tidak sedang menunggu atau berjalan." })
                : Results.Ok(new { ok = true });
        });

        // --------------------------------------------------------------
        app.MapDelete("/api/jobs/{id}", (HttpContext context, string id) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM jobs WHERE tenant_id = @p0 AND id = @p1", tenantId, id);

            return removed == 0 ? Results.NotFound(new { error = "Pekerjaan tidak ada." }) : Results.Ok(new { ok = true });
        });

        MapTriggers(app);
    }

    // ------------------------------------------------------------------

    private static void MapTriggers(WebApplication app)
    {
        app.MapGet("/api/triggers", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT id, name, process_name, robot_name, type, cron, interval_minutes, priority, timezone, runtime_type,
                         enabled, next_run_at, last_run_at, created_at
                    FROM triggers
                   WHERE tenant_id = @p0
                   ORDER BY enabled DESC, next_run_at",
                tenantId);

            return Results.Ok(rows);
        });

        app.MapPost("/api/triggers", async (HttpContext context) =>
        {
            var body = await context.Body();

            var name = body.Str("name");
            var process = body.Str("processName");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama pemicu wajib diisi." });

            if (string.IsNullOrWhiteSpace(process))
                return Results.BadRequest(new { error = "processName wajib diisi." });

            var interval = (int)body.Num("intervalMinutes", 60);
            var cron = body.Str("cron");

            // Cron DIVALIDASI di sini, bukan dibiarkan sampai penjadwal.
            //
            // Ekspresi yang salah baru ketahuan pada putaran penjadwal
            // berikutnya, dan orang yang menekan "Buat pemicu" sudah pergi.
            // Ditolak sekarang, dengan penjelasan bentuk yang benar.
            if (!string.IsNullOrWhiteSpace(cron) && !Cron.IsValid(cron))
            {
                return Results.BadRequest(new
                {
                    error = "Ekspresi cron tidak sah: " + cron +
                            ". Bentuknya lima ruas: menit jam tanggal bulan hari, " +
                            "mis. \"0 7 * * 1-5\" untuk tiap hari kerja pukul 07:00.",
                });
            }

            if (string.IsNullOrWhiteSpace(cron) && interval < 1)
                return Results.BadRequest(new { error = "Selang waktu minimal 1 menit." });

            using var connection = context.Db(out var tenantId);

            if (Sql.Count(connection,
                    "SELECT COUNT(*) FROM processes WHERE tenant_id = @p0 AND name = @p1", tenantId, process) == 0)
                return Results.BadRequest(new { error = "Proses '" + process + "' belum diterbitkan ke ForgeHub." });

            var enabled = body.Flag("enabled", true);

            // Waktu jalan pertama mengikuti cara penjadwalannya.
            var next = string.IsNullOrWhiteSpace(cron)
                ? DateTime.UtcNow.AddMinutes(interval).ToString("yyyy-MM-ddTHH:mm:ssZ")
                : Cron.Next(cron, DateTime.UtcNow)!.Value.ToString("yyyy-MM-ddTHH:mm:ssZ");

            var existing = (string)Sql.Scalar(connection,
                "SELECT id FROM triggers WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            if (existing != null)
            {
                Sql.Exec(connection,
                    @"UPDATE triggers
                         SET process_name = @p0, robot_name = @p1, type = @p2, cron = @p3,
                             interval_minutes = @p4, enabled = @p5, next_run_at = @p6,
                             priority = @p7, timezone = @p8, runtime_type = @p9
                       WHERE id = @p10",
                    process, body.Str("robotName"), body.Str("type", "Time"), body.Str("cron"),
                    interval, enabled ? 1 : 0, next,
                    body.Str("priority", "Normal"), body.Str("timezone", "UTC"),
                    body.Str("runtimeType", "Unattended"), existing);
            }
            else
            {
                Sql.Exec(connection,
                    @"INSERT INTO triggers
                        (id, tenant_id, name, process_name, robot_name, type, cron,
                         interval_minutes, enabled, next_run_at, created_at,
                         priority, timezone, runtime_type)
                      VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, @p7, @p8, @p9, @p10, @p11, @p12, @p13)",
                    Sql.NewId(), tenantId, name, process, body.Str("robotName"),
                    body.Str("type", "Time"), body.Str("cron"), interval,
                    enabled ? 1 : 0, next, Sql.Now(),
                    body.Str("priority", "Normal"), body.Str("timezone", "UTC"),
                    body.Str("runtimeType", "Unattended"));
            }

            return Results.Ok(new { ok = true });
        });

        app.MapPost("/api/triggers/{name}/toggle", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var changed = Sql.Exec(connection,
                @"UPDATE triggers
                     SET enabled = CASE WHEN enabled = 1 THEN 0 ELSE 1 END,
                         next_run_at = CASE
                             WHEN enabled = 1 THEN next_run_at
                             ELSE datetime('now', '+' || interval_minutes || ' minutes')
                         END
                   WHERE tenant_id = @p0 AND name = @p1",
                tenantId, name);

            return changed == 0
                ? Results.NotFound(new { error = "Pemicu tidak ada." })
                : Results.Ok(new { ok = true });
        });

        app.MapDelete("/api/triggers/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM triggers WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            return removed == 0 ? Results.NotFound(new { error = "Pemicu tidak ada." }) : Results.Ok(new { ok = true });
        });
    }
}
