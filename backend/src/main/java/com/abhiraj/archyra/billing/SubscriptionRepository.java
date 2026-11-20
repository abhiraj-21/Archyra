package com.abhiraj.archyra.billing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

	Optional<Subscription> findByUserId(UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Subscription s where s.razorpaySubscriptionId = :id")
	Optional<Subscription> lockByRazorpayId(@Param("id") String razorpaySubscriptionId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Subscription s where s.userId = :userId")
	Optional<Subscription> lockByUserId(@Param("userId") UUID userId);

	List<Subscription> findByStatusIn(Collection<SubscriptionStatus> statuses);

	/** The users of the given ids whose subscription will charge again. */
	@Query("select s.userId from Subscription s where s.userId in :userIds and s.cancelAtCycleEnd = false and s.status in "
			+ "(com.abhiraj.archyra.billing.SubscriptionStatus.AUTHENTICATED, com.abhiraj.archyra.billing.SubscriptionStatus.ACTIVE, "
			+ "com.abhiraj.archyra.billing.SubscriptionStatus.PENDING)")
	List<UUID> autoRenewingAmong(@Param("userIds") Collection<UUID> userIds);
}
