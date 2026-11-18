package com.abhiraj.archyra.auth.oauth2;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LinkIntentRepository extends JpaRepository<LinkIntent, UUID> {

	Optional<LinkIntent> findByTokenHash(String tokenHash);

	/** Marks the intent used if it still can be. 1 means this caller got it; 0 means it was used or expired meanwhile. */
	@Modifying
	@Query("update LinkIntent i set i.consumedAt = :now where i.id = :id and i.consumedAt is null and i.expiresAt > :now")
	int consume(@Param("id") UUID id, @Param("now") Instant now);

	@Modifying
	@Query("delete from LinkIntent i where i.expiresAt < :cutoff or i.consumedAt < :cutoff")
	int purge(@Param("cutoff") Instant cutoff);
}
