package com.example.broadcast.shared.repository;

import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.model.UserPreferences;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Monitored("repository")
public interface UserPreferencesRepository extends CrudRepository<UserPreferences, Long> {
    
    // Derived Queries
    Optional<UserPreferences> findByUserId(String userId);
    List<UserPreferences> findByUserIdIn(List<String> userIds);

    // Custom query for a specific field projection
    @Query("SELECT user_id FROM user_preferences ORDER BY user_id")
    List<String> findAllUserIds();
}