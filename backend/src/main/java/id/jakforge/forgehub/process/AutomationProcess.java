package id.jakforge.forgehub.process;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Proses: satu paket yang siap dijalankan di sebuah lingkungan.
 *
 * Dinamai AutomationProcess, bukan Process, karena java.lang.Process ada
 * di setiap berkas tanpa perlu diimpor — kelas bernama Process akan
 * menutupinya diam-diam.
 */
@Entity
@Table(name = "processes")
@Getter
@Setter
public class AutomationProcess extends TenantEntity {

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 400)
    private String description;

    @Column(name = "package_id")
    private UUID packageId;

    @Column(name = "environment_id")
    private UUID environmentId;

    @Column(name = "entry_point", nullable = false, length = 255)
    private String entryPoint = "Main.xaml";
}
