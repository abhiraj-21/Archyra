package com.abhiraj.archyra.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** Package-private on purpose: other features go through {@link UserService}. */
interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	boolean existsByRole(Role role);

	/** Locks every enabled account of the role, so two administrators cannot disable each other at the same moment. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.role = :role and u.enabled = true")
	List<User> lockEnabledByRole(@Param("role") Role role);

	long countByRoleAndEnabledTrue(Role role);

	/** Enabled accounts that signed in at or after {@code since}. */
	long countByEnabledTrueAndLastLoginAtGreaterThanEqual(Instant since);

	/** Accounts on a PREMIUM plan that has not expired at {@code now}, the same rule as {@link User#effectivePlan}. */
	@Query("select count(u) from User u where u.plan = com.abhiraj.archyra.user.Plan.PREMIUM and (u.planExpiresAt is null or u.planExpiresAt > :now)")
	long countEffectivePremium(@Param("now") Instant now);
}
