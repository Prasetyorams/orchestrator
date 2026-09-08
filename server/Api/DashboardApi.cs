using ForgeHub.Data;

namespace ForgeHub.Api;

public static class DashboardApi
{
    public static void MapDashboard(this WebApplication app)
    {
        // --------------------------------------------------------------
        // Seluruh isi halaman utama dalam SATU permintaan.
        //
        // Dasbor menyegarkan dirinya tiap beberapa detik. Sepuluh permintaan
        // terpisah berarti sepuluh kali membuka basis data tiap penyegaran, dan
        // angka-angkanya bisa berasal dari sepuluh saat yang berbeda — kartu
        // "berjalan" tidak cocok dengan tabel di bawahnya.
        app.MapGet("/api/dashboard", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var offline = RobotsApi.OfflineAfterSeconds;

            // Batas "hari ini" memakai hari setempat server, bukan UTC. Orang
            // yang melihat dasbor jam 7 pagi memaksudkan pagi ini, dan di
            // Indonesia UTC masih kemarin sore pada jam itu.
            var startOfDay = DateTime.Today.ToUniversalTime().ToString("o");

            var robots = new
            {
                total = Sql.Count(connection, "SELECT COUNT(*) FROM robots WHERE tenant_id = @p0", tenantId),

                available = Sql.Count(connection,
                    $@"SELECT COUNT(*) FROM robots
                        WHERE tenant_id = @p0 AND status = 'AVAILABLE'
                          AND last_heartbeat_at IS NOT NULL
                          AND (julianday('now') - julianday(last_heartbeat_at)) * 86400.0 <= {offline}",
                    tenantId),

                busy = Sql.Count(connection,
                    $@"SELECT COUNT(*) FROM robots
                        WHERE tenant_id = @p0 AND status = 'BUSY'
                          AND last_heartbeat_at IS NOT NULL
                          AND (julianday('now') - julianday(last_heartbeat_at)) * 86400.0 <= {offline}",
                    tenantId),

                disconnected = Sql.Count(connection,
                    $@"SELECT COUNT(*) FROM robots
                        WHERE tenant_id = @p0
                          AND (last_heartbeat_at IS NULL
                               OR (julianday('now') - julianday(last_heartbeat_at)) * 86400.0 > {offline})",
                    tenantId),
            };

            var jobs = new
            {
                running = Sql.Count(connection,
                    "SELECT COUNT(*) FROM jobs WHERE tenant_id = @p0 AND state = 'RUNNING'", tenantId),

                pending = Sql.Count(connection,
                    "SELECT COUNT(*) FROM jobs WHERE tenant_id = @p0 AND state = 'PENDING'", tenantId),

                successfulToday = Sql.Count(connection,
                    "SELECT COUNT(*) FROM jobs WHERE tenant_id = @p0 AND state = 'SUCCESSFUL' AND ended_at >= @p1",
                    tenantId, startOfDay),

                faultedToday = Sql.Count(connection,
                    "SELECT COUNT(*) FROM jobs WHERE tenant_id = @p0 AND state = 'FAULTED' AND ended_at >= @p1",
                    tenantId, startOfDay),

                totalToday = Sql.Count(connection,
                    "SELECT COUNT(*) FROM jobs WHERE tenant_id = @p0 AND created_at >= @p1",
                    tenantId, startOfDay),
            };

            var queues = new
            {
                total = Sql.Count(connection, "SELECT COUNT(*) FROM queues WHERE tenant_id = @p0", tenantId),

                newItems = Sql.Count(connection,
                    "SELECT COUNT(*) FROM queue_items WHERE tenant_id = @p0 AND status = 'NEW'", tenantId),

                inProgress = Sql.Count(connection,
                    "SELECT COUNT(*) FROM queue_items WHERE tenant_id = @p0 AND status = 'IN_PROGRESS'", tenantId),

                failed = Sql.Count(connection,
                    "SELECT COUNT(*) FROM queue_items WHERE tenant_id = @p0 AND status = 'FAILED'", tenantId),
            };

            var library = new
            {
                processes = Sql.Count(connection, "SELECT COUNT(*) FROM processes WHERE tenant_id = @p0", tenantId),
                packages = Sql.Count(connection, "SELECT COUNT(*) FROM packages WHERE tenant_id = @p0", tenantId),
                assets = Sql.Count(connection, "SELECT COUNT(*) FROM assets WHERE tenant_id = @p0", tenantId),
                machines = Sql.Count(connection, "SELECT COUNT(*) FROM machines WHERE tenant_id = @p0", tenantId),

                triggers = Sql.Count(connection,
                    "SELECT COUNT(*) FROM triggers WHERE tenant_id = @p0 AND enabled = 1", tenantId),
            };

            // Tingkat keberhasilan hari ini. Dihitung dari pekerjaan yang sudah
            // SELESAI saja — memasukkan yang masih berjalan ke penyebut membuat
            // angkanya turun tiap kali pekerjaan baru dimulai, seolah ada yang gagal.
            var finishedToday = jobs.successfulToday + jobs.faultedToday;
            var successRate = finishedToday == 0 ? 100.0
                : Math.Round(jobs.successfulToday * 100.0 / finishedToday, 1);

            var jobsInProgress = Sql.Rows(connection,
                @"SELECT id, process_name, robot_name, machine_name, state, source, priority,
                         progress, info, created_at, started_at
                    FROM jobs
                   WHERE tenant_id = @p0 AND state IN ('RUNNING', 'PENDING', 'STOPPING')
                   ORDER BY CASE state WHEN 'RUNNING' THEN 0 WHEN 'STOPPING' THEN 1 ELSE 2 END,
                            created_at
                   LIMIT 25",
                tenantId);

            var activeRobots = Sql.Rows(connection,
                $@"SELECT name, machine_name, type, environment, cpu_percent, memory_mb, last_heartbeat_at,
                          CASE
                              WHEN last_heartbeat_at IS NULL THEN 'DISCONNECTED'
                              WHEN (julianday('now') - julianday(last_heartbeat_at)) * 86400.0 > {offline}
                                  THEN 'DISCONNECTED'
                              ELSE status
                          END AS status,
                          (SELECT COUNT(*) FROM jobs j
                            WHERE j.tenant_id = r.tenant_id AND j.robot_name = r.name
                              AND j.state = 'RUNNING') AS running_jobs
                     FROM robots r
                    WHERE tenant_id = @p0
                    ORDER BY CASE
                                 WHEN last_heartbeat_at IS NULL THEN 2
                                 WHEN (julianday('now') - julianday(last_heartbeat_at)) * 86400.0 > {offline} THEN 2
                                 ELSE 0
                             END,
                             name
                    LIMIT 25",
                tenantId);

            var upcomingTriggers = Sql.Rows(connection,
                @"SELECT name, process_name, type, interval_minutes, enabled, next_run_at, last_run_at
                    FROM triggers
                   WHERE tenant_id = @p0 AND enabled = 1
                   ORDER BY next_run_at
                   LIMIT 10",
                tenantId);

            var recentAlerts = Sql.Rows(connection,
                @"SELECT id, severity, title, message, source, is_read, created_at
                    FROM alerts WHERE tenant_id = @p0
                   ORDER BY id DESC LIMIT 8",
                tenantId);

            var unreadAlerts = Sql.Count(connection,
                "SELECT COUNT(*) FROM alerts WHERE tenant_id = @p0 AND is_read = 0", tenantId);

            var queueSummary = Sql.Rows(connection,
                @"SELECT q.name,
                         (SELECT COUNT(*) FROM queue_items i
                           WHERE i.tenant_id = q.tenant_id AND i.queue_name = q.name AND i.status = 'NEW') AS new_count,
                         (SELECT COUNT(*) FROM queue_items i
                           WHERE i.tenant_id = q.tenant_id AND i.queue_name = q.name AND i.status = 'IN_PROGRESS') AS in_progress_count,
                         (SELECT COUNT(*) FROM queue_items i
                           WHERE i.tenant_id = q.tenant_id AND i.queue_name = q.name AND i.status = 'SUCCESSFUL') AS successful_count,
                         (SELECT COUNT(*) FROM queue_items i
                           WHERE i.tenant_id = q.tenant_id AND i.queue_name = q.name AND i.status = 'FAILED') AS failed_count
                    FROM queues q
                   WHERE q.tenant_id = @p0
                   ORDER BY q.name LIMIT 8",
                tenantId);

            return Results.Ok(new
            {
                robots,
                jobs,
                queues,
                library,
                successRate,
                unreadAlerts,
                jobsInProgress,
                activeRobots,
                upcomingTriggers,
                recentAlerts,
                queueSummary,
                serverTime = Sql.Now(),
            });
        });

