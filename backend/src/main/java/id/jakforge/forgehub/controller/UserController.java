package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.request.SaveUserRequest;
import id.jakforge.forgehub.dto.response.OkResponse;
import id.jakforge.forgehub.dto.response.SaveResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Pengguna. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return userService.findAll(principal);
    }

    /** Membuat, atau memperbarui pengguna bernama itu. */
    @PostMapping
    public SaveResponse save(@AuthenticationPrincipal ForgeHubPrincipal principal,
                             @Valid @RequestBody SaveUserRequest request) {
        return userService.save(principal, request);
    }

    @DeleteMapping("/{username}")
    public OkResponse delete(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String username) {
        userService.delete(principal, username);

        return OkResponse.success();
    }
}
