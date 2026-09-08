using ForgeHub.Data;

namespace ForgeHub.Api;

public static class QueuesApi
{
    public static void MapQueues(this WebApplication app)
    {
        // --------------------------------------------------------------
        // Daftar antrean lengkap dengan hitungan tiap keadaan — angka inilah
        // yang dilihat orang lebih dulu daripada isinya.
        app.MapGet("/api/queues", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT q.id, q.name, q.description, q.max_retries, q.accept_duplicates, q.created_at,
                         (SELECT COUNT(*) FROM queue_items i
                           WHERE i.tenant_id = q.tenant_id AND i.queue_name = q.name AND i.status = 'NEW') AS new_count,
                         (SELECT COUNT(*) FROM queue_items i
                           WHERE i.tenant_id = q.tenant_id AND i.queue_name = q.name AND i.status = 'IN_PROGRESS') AS in_progress_count,
                         (SELECT COUNT(*) FROM queue_items i
                           WHERE i.tenant_id = q.tenant_id AND i.queue_name = q.name AND i.status = 'SUCCESSFUL') AS successful_count,
                         (SELECT COUNT(*) FROM queue_items i
                           WHERE i.tenant_id = q.tenant_id AND i.queue_name = q.name AND i.status = 'FAILED') AS failed_count,
                         (SELECT COUNT(*) FROM queue_items i
                           WHERE i.tenant_id = q.tenant_id AND i.queue_name = q.name) AS total_count
                    FROM queues q
                   WHERE q.tenant_id = @p0
                   ORDER BY q.name",
                tenantId);

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        app.MapPost("/api/queues", async (HttpContext context) =>
        {
            var body = await context.Body();
            var name = body.Str("name");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama antrean wajib diisi." });

            using var connection = context.Db(out var tenantId);

            if (Sql.Count(connection,
                    "SELECT COUNT(*) FROM queues WHERE tenant_id = @p0 AND name = @p1", tenantId, name) > 0)
                return Results.Conflict(new { error = "Antrean '" + name + "' sudah ada." });

            Sql.Exec(connection,
                @"INSERT INTO queues (id, tenant_id, name, description, max_retries, accept_duplicates, created_at)
                  VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6)",
                Sql.NewId(), tenantId, name, body.Str("description"),
                (int)body.Num("maxRetries", 3), body.Flag("acceptDuplicates") ? 1 : 0, Sql.Now());

            return Results.Ok(new { ok = true });
        });

        app.MapDelete("/api/queues/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            // Isinya ikut dihapus. Butir yang menggantung tanpa antrean induk
            // tidak akan pernah bisa dilihat lagi lewat jalan mana pun.
            Sql.Exec(connection, "DELETE FROM queue_items WHERE tenant_id = @p0 AND queue_name = @p1", tenantId, name);

            var removed = Sql.Exec(connection,
                "DELETE FROM queues WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            return removed == 0 ? Results.NotFound(new { error = "Antrean tidak ada." }) : Results.Ok(new { ok = true });
        });

