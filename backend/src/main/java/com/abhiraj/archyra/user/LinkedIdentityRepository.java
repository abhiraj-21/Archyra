package com.abhiraj.archyra.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private on purpose: other features go through {@link UserService}. */
interface LinkedIdentityRepository extends JpaRepository<LinkedIdentity, UUID> {

	Optional<LinkedIdentity> findByProviderAndProviderUserId(Provider provider, String providerUserId);

	List<LinkedIdentity> findByUserId(UUID userId);

	Optional<LinkedIdentity> findByUserIdAndProvider(UUID userId, Provider provider);

	long countByUserId(UUID userId);
}
