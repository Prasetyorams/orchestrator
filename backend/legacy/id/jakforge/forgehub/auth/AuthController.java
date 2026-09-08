package id.jakforge.forgehub.auth;

import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.security.JwtService;
import id.jakforge.forgehub.user.AppUser;
import id.jakforge.forgehub.user.AppUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        var found = users.findByUsernameIgnoreCase(request.username());

        // Pesannya SENGAJA sama untuk "pengguna tidak ada" dan "sandi salah".
        // Membedakannya memberi tahu penebak sandi bahwa nama penggunanya
        // sudah benar, dan itu memotong separuh pekerjaannya.
        if (found.isEmpty() || !found.get().isActive()
                || !passwordEncoder.matches(request.password(), found.get().getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Nama pengguna atau kata sandi salah."));
        }

        AppUser user = found.get();
        user.setLastLoginAt(OffsetDateTime.now());
        users.save(user);

        String token = jwtService.issue(user.getId(), user.getTenantId(), user.getUsername(), user.getRole());

        return ResponseEntity.ok(new AuthDtos.LoginResponse(
                token,
                jwtService.getExpirationMinutes(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                user.getTenantId().toString()));
    }

    @GetMapping("/me")
    public AuthDtos.MeResponse me() {
        var principal = CurrentUser.get();
        return new AuthDtos.MeResponse(
                principal.userId().toString(),
                principal.tenantId().toString(),
                principal.username(),
                principal.role());
    }
}
