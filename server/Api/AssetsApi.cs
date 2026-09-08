using ForgeHub.Auth;
using ForgeHub.Data;

namespace ForgeHub.Api;

public static class AssetsApi
{
    /// <summary>Batas ukuran satu berkas di gudang: 32 MB.</summary>
    private const long MaxFileBytes = 32L * 1024 * 1024;

    public static void MapAssets(this WebApplication app)
    {
        // --------------------------------------------------------------
        // Aset bertipe Credential/Secret TIDAK menampilkan nilainya di daftar;
        // yang muncul hanya penanda bahwa isinya ada.
        app.MapGet("/api/assets", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT id, name, type, scope, description, created_at, updated_at,
                         CASE WHEN type IN ('Credential', 'Secret') THEN NULL ELSE value_text END AS value_text,
                         CASE WHEN value_text IS NULL OR value_text = '' THEN 0 ELSE 1 END AS has_value
                    FROM assets
                   WHERE tenant_id = @p0
                   ORDER BY name",
                tenantId);

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        app.MapPost("/api/assets", async (HttpContext context) =>
        {
            var body = await context.Body();
            var name = body.Str("name");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama aset wajib diisi." });

            var type = body.Str("type", "Text");
            var value = body.Str("value");

            var allowed = new[] { "Text", "Integer", "Bool", "Credential", "Secret" };
            if (!allowed.Contains(type))
                return Results.BadRequest(new { error = "Tipe aset tidak dikenal: '" + type + "'." });

            // Nilai bertipe angka dan boolean diperiksa di sini, bukan dibiarkan
            // meledak nanti di dalam robot yang sedang berjalan.
            if (type == "Integer" && !string.IsNullOrEmpty(value) && !long.TryParse(value, out _))
                return Results.BadRequest(new { error = "Aset bertipe Integer harus berisi bilangan bulat." });

            if (type == "Bool" && !string.IsNullOrEmpty(value) &&
                !bool.TryParse(value, out _))
                return Results.BadRequest(new { error = "Aset bertipe Bool harus berisi true atau false." });

            var stored = type is "Credential" or "Secret" ? Secrets.Protect(value) : value;

            using var connection = context.Db(out var tenantId);
            var now = Sql.Now();

            var existing = (string)Sql.Scalar(connection,
                "SELECT id FROM assets WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            if (existing != null)
            {
                Sql.Exec(connection,
                    @"UPDATE assets
                         SET type = @p0, value_text = @p1, description = @p2, scope = @p3, updated_at = @p4
                       WHERE id = @p5",
                    type, stored, body.Str("description"), body.Str("scope", "Global"), now, existing);
            }
            else
            {
                Sql.Exec(connection,
                    @"INSERT INTO assets (id, tenant_id, name, type, value_text, description, scope, created_at, updated_at)
                      VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, @p7, @p7)",
                    Sql.NewId(), tenantId, name, type, stored,
                    body.Str("description"), body.Str("scope", "Global"), now);
            }

            return Results.Ok(new { ok = true, created = existing == null });
        });

        // --------------------------------------------------------------
        // Pengambilan nilai untuk robot. Aset rahasia dibuka di sini SAJA.
        app.MapGet("/api/assets/{name}/value", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var row = Sql.Row(connection,
                "SELECT type, value_text FROM assets WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            if (row == null) return Results.NotFound(new { error = "Aset '" + name + "' tidak ada." });

            var type = (string)row["type"];
            var raw = (string)row["valueText"];
            var value = type is "Credential" or "Secret" ? Secrets.Unprotect(raw) : raw;

            return Results.Ok(new { name, type, value });
        });

        app.MapDelete("/api/assets/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM assets WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            return removed == 0 ? Results.NotFound(new { error = "Aset tidak ada." }) : Results.Ok(new { ok = true });
        });

