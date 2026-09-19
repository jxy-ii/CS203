package mygrant.user;

import mygrant.user.dto.LoginResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProfileController {

    private final UserRepository userRepository;

    public ProfileController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/api/v1/profiles/me")
    public ResponseEntity<LoginResponse> getCurrentProfile(Authentication authentication) {
        String email = authentication.getName();

        return userRepository.findByEmail(email)
                .map(user -> ResponseEntity.ok(new LoginResponse(user)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
