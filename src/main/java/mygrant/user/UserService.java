package mygrant.user;

import java.util.Optional;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import mygrant.auth.JwtService;
import mygrant.common.InvalidCredentialsException;
import mygrant.user.dto.LoginResponse;
import mygrant.user.dto.UserCreation;
import mygrant.user.dto.UserLogin;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(
            UserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
        user.setCurrentLocation(userCreation.getCurrentLocation());
        user.setUpcomingTravelDate(userCreation.getUpcomingTravelDate());
        user.setRole(UserRole.APPLICANT);
        user.setPasswordHash(passwordEncoder.encode(userCreation.getUserPassword()));

        User savedUser = userRepository.save(user);
        String token = jwtService.generateToken(savedUser.getEmail());

        return new LoginResponse(savedUser, token);
    }

    public LoginResponse loginUser(UserLogin userLogin) {
        Optional<User> optionalUser = userRepository.findByEmail(
                userLogin.getUserEmail().trim().toLowerCase());

        if (optionalUser.isEmpty()) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        User user = optionalUser.get();

        if (!passwordEncoder.matches(userLogin.getUserPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getEmail());
        return new LoginResponse(user, token);
    }
}
