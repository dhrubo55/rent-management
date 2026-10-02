package com.renteasebd.repository;

import com.renteasebd.domain.auth.RefreshToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rt from RefreshToken rt where rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
    boolean existsByIdAndUserId(String id, Long userId);
    List<RefreshToken> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("delete from RefreshToken rt where rt.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from RefreshToken rt where rt.id = :id and rt.user.id = :userId")
    int deleteByIdAndUserId(@Param("id") String id, @Param("userId") Long userId);
}
