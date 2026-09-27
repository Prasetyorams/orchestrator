package id.jakforge.forgehub.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Badan permintaan bertipe untuk endpoint dasbor: penormalan isian (pangkas,
 * kosong berarti tidak diisi, nilai bawaan) dan pesan validasinya.
 *
 * <p>Pesannya kalimat yang SAMA dengan yang dulu dilempar layanan — dasbor
 * menerjemahkannya lewat teks persisnya.
 */
class RequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    /** Pengaturan yang sama dengan ObjectMapper Spring: medan tak dikenal diabaikan. */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private static <T> List<String> violationsOf(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        return violations.stream().map(ConstraintViolation::getMessage).sorted().toList();
    }

    // ---------- profil dan kata sandi ----------

    @Test
    @DisplayName("nama tampilan wajib dan dipangkas; panjangnya mengikuti kolom")
    void profileDisplayName() {
        assertEquals(List.of("Nama tampilan wajib diisi."), violationsOf(new UpdateProfileRequest("   ", null)));
        assertEquals(List.of("Nama tampilan paling panjang 200 karakter."),
                violationsOf(new UpdateProfileRequest("x".repeat(201), null)));
        assertEquals(List.of("Alamat surel paling panjang 160 karakter."),
                violationsOf(new UpdateProfileRequest("Pras", "a".repeat(151) + "@contoh.id")));

        UpdateProfileRequest trimmed = new UpdateProfileRequest("  Pras  ", "  ");
        assertEquals("Pras", trimmed.displayName());
        assertNull(trimmed.email(), "surel kosong berarti dihapus");
        assertTrue(violationsOf(trimmed).isEmpty());
    }

    @Test
    @DisplayName("kata sandi saat ini wajib; kata sandi baru minimal 8 karakter; spasi tidak dipangkas")
    void changePassword() {
        assertEquals(List.of("Kata sandi saat ini wajib diisi."),
                violationsOf(new ChangePasswordRequest("", "sandi-baru-123")));
        assertEquals(List.of("Kata sandi baru minimal 8 karakter."),
                violationsOf(new ChangePasswordRequest("lama", "pendek")));
        assertEquals(List.of("Kata sandi baru minimal 8 karakter."),
                violationsOf(new ChangePasswordRequest("lama", null)));

        assertEquals(" spasi di tepi ", new ChangePasswordRequest(" spasi di tepi ", "x".repeat(8)).currentPassword());
    }

    // ---------- peran ----------

    @Test
    @DisplayName("nama peran wajib saat membuat, paling panjang 48 karakter; keterangan paling panjang 400")
    void roleLimits() {
        assertEquals(List.of("Nama peran wajib diisi."), violationsOf(new CreateRoleRequest(" ", null, null)));
        assertEquals(List.of("Nama peran paling panjang 48 karakter."),
                violationsOf(new CreateRoleRequest("r".repeat(49), null, null)));
        assertEquals(List.of("Keterangan paling panjang 400 karakter."),
                violationsOf(new CreateRoleRequest("Operator", "k".repeat(401), null)));

        // Mengubah tanpa nama berarti namanya tetap.
        assertTrue(violationsOf(new UpdateRoleRequest(null, null, null)).isEmpty());
        assertEquals(List.of("Nama peran paling panjang 48 karakter."),
                violationsOf(new UpdateRoleRequest("r".repeat(49), null, null)));
    }

    @Test
    @DisplayName("izin peran diterima sebagai larik JSON maupun untai dipisah koma")
    void permissionsFromArrayOrCommaSeparatedText() throws Exception {
        CreateRoleRequest fromArray = objectMapper.readValue(
                "{\"name\":\"Operator\",\"permissions\":[\"jobs.read\",null,\"jobs.create\"]}", CreateRoleRequest.class);
        CreateRoleRequest fromText = objectMapper.readValue(
                "{\"name\":\"Operator\",\"permissions\":\"jobs.read,jobs.create\"}", CreateRoleRequest.class);
        CreateRoleRequest withoutPermissions = objectMapper.readValue(
                "{\"name\":\"Operator\"}", CreateRoleRequest.class);

        assertEquals(List.of("jobs.read", "jobs.create"), fromArray.permissions());
        assertEquals(List.of("jobs.read", "jobs.create"), fromText.permissions());
        assertNull(withoutPermissions.permissions(), "tidak dikirim berbeda dari dikosongkan");
    }

    // ---------- nilai bawaan ----------

    @Test
    @DisplayName("pemicu: cron menentukan tipenya, dan yang tidak diisi mendapat nilai bawaan")
    void triggerDefaults() throws Exception {
        SaveTriggerRequest cronTrigger = objectMapper.readValue(
                "{\"name\":\" Harian \",\"processName\":\"Tagihan\",\"cron\":\" 0 7 * * 1-5 \",\"unknownField\":1}",
                SaveTriggerRequest.class);

        assertEquals("Harian", cronTrigger.name());
        assertEquals("0 7 * * 1-5", cronTrigger.cron());
        assertEquals("Cron", cronTrigger.type());
        assertTrue(cronTrigger.usesCron());
        assertEquals(60, cronTrigger.intervalMinutes());
        assertEquals("Normal", cronTrigger.priority());
        assertEquals("UTC", cronTrigger.timezone());
        assertEquals("Unattended", cronTrigger.runtimeType());
        assertTrue(cronTrigger.enabled());

        SaveTriggerRequest intervalTrigger = objectMapper.readValue(
                "{\"name\":\"Tiap15\",\"processName\":\"Tagihan\",\"intervalMinutes\":15,\"enabled\":false}",
                SaveTriggerRequest.class);

        assertEquals("Time", intervalTrigger.type());
        assertFalse(intervalTrigger.usesCron());
        assertEquals(15, intervalTrigger.intervalMinutes());
        assertFalse(intervalTrigger.enabled());

        assertEquals(List.of("Nama pemicu wajib diisi.", "processName wajib diisi."),
                violationsOf(objectMapper.readValue("{}", SaveTriggerRequest.class)));
    }

    @Test
    @DisplayName("antrean: percobaan ulang bawaan 3, tidak boleh negatif")
    void queueDefaults() {
        CreateQueueRequest queue = new CreateQueueRequest(" Tagihan ", null, null, null, null);

        assertEquals("Tagihan", queue.name());
        assertEquals(3, queue.maxRetries());
        assertFalse(queue.acceptDuplicates());

        assertEquals(List.of("Jumlah percobaan ulang tidak boleh negatif."),
                violationsOf(new CreateQueueRequest("Tagihan", null, -1, null, null)));
    }

    @Test
    @DisplayName("pengguna: isActive tidak dikirim berarti aktif; nama pengguna dipangkas")
    void userDefaults() throws Exception {
        SaveUserRequest inactive = objectMapper.readValue(
                "{\"username\":\" budi \",\"isActive\":false,\"password\":\"\"}", SaveUserRequest.class);
        SaveUserRequest withoutFlag = objectMapper.readValue("{\"username\":\"budi\"}", SaveUserRequest.class);

        assertEquals("budi", inactive.username());
        assertFalse(inactive.isActive());
        assertNull(inactive.password(), "kata sandi kosong berarti tidak diganti");
        assertTrue(withoutFlag.isActive());

        assertEquals(List.of("Nama pengguna wajib diisi."), violationsOf(new SaveUserRequest(" ", null, null, null,
                null, null)));
    }

    @Test
    @DisplayName("aset: untai kosong berarti tidak diisi; tipe dan cakupan bawaan Text dan Global")
    void assetDefaults() throws Exception {
        SaveAssetRequest asset = objectMapper.readValue(
                "{\"name\":\"Alamat\",\"type\":\"\",\"value\":\"\",\"folderId\":\"\"}", SaveAssetRequest.class);

        assertEquals("Text", asset.type());
        assertEquals("Global", asset.scope());
        assertNull(asset.value());
        assertNull(asset.folderId());
    }

    @Test
    @DisplayName("pesan wajib-diisi untuk tiap sumber sama dengan pesan layanan sebelumnya")
    void requiredNameMessages() {
        assertEquals(List.of("Nama proses wajib diisi."),
                violationsOf(new SaveProcessRequest(null, null, null, null, null, null)));
        assertEquals(List.of("Nama antrean wajib diisi."),
                violationsOf(new CreateQueueRequest(null, null, null, null, null)));
        assertEquals(List.of("Nama aset wajib diisi."),
                violationsOf(new SaveAssetRequest(null, null, null, null, null, null, null)));
        assertEquals(List.of("Nama kredensial wajib diisi."),
                violationsOf(new SaveCredentialRequest(null, null, null, null)));
        assertEquals(List.of("Nama ember wajib diisi."), violationsOf(new CreateBucketRequest(null, null, null)));
        assertEquals(List.of("Nama robot wajib diisi."),
                violationsOf(new CreateRobotRequest(null, null, null, null, null, null)));
        assertEquals(List.of("Nama mesin wajib diisi."), violationsOf(new CreateMachineRequest(null, null, null, null)));
        assertEquals(List.of("Nama lingkungan wajib diisi."), violationsOf(new CreateEnvironmentRequest(null, null)));
        assertEquals(List.of("folderId wajib diisi."), violationsOf(new MoveToFolderRequest("  ")));
        assertEquals(List.of("username wajib diisi."), violationsOf(new AssignUserRequest(null)));
        assertEquals(List.of("robotName wajib diisi."), violationsOf(new AssignRobotRequest(null)));
    }

    @Test
    @DisplayName("unggahan: isi kosong menjadi untai kosong, jenis isi bawaan application/octet-stream")
    void uploadDefaults() {
        UploadFileRequest upload = new UploadFileRequest(" laporan.xlsx ", null, "");

        assertEquals("laporan.xlsx", upload.fileName());
        assertEquals("", upload.contentBase64());
        assertEquals("application/octet-stream", upload.contentType());
    }
}
