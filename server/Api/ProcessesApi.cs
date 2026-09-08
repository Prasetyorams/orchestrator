using ForgeHub.Data;

namespace ForgeHub.Api;

public static class ProcessesApi
{
    /// <summary>Batas ukuran satu paket yang diterbitkan: 64 MB.</summary>
    private const long MaxPackageBytes = 64L * 1024 * 1024;

    public static void MapProcesses(this WebApplication app)
    {
        MapPackages(app);

        // --------------------------------------------------------------
        app.MapGet("/api/processes", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT p.id, p.name, p.package_name, p.package_version, p.environment,
                         p.description, p.created_at,
                         (SELECT COUNT(*) FROM jobs j
                           WHERE j.tenant_id = p.tenant_id AND j.process_name = p.name) AS job_count,
                         (SELECT MAX(j.created_at) FROM jobs j
                           WHERE j.tenant_id = p.tenant_id AND j.process_name = p.name) AS last_run_at
                    FROM processes p
                   WHERE p.tenant_id = @p0
                   ORDER BY p.name",
                tenantId);

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        // Dipakai Studio saat menerbitkan, dan dasbor saat membuat proses baru.
        // Nama yang sudah ada DIPERBARUI, bukan ditolak: menerbitkan ulang versi
        // yang lebih baru adalah hal yang paling sering dilakukan.
        app.MapPost("/api/processes", async (HttpContext context) =>
        {
            var body = await context.Body();
            var name = body.Str("name");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama proses wajib diisi." });

            using var connection = context.Db(out var tenantId);

            var exists = Sql.Count(connection,
                "SELECT COUNT(*) FROM processes WHERE tenant_id = @p0 AND name = @p1", tenantId, name) > 0;

            if (exists)
            {
                Sql.Exec(connection,
                    @"UPDATE processes
                         SET package_name = COALESCE(@p0, package_name),
                             package_version = COALESCE(@p1, package_version),
                             environment = COALESCE(@p2, environment),
                             description = COALESCE(@p3, description)
                       WHERE tenant_id = @p4 AND name = @p5",
                    body.Str("packageName"), body.Str("packageVersion"),
                    body.Str("environment"), body.Str("description"), tenantId, name);
            }
            else
            {
                Sql.Exec(connection,
                    @"INSERT INTO processes
                        (id, tenant_id, name, package_name, package_version, environment, description, created_at)
                      VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, @p7)",
                    Sql.NewId(), tenantId, name, body.Str("packageName"),
                    body.Str("packageVersion"), body.Str("environment", "Production"),
                    body.Str("description"), Sql.Now());
            }

            return Results.Ok(new { ok = true, created = !exists });
        });

