package mygrant.notifications;

import java.util.function.Predicate;

import mygrant.user.User;

/** Profile information a policy change can require a user to review or update. */
public enum ProfileField {
    PROGRAM_END_DATE("program end date", user -> user.getProgramEndDate() != null),
    ACADEMIC_LEVEL("academic level",
            user -> user.getAcademicLevel() != null && !user.getAcademicLevel().isBlank());

    private final String label;
    private final Predicate<User> isFilledIn;

    ProfileField(String label, Predicate<User> isFilledIn) {
        this.label = label;
        this.isFilledIn = isFilledIn;
    }

    public String label() {
        return label;
    }

    public boolean isFilledIn(User user) {
        return isFilledIn.test(user);
    }
}
