package id.jakforge.forgehub.robot;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "environments")
@Getter
@Setter
public class Environment extends TenantEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 400)
    private String description;
}
