package id.jakforge.forgehub.robot;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "credentials")
@Getter
@Setter
public class Credential extends TenantEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 160)
    private String username;

    /** Nilai TERENKRIPSI. Tidak pernah dikembalikan lewat API. */
    @Column(name = "secret_encrypted")
    private String secretEncrypted;
}