        // --------------------------------------------------------------
        app.MapDelete("/api/processes/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM processes WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            return removed == 0
                ? Results.NotFound(new { error = "Proses '" + name + "' tidak ada." })
                : Results.Ok(new { ok = true });
        });
    }

    // ------------------------------------------------------------------

    private static void MapPackages(WebApplication app)
    {
        // Isi paketnya TIDAK ikut dalam daftar. Satu paket bisa puluhan megabita,
        // dan daftar yang membawanya akan menyeret seluruh gudang tiap kali
        // halaman dibuka.
        app.MapGet("/api/packages", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT id, name, version, description, entry_point, published_by, published_at, size_bytes
                    FROM packages
                   WHERE tenant_id = @p0
                   ORDER BY name, published_at DESC",
                tenantId);

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        // Penerbitan dari Studio.
        //
        // Isi paket dikirim sebagai base64 di dalam JSON, bukan multipart.
        // Penerbitnya adalah Studio di .NET Framework 4.6.2, yang menyusun
        // permintaan multipart dengan tangan; JSON base64 lebih besar 33% tapi
        // hanya butuh satu jalan yang sama dengan permintaan lainnya.
        app.MapPost("/api/packages", async (HttpContext context) =>
        {
            var body = await context.Body();

            var name = body.Str("name");
            var version = body.Str("version", "1.0.0");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama paket wajib diisi." });

            byte[] content = null;
            var contentBase64 = body.Str("contentBase64");

            if (!string.IsNullOrEmpty(contentBase64))
            {
                try
                {
                    content = Convert.FromBase64String(contentBase64);
                }
                catch (FormatException)
                {
                    return Results.BadRequest(new { error = "contentBase64 bukan base64 yang sah." });
                }

                if (content.LongLength > MaxPackageBytes)
                    return Results.BadRequest(new
                    {
                        error = "Paket terlalu besar. Batasnya " + (MaxPackageBytes / 1024 / 1024) + " MB.",
                    });
            }

            using var connection = context.Db(out var tenantId);

            var existingId = (string)Sql.Scalar(connection,
                "SELECT id FROM packages WHERE tenant_id = @p0 AND name = @p1 AND version = @p2",
                tenantId, name, version);

            var now = Sql.Now();
            var publisher = context.User()?.Username;
            var size = content?.LongLength ?? 0;

            if (existingId != null)
            {
                Sql.Exec(connection,
                    @"UPDATE packages
                         SET description = @p0, entry_point = @p1, published_by = @p2,
                             published_at = @p3, size_bytes = @p4,
                             content = COALESCE(@p5, content)
                       WHERE id = @p6",
                    body.Str("description"), body.Str("entryPoint"), publisher, now, size,
                    (object)content, existingId);
            }
            else
            {
                Sql.Exec(connection,
                    @"INSERT INTO packages
                        (id, tenant_id, name, version, description, entry_point, published_by, published_at, size_bytes, content)
                      VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, @p7, @p8, @p9)",
                    Sql.NewId(), tenantId, name, version, body.Str("description"),
                    body.Str("entryPoint"), publisher, now, size, (object)content);
            }

            // Menerbitkan paket hampir selalu berarti ingin proses dengan nama
            // yang sama tersedia untuk dijalankan. Membuatnya di sini menghemat
            // satu langkah yang mudah terlupa.
            var processExists = Sql.Count(connection,
                "SELECT COUNT(*) FROM processes WHERE tenant_id = @p0 AND name = @p1", tenantId, name) > 0;

            if (processExists)
            {
                Sql.Exec(connection,
                    "UPDATE processes SET package_name = @p0, package_version = @p1 WHERE tenant_id = @p2 AND name = @p3",
                    name, version, tenantId, name);
            }
            else
            {
                Sql.Exec(connection,
                    @"INSERT INTO processes
                        (id, tenant_id, name, package_name, package_version, environment, description, created_at)
                      VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, @p7)",
                    Sql.NewId(), tenantId, name, name, version,
                    body.Str("environment", "Production"), body.Str("description"), now);
            }

            ApiSupport.Alert(connection, tenantId, "Info",
                "Paket diterbitkan",
                name + " " + version + " diterbitkan oleh " + (publisher ?? "?") + ".", "packages");

            Sql.Exec(connection,
                @"INSERT INTO logs (tenant_id, level, message, process_name, logged_at)
                  VALUES (@p0, 'INFO', @p1, @p2, @p3)",
                tenantId, "Paket " + name + " " + version + " diterbitkan.", name, now);

            return Results.Ok(new { ok = true, name, version, sizeBytes = size });
        });

        // --------------------------------------------------------------
        app.MapGet("/api/packages/{name}/{version}/content", (HttpContext context, string name, string version) =>
        {
            using var connection = context.Db(out var tenantId);

            var row = Sql.Row(connection,
                "SELECT content, size_bytes FROM packages WHERE tenant_id = @p0 AND name = @p1 AND version = @p2",
                tenantId, name, version);

            if (row == null || row["content"] == null)
                return Results.NotFound(new { error = "Paket tidak ada atau tanpa isi." });

            return Results.File((byte[])row["content"], "application/zip", name + "." + version + ".zip");
        });

        // --------------------------------------------------------------
        app.MapDelete("/api/packages/{name}/{version}", (HttpContext context, string name, string version) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM packages WHERE tenant_id = @p0 AND name = @p1 AND version = @p2",
                tenantId, name, version);

            return removed == 0
                ? Results.NotFound(new { error = "Paket tidak ada." })
                : Results.Ok(new { ok = true });
        });
    }
}
