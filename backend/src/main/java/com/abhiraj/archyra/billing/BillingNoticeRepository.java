package com.abhiraj.archyra.billing;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BillingNoticeRepository extends JpaRepository<BillingNotice, UUID> {

	/** 1 when this call claimed the step, 0 when it was taken already (or concurrently, once that transaction commits). */
	@Modifying
	@Query(value = "insert into billing_notices (id, user_id, kind, period_end, sent_at) values (gen_random_uuid(), :userId, :kind, :periodEnd, :now) "
			+ "on conflict (user_id, kind, period_end) do nothing", nativeQuery = true)
	int claim(@Param("userId") UUID userId, @Param("kind") String kind, @Param("periodEnd") Instant periodEnd, @Param("now") Instant now);

	java.util.Optional<BillingNotice> findByUserIdAndKindAndPeriodEnd(UUID userId, NoticeKind kind, Instant periodEnd);

	boolean existsByUserIdAndKindAndPeriodEnd(UUID userId, NoticeKind kind, Instant periodEnd);

	boolean existsByUserId(UUID userId);

	void deleteByUserId(UUID userId);
}
