package mygrant.user;

import mygrant.user.dto.UserCreation;
import mygrant.user.dto.UserLogin;
import mygrant.user.dto.LoginResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResponse createUser(UserCreation userCreation) {
        checkUserEmailValidity(userCreation);
        checkUserNameValidity(userCreation);
        checkPasswordValidity(userCreation);

        String email = userCreation.getUserEmail().trim();
        String userName = userCreation.getUserName().trim();

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }
        if (userRepository.existsByFullName(userName)) {
            throw new IllegalArgumentException("Username already exists");
        }

        User user = new User();
        user.setFullName(userName);
        user.setEmail(email);
        user.setVisaType(userCreation.getVisaType());
        user.setAcademicLevel(userCreation.getAcademicLevel());
        user.setProgramEndDate(userCreation.getProgramEndDate());

        String hashedPassword = passwordEncoder.encode(userCreation.getUserPassword());
        user.setPasswordHash(hashedPassword);

        User savedUser = userRepository.save(user);
        return new LoginResponse(savedUser);
    }

    public String checkUserNameValidity(UserCreation userCreation) {
        String userName = userCreation.getUserName();
        if (userName == null || userName.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }

        userName = userName.trim();
        if (userName.length() < 2) {
            throw new IllegalArgumentException("Username must be at least 2 characters");
        } else if (userName.length() > 100) {
            throw new IllegalArgumentException("Username cannot be more than 100 characters");
        }
        return "UserName is valid";
    }

    public String checkUserEmailValidity(UserCreation userCreation) {
        String userEmail = userCreation.getUserEmail();
        if (userEmail == null || userEmail.isBlank()) {
            throw new IllegalArgumentException("Email cannot be blank");
        }
        userEmail = userEmail.trim();
        if (!userEmail.contains("@")) {
            throw new IllegalArgumentException("Email must contain @");
        } else if (userEmail.indexOf("@") != userEmail.lastIndexOf("@")) {
            throw new IllegalArgumentException("Email must only contain one @");
        } else if (userEmail.indexOf("@") == 0) {
            throw new IllegalArgumentException("Email cannot have @ as the first character");
        } else if (userEmail.lastIndexOf('.') < userEmail.indexOf("@")) {
            throw new IllegalArgumentException("Email has incorrect formatting");
        }
        isEmailCharValid(userEmail);
        return "UserEmail is valid";
    }

    public void isEmailCharValid(String userEmail) {
        for (char c : userEmail.toCharArray()) {
            if (Character.isLetter(c) || Character.isDigit(c) || c == '@' || c == '-' || c == '_' || c == '.') {
                continue;
            }
            throw new IllegalArgumentException("Email contains invalid character " + c);
        }
    }

    public String checkPasswordValidity(UserCreation userCreation) {
        String userPassword = userCreation.getUserPassword();
        if (userPassword == null || userPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be blank");
        }
        userPassword = userPassword.trim();
        if (userPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < userPassword.length(); i++) {
            char curr = userPassword.charAt(i);
            if (Character.isLetter(curr)) {
                hasLetter = true;
            }
            if (Character.isDigit(curr)) {
                hasDigit = true;
            }
        }
        if (!hasLetter) {
            throw new IllegalArgumentException("Password must contain at least one letter");
        } else if (!hasDigit) {
            throw new IllegalArgumentException("Password must contain at least one number");
        }
        return "Password is valid";
    }

    public LoginResponse loginUser(UserLogin userLogin) {
        String userPassword = userLogin.getUserPassword();
        if (userPassword == null || userPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be blank");
        }

        Optional<User> optionalUser = Optional.empty();

        // Support login via email or username
        if (userLogin.getUserEmail() != null && !userLogin.getUserEmail().isBlank()) {
            optionalUser = userRepository.findByEmail(userLogin.getUserEmail().trim());
        } else if (userLogin.getUserName() != null && !userLogin.getUserName().isBlank()) {
            optionalUser = userRepository.findByFullName(userLogin.getUserName().trim());
        } else {
            throw new IllegalArgumentException("Please provide an email or username to log in");
        }

        if (optionalUser.isEmpty()) {
            throw new IllegalArgumentException("Invalid email/username or password");
        }

        User user = optionalUser.get();

        if (!passwordEncoder.matches(userPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email/username or password");
        }

        return new LoginResponse(user);
    }
}