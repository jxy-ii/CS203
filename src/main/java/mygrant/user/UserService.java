package mygrant.user;

import java.util.Optional;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import mygrant.user.dto.LoginResponse;
import mygrant.user.dto.UserCreation;
import mygrant.user.dto.UserLogin;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResponse createUser(UserCreation userCreation) {

        String email = userCreation.getUserEmail().trim().toLowerCase();
        String userName = userCreation.getUserName().trim();

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }

        User user = new User();
        user.setFullName(userName);
        user.setEmail(email);
        user.setVisaType(userCreation.getVisaType());
        user.setAcademicLevel(userCreation.getAcademicLevel());
        user.setProgramEndDate(userCreation.getProgramEndDate());
        user.setRole(UserRole.APPLICANT); 

        String hashedPassword = passwordEncoder.encode(userCreation.getUserPassword());
        user.setPasswordHash(hashedPassword);

        User savedUser = userRepository.save(user);
        return new LoginResponse(savedUser);
    }

    public LoginResponse loginUser(UserLogin userLogin) {
        String userPassword = userLogin.getUserPassword();

        Optional<User> optionalUser =
        userRepository.findByEmail(userLogin.getUserEmail().trim().toLowerCase());

        if (optionalUser.isEmpty()) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        User user = optionalUser.get();

        if (!passwordEncoder.matches(userPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        return new LoginResponse(user);
    }
}