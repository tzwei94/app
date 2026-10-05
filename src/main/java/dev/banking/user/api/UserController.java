package dev.banking.user.api;

import dev.banking.user.application.UserService;
import dev.banking.user.domain.UserProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
class UserController {
    record CreateUser(@NotNull @Pattern(regexp="[a-z][a-z0-9._-]{2,63}") String username,
        @NotBlank @Size(max=100) String displayName, @NotBlank @Size(min=12,max=72) String password) {}
    record UpdateUser(@NotNull @Pattern(regexp="[a-z][a-z0-9._-]{2,63}") String username,
        @NotBlank @Size(max=100) String displayName, @Size(min=12,max=72) String password) {}
    private final UserService service;
    UserController(UserService service) { this.service = service; }

    @PostMapping ResponseEntity<UserProfile> create(@Valid @RequestBody CreateUser input) {
        var profile = service.create(input.username(), input.displayName(), input.password());
        return ResponseEntity.created(URI.create("/users/" + profile.id())).header("Cache-Control", "no-store").body(profile);
    }
    @GetMapping ResponseEntity<List<UserProfile>> list(@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue="0") @Min(0) int offset) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(limit, offset));
    }
    @GetMapping("/{id}") ResponseEntity<UserProfile> get(@PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.get(id));
    }
    @PutMapping("/{id}") ResponseEntity<UserProfile> update(@PathVariable UUID id, @Valid @RequestBody UpdateUser input) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(service.update(id, input.username(), input.displayName(), input.password()));
    }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
