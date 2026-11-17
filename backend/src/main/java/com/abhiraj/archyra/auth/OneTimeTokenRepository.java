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

interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID> {

	/** Locks the row so a token cannot be consumed twice by concurrent requests. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from OneTimeToken t where t.tokenHash = :hash and t.purpose = :purpose")
	Optional<OneTimeToken> findForUpdate(@Param("hash") String hash, @Param("purpose") OneTimePurpose purpose);

	List<OneTimeToken> findByUserIdAndPurposeAndUsedAtIsNull(UUID userId, OneTimePurpose purpose);

	long deleteByExpiresAtBefore(Instant cutoff);

	long deleteByUsedAtBefore(Instant cutoff);
}
