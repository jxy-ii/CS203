package mygrant.user;

import mygrant.user.dto.LoginResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import mygrant.user.dto.ProfileCompletion;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProfileController {

    private final UserRepository userRepository;
    private final UserService userService;

    public ProfileController(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @GetMapping("/api/v1/profiles/me")
    public ResponseEntity<LoginResponse> getCurrentProfile(Authentication authentication) {
        String email = authentication.getName();

        return userRepository.findByEmail(email)
                .map(user -> ResponseEntity.ok(new LoginResponse(user)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/api/v1/profiles/me")
    public ResponseEntity<LoginResponse> completeProfile(
            Authentication authentication, @Valid @RequestBody ProfileCompletion profile) {
        return ResponseEntity.ok(userService.completeProfile(authentication.getName(), profile));
    }
}
