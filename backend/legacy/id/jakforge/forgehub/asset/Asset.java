package id.jakforge.forgehub.asset;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "assets")
@Getter
@Setter
public class Asset extends TenantEntity {

    @Column(nullable = false, length = 160)
    private String name;

    /** TEXT, INTEGER, BOOLEAN, CREDENTIAL */
    @Column(name = "asset_type", nullable = false, length = 24)
    private String assetType = "TEXT";

    @Column(name = "value_text")
    private String valueText;

    @Column(length = 400)
    private String description;
}
