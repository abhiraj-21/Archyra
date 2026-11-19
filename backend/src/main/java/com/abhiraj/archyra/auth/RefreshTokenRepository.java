package com.abhiraj.archyra.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/** Locks the row so a token cannot be rotated twice by concurrent requests. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t where t.tokenHash = :hash")
	Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

	List<RefreshToken> findByFamilyIdAndRevokedAtIsNull(UUID familyId);

	List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);

	long deleteByUserId(UUID userId);

	long deleteByExpiresAtBefore(Instant cutoff);

	long deleteByRevokedAtBefore(Instant cutoff);
}
