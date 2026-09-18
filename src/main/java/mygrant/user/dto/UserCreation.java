package mygrant.user.dto;

import java.time.LocalDate;

public class UserCreation {

    private String userName;
    private String userEmail;
    private String userPassword;
    private String visaType;        // e.g., "F-1", "J-1", "H-1B"
    private String academicLevel;   // e.g., "PhD", "Undergraduate", "Master's"
    private LocalDate programEndDate; // e.g., "2028-05-31"

    public UserCreation() {}

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

    public String getUserPassword() {
        return userPassword;
    }

    public void setUserPassword(String userPassword) {
        this.userPassword = userPassword;
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
}