        // --------------------------------------------------------------
        app.MapGet("/api/queues/{name}/items", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var status = context.Request.Query["status"].ToString();
            var limit = context.IntQuery("limit", 200);

            var rows = string.IsNullOrEmpty(status)
                ? Sql.Rows(connection,
                    @"SELECT id, reference, priority, status, content, output, exception, retries,
                             robot_name, created_at, started_at, ended_at
                        FROM queue_items
                       WHERE tenant_id = @p0 AND queue_name = @p1
                       ORDER BY created_at DESC LIMIT @p2",
                    tenantId, name, limit)
                : Sql.Rows(connection,
                    @"SELECT id, reference, priority, status, content, output, exception, retries,
                             robot_name, created_at, started_at, ended_at
                        FROM queue_items
                       WHERE tenant_id = @p0 AND queue_name = @p1 AND status = @p2
                       ORDER BY created_at DESC LIMIT @p3",
                    tenantId, name, status.ToUpperInvariant(), limit);

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        // Menambah butir. Dipakai activity "Add Queue Item" di Studio.
        app.MapPost("/api/queues/{name}/items", async (HttpContext context, string name) =>
        {
            var body = await context.Body();

            using var connection = context.Db(out var tenantId);

            if (Sql.Count(connection,
                    "SELECT COUNT(*) FROM queues WHERE tenant_id = @p0 AND name = @p1", tenantId, name) == 0)
                return Results.NotFound(new { error = "Antrean '" + name + "' tidak ada." });

            var reference = body.Str("reference");
            var acceptDuplicates = Sql.Count(connection,
                "SELECT accept_duplicates FROM queues WHERE tenant_id = @p0 AND name = @p1", tenantId, name) == 1;

            // Penolakan kembar hanya berlaku untuk butir yang BELUM selesai.
            // Referensi yang sama boleh muncul lagi besok; yang tidak boleh
            // adalah dua salinan menunggu diproses pada saat yang sama.
            if (!acceptDuplicates && !string.IsNullOrEmpty(reference))
            {
                var duplicate = Sql.Count(connection,
                    @"SELECT COUNT(*) FROM queue_items
                       WHERE tenant_id = @p0 AND queue_name = @p1 AND reference = @p2
                         AND status IN ('NEW', 'IN_PROGRESS')",
                    tenantId, name, reference);

                if (duplicate > 0)
                    return Results.Conflict(new
                    {
                        error = "Butir dengan referensi '" + reference + "' sudah menunggu di antrean ini.",
                    });
            }

            var id = Sql.NewId();

            Sql.Exec(connection,
                @"INSERT INTO queue_items
                    (id, tenant_id, queue_name, reference, priority, status, content, retries, created_at)
                  VALUES (@p0, @p1, @p2, @p3, @p4, 'NEW', @p5, 0, @p6)",
                id, tenantId, name, reference, body.Str("priority", "Normal"),
                body.Str("content"), Sql.Now());

            return Results.Ok(new { ok = true, id });
        });

        // --------------------------------------------------------------
        // Robot mengambil butir berikutnya. Sama seperti pengambilan pekerjaan,
        // ini satu langkah tak terbagi supaya satu butir tidak diproses dua robot.
        app.MapPost("/api/queues/{name}/next", async (HttpContext context, string name) =>
        {
            var body = await context.Body();
            var robot = body.Str("robotName");

            using var connection = context.Db(out var tenantId);
            using var transaction = connection.BeginTransaction();

            var claimed = Sql.Exec(connection,
                @"UPDATE queue_items
                     SET status = 'IN_PROGRESS', robot_name = @p0, started_at = @p1
                   WHERE id = (
                         SELECT id FROM queue_items
                          WHERE tenant_id = @p2 AND queue_name = @p3 AND status = 'NEW'
                          ORDER BY CASE priority
                                     WHEN 'High' THEN 0
                                     WHEN 'Normal' THEN 1
                                     ELSE 2
                                   END,
                                   created_at
                          LIMIT 1)",
                robot, Sql.Now(), tenantId, name);

            if (claimed == 0)
            {
                transaction.Commit();
                return Results.Ok(new { item = (object)null });
            }

            var row = Sql.Row(connection,
                @"SELECT id, reference, priority, status, content, retries, created_at, started_at
                    FROM queue_items
                   WHERE tenant_id = @p0 AND queue_name = @p1 AND status = 'IN_PROGRESS' AND robot_name IS @p2
                   ORDER BY started_at DESC LIMIT 1",
                tenantId, name, robot);

            transaction.Commit();

            return Results.Ok(new { item = row });
        });

        // --------------------------------------------------------------
        // Hasil pemrosesan satu butir.
        app.MapPost("/api/queues/items/{id}/result", async (HttpContext context, string id) =>
        {
            var body = await context.Body();
            var status = (body.Str("status") ?? "").ToUpperInvariant();

            if (status is not ("SUCCESSFUL" or "FAILED" or "RETRIED" or "ABANDONED"))
                return Results.BadRequest(new { error = "Status hasil tidak dikenal: '" + status + "'." });

            using var connection = context.Db(out var tenantId);

            var item = Sql.Row(connection,
                "SELECT queue_name, retries, reference FROM queue_items WHERE tenant_id = @p0 AND id = @p1",
                tenantId, id);

            if (item == null) return Results.NotFound(new { error = "Butir antrean tidak ada." });

            var now = Sql.Now();

            // Butir gagal dicoba lagi selama jatah percobaannya belum habis:
            // ia dikembalikan ke NEW dengan hitungan percobaan bertambah.
            if (status == "FAILED")
            {
                var maxRetries = Sql.Count(connection,
                    "SELECT max_retries FROM queues WHERE tenant_id = @p0 AND name = @p1",
                    tenantId, item["queueName"]);

                var retries = Convert.ToInt64(item["retries"]);

                if (retries < maxRetries)
                {
                    Sql.Exec(connection,
                        @"UPDATE queue_items
                             SET status = 'NEW', retries = retries + 1, exception = @p0,
                                 started_at = NULL, robot_name = NULL
                           WHERE tenant_id = @p1 AND id = @p2",
                        body.Str("exception"), tenantId, id);

                    return Results.Ok(new { ok = true, retried = true, attempt = retries + 1 });
                }

                ApiSupport.Alert(connection, tenantId, "Warning",
                    "Butir antrean gagal permanen",
                    "Butir '" + (item["reference"] ?? id) + "' di antrean " + item["queueName"] +
                    " gagal setelah " + retries + " percobaan ulang.", "queues");
            }

            Sql.Exec(connection,
                @"UPDATE queue_items
                     SET status = @p0, output = @p1, exception = @p2, ended_at = @p3
                   WHERE tenant_id = @p4 AND id = @p5",
                status, body.Str("output"), body.Str("exception"), now, tenantId, id);

            return Results.Ok(new { ok = true, retried = false });
        });

        app.MapDelete("/api/queues/items/{id}", (HttpContext context, string id) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM queue_items WHERE tenant_id = @p0 AND id = @p1", tenantId, id);

            return removed == 0 ? Results.NotFound(new { error = "Butir tidak ada." }) : Results.Ok(new { ok = true });
        });
    }
}
