package id.jakforge.forgehub.robot;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "machines")
@Getter
@Setter
public class Machine extends TenantEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "host_name", length = 160)
    private String hostName;

    @Column(name = "license_key", length = 120)
    private String licenseKey;
}