        MapBuckets(app);
    }

    // ------------------------------------------------------------------

    private static void MapBuckets(WebApplication app)
    {
        app.MapGet("/api/buckets", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT b.id, b.name, b.description, b.created_at,
                         (SELECT COUNT(*) FROM bucket_files f
                           WHERE f.tenant_id = b.tenant_id AND f.bucket_name = b.name) AS file_count,
                         (SELECT COALESCE(SUM(f.size_bytes), 0) FROM bucket_files f
                           WHERE f.tenant_id = b.tenant_id AND f.bucket_name = b.name) AS total_bytes
                    FROM buckets b
                   WHERE b.tenant_id = @p0
                   ORDER BY b.name",
                tenantId);

            return Results.Ok(rows);
        });

        app.MapPost("/api/buckets", async (HttpContext context) =>
        {
            var body = await context.Body();
            var name = body.Str("name");

            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Nama gudang wajib diisi." });

            using var connection = context.Db(out var tenantId);

            if (Sql.Count(connection,
                    "SELECT COUNT(*) FROM buckets WHERE tenant_id = @p0 AND name = @p1", tenantId, name) > 0)
                return Results.Conflict(new { error = "Gudang '" + name + "' sudah ada." });

            Sql.Exec(connection,
                "INSERT INTO buckets (id, tenant_id, name, description, created_at) VALUES (@p0, @p1, @p2, @p3, @p4)",
                Sql.NewId(), tenantId, name, body.Str("description"), Sql.Now());

            return Results.Ok(new { ok = true });
        });

        app.MapDelete("/api/buckets/{name}", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            Sql.Exec(connection, "DELETE FROM bucket_files WHERE tenant_id = @p0 AND bucket_name = @p1", tenantId, name);

            var removed = Sql.Exec(connection,
                "DELETE FROM buckets WHERE tenant_id = @p0 AND name = @p1", tenantId, name);

            return removed == 0 ? Results.NotFound(new { error = "Gudang tidak ada." }) : Results.Ok(new { ok = true });
        });

        // --------------------------------------------------------------
        app.MapGet("/api/buckets/{name}/files", (HttpContext context, string name) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT id, file_name, content_type, size_bytes, uploaded_by, uploaded_at
                    FROM bucket_files
                   WHERE tenant_id = @p0 AND bucket_name = @p1
                   ORDER BY uploaded_at DESC",
                tenantId, name);

            return Results.Ok(rows);
        });

        app.MapPost("/api/buckets/{name}/files", async (HttpContext context, string name) =>
        {
            var body = await context.Body();

            var fileName = body.Str("fileName");
            if (string.IsNullOrWhiteSpace(fileName))
                return Results.BadRequest(new { error = "fileName wajib diisi." });

            // Nama berkas datang dari luar dan dipakai sebagai nama unduhan.
            // Pemisah jalur dibuang supaya tidak ada yang bisa menulis "../"
            // ke dalamnya dan mengubah tempat berkas itu mendarat di komputer
            // orang yang mengunduhnya.
            fileName = Path.GetFileName(fileName.Replace('\\', '/'));

            if (string.IsNullOrWhiteSpace(fileName))
                return Results.BadRequest(new { error = "fileName tidak sah." });

            byte[] content;
            try
            {
                content = Convert.FromBase64String(body.Str("contentBase64") ?? "");
            }
            catch (FormatException)
            {
                return Results.BadRequest(new { error = "contentBase64 bukan base64 yang sah." });
            }

            if (content.LongLength > MaxFileBytes)
                return Results.BadRequest(new
                {
                    error = "Berkas terlalu besar. Batasnya " + (MaxFileBytes / 1024 / 1024) + " MB.",
                });

            using var connection = context.Db(out var tenantId);

            if (Sql.Count(connection,
                    "SELECT COUNT(*) FROM buckets WHERE tenant_id = @p0 AND name = @p1", tenantId, name) == 0)
                return Results.NotFound(new { error = "Gudang '" + name + "' tidak ada." });

            Sql.Exec(connection,
                "DELETE FROM bucket_files WHERE tenant_id = @p0 AND bucket_name = @p1 AND file_name = @p2",
                tenantId, name, fileName);

            Sql.Exec(connection,
                @"INSERT INTO bucket_files
                    (id, tenant_id, bucket_name, file_name, content_type, size_bytes, uploaded_by, uploaded_at, content)
                  VALUES (@p0, @p1, @p2, @p3, @p4, @p5, @p6, @p7, @p8)",
                Sql.NewId(), tenantId, name, fileName,
                body.Str("contentType", "application/octet-stream"),
                content.LongLength, context.User()?.Username, Sql.Now(), content);

            return Results.Ok(new { ok = true, fileName, sizeBytes = content.LongLength });
        });

        app.MapGet("/api/buckets/{name}/files/{id}/content", (HttpContext context, string name, string id) =>
        {
            using var connection = context.Db(out var tenantId);

            var row = Sql.Row(connection,
                @"SELECT file_name, content_type, content FROM bucket_files
                   WHERE tenant_id = @p0 AND bucket_name = @p1 AND id = @p2",
                tenantId, name, id);

            if (row == null || row["content"] == null)
                return Results.NotFound(new { error = "Berkas tidak ada." });

            return Results.File((byte[])row["content"],
                (string)row["contentType"] ?? "application/octet-stream",
                (string)row["fileName"]);
        });

        app.MapDelete("/api/buckets/{name}/files/{id}", (HttpContext context, string name, string id) =>
        {
            using var connection = context.Db(out var tenantId);

            var removed = Sql.Exec(connection,
                "DELETE FROM bucket_files WHERE tenant_id = @p0 AND bucket_name = @p1 AND id = @p2",
                tenantId, name, id);

            return removed == 0 ? Results.NotFound(new { error = "Berkas tidak ada." }) : Results.Ok(new { ok = true });
        });
    }
}
