package id.jakforge.forgehub.user;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Pengguna ForgeHub.
 *
 * Dinamai AppUser, bukan User: "user" kata terpesan di PostgreSQL, dan
 * kelas bernama User juga bertabrakan dengan milik Spring Security di
 * setiap berkas yang mengimpor keduanya.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class AppUser extends TenantEntity {

    @Column(nullable = false, length = 80)
    private String username;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", length = 160)
    private String fullName;

    @Column(nullable = false, length = 32)
    private String role = "USER";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;
}
