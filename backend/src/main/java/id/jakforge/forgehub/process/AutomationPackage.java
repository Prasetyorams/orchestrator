package id.jakforge.forgehub.process;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** Paket automasi yang diunggah dari Studio. */
@Entity
@Table(name = "packages")
@Getter
@Setter
public class AutomationPackage extends TenantEntity {

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 48)
    private String version;

    @Column(length = 400)
    private String description;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;
}
