package mygrant.user;

import mygrant.user.dto.UserCreation;
import mygrant.user.dto.UserLogin;
import mygrant.user.dto.LoginResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "*")
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/api/users")
    public ResponseEntity<?> receiveRequest(@RequestBody UserCreation userCreation) {
        try {
            LoginResponse result = userService.createUser(userCreation);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/api/login")
    public ResponseEntity<?> login(@RequestBody UserLogin userLogin) {
        try {
            LoginResponse response = userService.loginUser(userLogin);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(e.getMessage());
        }
    }
}