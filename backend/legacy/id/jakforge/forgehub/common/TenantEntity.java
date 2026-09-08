package id.jakforge.forgehub.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Entity yang dimiliki sebuah tenant.
 *
 * tenant_id disimpan sebagai kolom biasa, bukan relasi, supaya setiap
 * kueri bisa menyaringnya langsung tanpa join — penyaringan yang butuh
 * join lebih mudah terlupa, dan yang terlupa di sini berarti data satu
 * pelanggan terlihat oleh pelanggan lain.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class TenantEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;
}
