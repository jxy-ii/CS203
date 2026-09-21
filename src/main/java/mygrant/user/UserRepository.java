package mygrant.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);
    Optional<User> findByEmail(String email);

    /** Matches users by role and lower-cased visa type, since registration does not normalize case. */
    @Query("select u from User u where u.role = :role and lower(u.visaType) in :visaTypes")
    List<User> findByRoleAndVisaTypeIn(@Param("role") UserRole role, @Param("visaTypes") List<String> visaTypes);
}