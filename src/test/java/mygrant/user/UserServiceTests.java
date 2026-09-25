package mygrant.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import mygrant.auth.JwtService;
import mygrant.user.dto.LoginResponse;
import mygrant.user.dto.UserCreation;

/** Verifies registration persists the optional travel fields whether or not they are supplied. */
@ExtendWith(MockitoExtension.class)
class UserServiceTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Captor
    private ArgumentCaptor<User> saved;

    @BeforeEach
    void setUp() {
        when(userRepository.existsByEmail("student@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateToken("student@example.com")).thenReturn("token");
    }

    @Test
    void registersWithoutTravelFields() {
        LoginResponse response = service().createUser(registration());

        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getCurrentLocation()).isNull();
        assertThat(saved.getValue().getUpcomingTravelDate()).isNull();
        assertThat(saved.getValue().getVisaType()).isEqualTo("F-1");
        assertThat(saved.getValue().getProgramEndDate()).isEqualTo(LocalDate.parse("2028-05-31"));
        assertThat(response.getCurrentLocation()).isNull();
        assertThat(response.getUpcomingTravelDate()).isNull();
        assertThat(response.getToken()).isEqualTo("token");
    }

    @Test
    void registersWithTravelFieldsPopulated() {
        UserCreation registration = registration();
        registration.setCurrentLocation("ABROAD");
        registration.setUpcomingTravelDate(LocalDate.parse("2026-12-15"));

        LoginResponse response = service().createUser(registration);

        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getCurrentLocation()).isEqualTo("ABROAD");
        assertThat(saved.getValue().getUpcomingTravelDate()).isEqualTo(LocalDate.parse("2026-12-15"));
        assertThat(response.getCurrentLocation()).isEqualTo("ABROAD");
        assertThat(response.getUpcomingTravelDate()).isEqualTo(LocalDate.parse("2026-12-15"));
    }

    private UserCreation registration() {
        UserCreation registration = new UserCreation();
        registration.setUserName("Student");
        registration.setUserEmail("student@example.com");
        registration.setUserPassword("password1");
        registration.setVisaType("F-1");
        registration.setAcademicLevel("Master's");
        registration.setProgramEndDate(LocalDate.parse("2028-05-31"));
        return registration;
    }

    private UserService service() {
        return new UserService(userRepository, passwordEncoder, jwtService);
    }
}