        // --------------------------------------------------------------
        // Riwayat 14 hari untuk grafik ringkas.
        app.MapGet("/api/dashboard/history", (HttpContext context) =>
        {
            using var connection = context.Db(out var tenantId);

            var rows = Sql.Rows(connection,
                @"SELECT date(ended_at) AS day,
                         SUM(CASE WHEN state = 'SUCCESSFUL' THEN 1 ELSE 0 END) AS successful,
                         SUM(CASE WHEN state = 'FAULTED' THEN 1 ELSE 0 END) AS faulted
                    FROM jobs
                   WHERE tenant_id = @p0 AND ended_at IS NOT NULL
                     AND ended_at >= datetime('now', '-14 days')
                   GROUP BY date(ended_at)
                   ORDER BY day",
                tenantId);

            return Results.Ok(rows);
        });

        // --------------------------------------------------------------
        // Pencarian menyeluruh untuk kotak "Global Search" di kepala halaman.
        app.MapGet("/api/search", (HttpContext context) =>
        {
            var query = context.Request.Query["q"].ToString();

            if (string.IsNullOrWhiteSpace(query) || query.Length < 2)
                return Results.Ok(Array.Empty<object>());

            using var connection = context.Db(out var tenantId);

            var pattern = "%" + query + "%";

            // UNION ALL di satu kueri: satu perjalanan ke basis data, dan
            // hasilnya sudah tercampur rapi menurut jenisnya.
            var rows = Sql.Rows(connection,
                @"SELECT 'Proses' AS kind, name AS label, COALESCE(description, '') AS detail, 'processes' AS page
                    FROM processes WHERE tenant_id = @p0 AND name LIKE @p1
                  UNION ALL
                  SELECT 'Robot', name, COALESCE(machine_name, ''), 'robots'
                    FROM robots WHERE tenant_id = @p0 AND name LIKE @p1
                  UNION ALL
                  SELECT 'Antrean', name, COALESCE(description, ''), 'queues'
                    FROM queues WHERE tenant_id = @p0 AND name LIKE @p1
                  UNION ALL
                  SELECT 'Aset', name, COALESCE(description, ''), 'assets'
                    FROM assets WHERE tenant_id = @p0 AND name LIKE @p1
                  UNION ALL
                  SELECT 'Pemicu', name, process_name, 'triggers'
                    FROM triggers WHERE tenant_id = @p0 AND name LIKE @p1
                  UNION ALL
                  SELECT 'Paket', name || ' ' || version, COALESCE(description, ''), 'packages'
                    FROM packages WHERE tenant_id = @p0 AND name LIKE @p1
                  LIMIT 30",
                tenantId, pattern);

            return Results.Ok(rows);
        });
    }
}
