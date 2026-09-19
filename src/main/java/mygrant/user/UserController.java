package mygrant.user;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import mygrant.user.dto.LoginResponse;
import mygrant.user.dto.UserCreation;
import mygrant.user.dto.UserLogin;

@CrossOrigin(origins = "*")
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<?> receiveRequest(@Valid @RequestBody UserCreation userCreation) {
        LoginResponse result = userService.createUser(userCreation);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody UserLogin userLogin) {
        LoginResponse response = userService.loginUser(userLogin);
        return ResponseEntity.ok(response);
    }
}
