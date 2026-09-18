package mygrant.user.dto;

import mygrant.user.User;
import java.time.LocalDate;

public class LoginResponse {

    private Long userId;
    private String userName;
    private String userEmail;
    private String visaType;
    private String academicLevel;
    private LocalDate programEndDate;
    private String token; // Optional placeholder for JWT authentication

    public LoginResponse() {}

    // Constructor to quickly map from the User entity
    public LoginResponse(User user) {
        this.userId = user.getId();
        this.userName = user.getFullName();
        this.userEmail = user.getEmail();
        this.visaType = user.getVisaType();
        this.academicLevel = user.getAcademicLevel();
        this.programEndDate = user.getProgramEndDate();
    }

    public LoginResponse(User user, String token) {
        this(user);
        this.token = token;
    }

    // Getters and Setters

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getVisaType() {
        return visaType;
    }

    public void setVisaType(String visaType) {
        this.visaType = visaType;
    }

    public String getAcademicLevel() {
        return academicLevel;
    }

    public void setAcademicLevel(String academicLevel) {
        this.academicLevel = academicLevel;
    }

    public LocalDate getProgramEndDate() {
        return programEndDate;
    }

    public void setProgramEndDate(LocalDate programEndDate) {
        this.programEndDate = programEndDate;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}