using System.Text.Json;
using System.Text.Json.Serialization;
using ForgeHub.Api;
using ForgeHub.Auth;
using ForgeHub.Data;

/// <summary>
/// Titik masuk ForgeHub.
/// </summary>
public static class Program
{
    /// <summary>Tempat basis data dan kunci penanda tangan disimpan.</summary>
    public static string DataDirectory { get; private set; }

    public static void Main(string[] args)
    {
        // Data TIDAK diletakkan di sebelah program.
        //
        // Folder program bisa ditimpa saat pembaruan, dan pada pemasangan di
        // Program Files ia tidak bisa ditulisi tanpa hak administrator. LocalAppData
        // selalu bisa ditulis oleh pemiliknya dan tidak ikut terhapus saat program
        // dibangun ulang.
        DataDirectory = Environment.GetEnvironmentVariable("FORGEHUB_DATA")
            ?? Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "JakForge", "ForgeHub");

        Directory.CreateDirectory(DataDirectory);

        // Urutannya penting: Tokens membuat signing.key kalau belum ada, dan
        // Secrets menurunkan kunci penyandiannya dari berkas itu.
        Tokens.Init(DataDirectory);
        Secrets.Init(DataDirectory);
        Db.Init(DataDirectory);

        var builder = WebApplication.CreateBuilder(args);

        builder.Services.ConfigureHttpJsonOptions(options =>
        {
            options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
            options.SerializerOptions.DefaultIgnoreCondition = JsonIgnoreCondition.Never;
        });

        builder.Services.AddHostedService<Scheduler>();

        // Studio dan JakRunner memanggil dari luar peramban dan tidak terkena
        // aturan CORS sama sekali. Yang butuh ini adalah dasbor kalau nanti
        // disajikan dari alamat lain saat pengembangan.
        builder.Services.AddCors(options =>
            options.AddDefaultPolicy(policy => policy
                .AllowAnyOrigin()
                .AllowAnyHeader()
                .AllowAnyMethod()));

        builder.WebHost.UseUrls(
            Environment.GetEnvironmentVariable("FORGEHUB_URL") ?? "http://localhost:8080");

        var app = builder.Build();

        app.UseCors();
        app.UseDefaultFiles();
        app.UseStaticFiles();

        app.UseForgeHubAuth();

        app.MapAuth();
        app.MapDashboard();
        app.MapRobots();
        app.MapProcesses();
        app.MapJobs();
        app.MapQueues();
        app.MapAssets();
        app.MapLogs();
        app.MapAdmin();

        // Dasbor adalah satu halaman yang mengatur tampilannya sendiri, jadi
        // alamat apa pun yang bukan /api dikembalikan ke halaman itu — memuat
        // ulang di /robots tidak boleh berakhir 404.
        app.MapFallbackToFile("index.html");

        Console.WriteLine();
        Console.WriteLine("  ForgeHub siap.");
        Console.WriteLine("  Dasbor : " + (Environment.GetEnvironmentVariable("FORGEHUB_URL") ?? "http://localhost:8080"));
        Console.WriteLine("  Masuk  : " + Seed.DefaultUser + " / " + Seed.DefaultPassword);
        Console.WriteLine("  Data   : " + DataDirectory);
        Console.WriteLine();

        app.Run();
    }
}
