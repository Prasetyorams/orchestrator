using ForgeHub.Data;

namespace ForgeHub.Api;

/// <summary>
/// Penjalan pemicu terjadwal.
///
/// Diperiksa tiap 30 detik. Selang sependek itu terasa boros, tapi biayanya
/// satu kueri berindeks ke basis data lokal — dan alternatifnya, memeriksa tiap
/// menit, membuat pemicu yang dipasang untuk "tiap 1 menit" meleset separuh waktu.
///
/// Pemicu yang terlewat TIDAK dikejar. Kalau ForgeHub mati semalaman, yang
/// dijalankan saat menyala lagi adalah satu kali, bukan dua belas kali sekaligus
/// menumpuk di robot yang sama.
/// </summary>
public sealed class Scheduler : BackgroundService
{
    private static readonly TimeSpan Tick = TimeSpan.FromSeconds(30);

    private readonly ILogger<Scheduler> _logger;

    public Scheduler(ILogger<Scheduler> logger)
    {
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Jeda awal supaya pemeriksaan pertama tidak berebut dengan penyiapan
        // basis data saat ForgeHub baru dinyalakan.
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(5), stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                FireDueTriggers();
                ExpireStuckJobs();
            }
            catch (Exception ex)
            {
                // Satu putaran yang gagal tidak boleh menghentikan penjadwal
                // selamanya. Dicatat, lalu dicoba lagi pada putaran berikutnya.
                _logger.LogError(ex, "Penjadwal gagal pada satu putaran.");
            }

            try
            {
                await Task.Delay(Tick, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private void FireDueTriggers()
    {
        using var connection = Db.Open();

        var due = Sql.Rows(connection,
            @"SELECT id, tenant_id, name, process_name, robot_name, interval_minutes, cron, priority
                FROM triggers
               WHERE enabled = 1
                 AND next_run_at IS NOT NULL
                 AND next_run_at <= @p0",
            Sql.Now());

        foreach (var trigger in due)
        {
            var tenantId = (string)trigger["tenantId"];
            var process = (string)trigger["processName"];
            var name = (string)trigger["name"];
            var interval = Math.Max(1, Convert.ToInt32(trigger["intervalMinutes"]));
            var now = Sql.Now();

            // Proses bisa saja sudah dihapus setelah pemicunya dibuat. Pemicunya
            // dimatikan, bukan diam-diam gagal tiap 30 detik selamanya.
            var processExists = Sql.Count(connection,
                "SELECT COUNT(*) FROM processes WHERE tenant_id = @p0 AND name = @p1", tenantId, process) > 0;

            if (!processExists)
            {
                Sql.Exec(connection, "UPDATE triggers SET enabled = 0 WHERE id = @p0", trigger["id"]);

                ApiSupport.Alert(connection, tenantId, "Warning",
                    "Pemicu dimatikan",
                    "Pemicu '" + name + "' menunjuk proses '" + process + "' yang sudah tidak ada.", "triggers");

                continue;
            }

            // Waktu berikutnya: dari CRON kalau ada, kalau tidak dari selangnya.
            //
            // Cron menang karena lebih tepat: "tiap hari kerja pukul 07:00"
            // tidak bisa dinyatakan sebagai selang menit sama sekali.
            var cron = trigger["cron"] as string;
            string berikutnya;

            if (!string.IsNullOrWhiteSpace(cron))
            {
                var next = Cron.Next(cron, DateTime.UtcNow);

                if (next == null)
                {
                    // Ekspresi yang tidak sah tidak boleh membuat pemicunya
                    // mencoba lagi tiap 30 detik selamanya tanpa penjelasan.
                    Sql.Exec(connection, "UPDATE triggers SET enabled = 0 WHERE id = @p0", trigger["id"]);

                    ApiSupport.Alert(connection, tenantId, "Warning",
                        "Pemicu dimatikan",
                        "Pemicu '" + name + "' memakai ekspresi cron yang tidak sah: " + cron, "triggers");

                    continue;
                }

                berikutnya = next.Value.ToString("yyyy-MM-ddTHH:mm:ssZ");
            }
            else
            {
                berikutnya = DateTime.UtcNow.AddMinutes(interval).ToString("yyyy-MM-ddTHH:mm:ssZ");
            }
            var jobId = Sql.NewId();

            Sql.Exec(connection,
                @"INSERT INTO jobs
                    (id, tenant_id, process_name, robot_name, state, source, priority, progress, info, created_at)
                  VALUES (@p0, @p1, @p2, @p3, 'PENDING', 'Trigger', @p6, 0, @p4, @p5)",
                jobId, tenantId, process, trigger["robotName"],
                "Dijadwalkan oleh pemicu '" + name + "'.", now,
                trigger["priority"] ?? "Normal");

            Sql.Exec(connection,
                @"UPDATE triggers
                     SET last_run_at = @p0,
                         next_run_at = @p1
                   WHERE id = @p2",
                now, berikutnya, trigger["id"]);

            Sql.Exec(connection,
                @"INSERT INTO logs (tenant_id, level, message, process_name, job_id, logged_at)
                  VALUES (@p0, 'INFO', @p1, @p2, @p3, @p4)",
                tenantId, "Pemicu '" + name + "' menjadwalkan " + process + ".", process, jobId, now);
        }
    }

    /// <summary>
    /// Pekerjaan yang robotnya menghilang.
    ///
    /// Robot yang mati di tengah jalan tidak pernah melaporkan hasil akhir, jadi
    /// pekerjaannya akan berstatus RUNNING selamanya — dan kartu "berjalan" di
    /// dasbor terus menghitungnya. Setelah robotnya dinyatakan putus, pekerjaannya
    /// ditandai gagal, dengan sebab yang jelas.
    /// </summary>
    private void ExpireStuckJobs()
    {
        using var connection = Db.Open();

        var stuck = Sql.Rows(connection,
            $@"SELECT j.id, j.tenant_id, j.process_name, j.robot_name
                 FROM jobs j
                 LEFT JOIN robots r ON r.tenant_id = j.tenant_id AND r.name = j.robot_name
                WHERE j.state = 'RUNNING'
                  AND (r.last_heartbeat_at IS NULL
                       OR (julianday('now') - julianday(r.last_heartbeat_at)) * 86400.0
                           > {RobotsApi.OfflineAfterSeconds * 2})");

        foreach (var job in stuck)
        {
            var tenantId = (string)job["tenantId"];
            var robot = (string)job["robotName"] ?? "robot";

            Sql.Exec(connection,
                @"UPDATE jobs
                     SET state = 'FAULTED',
                         info = @p0,
                         ended_at = @p1
                   WHERE id = @p2 AND state = 'RUNNING'",
                "Robot '" + robot + "' berhenti berdenyut saat pekerjaan masih berjalan.",
                Sql.Now(), job["id"]);

            ApiSupport.Alert(connection, tenantId, "Error",
                "Pekerjaan terputus",
                (string)job["processName"] + " dihentikan karena robot '" + robot + "' tidak lagi terhubung.",
                "jobs");
        }
    }
}
