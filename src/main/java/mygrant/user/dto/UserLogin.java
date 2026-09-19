package mygrant.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class UserLogin {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String userEmail;

    @NotBlank(message = "Password is required")
    private String userPassword;
    public UserLogin() {}

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getUserPassword() {
        return userPassword;
    }

    public void setUserPassword(String userPassword) {
        this.userPassword = userPassword;
    }
}