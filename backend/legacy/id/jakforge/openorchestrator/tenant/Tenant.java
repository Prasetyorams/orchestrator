package id.jakforge.openorchestrator.tenant;

import id.jakforge.openorchestrator.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tenants")
@Getter
@Setter
public class Tenant extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 64, unique = true)
    private String slug;

    @Column(nullable = false)
    private boolean active = true;
}
