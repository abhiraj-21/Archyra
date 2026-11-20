package com.abhiraj.archyra.billing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentRepository extends JpaRepository<Payment, UUID> {

	Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

	Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId);

	Page<Payment> findByUserId(UUID userId, Pageable pageable);

	boolean existsByUserIdAndStatusIn(UUID userId, java.util.Collection<PaymentStatus> statuses);

	// No clearAutomatically on these: applyPayment holds the locked User in the persistence context and changes it after the
	// guarded UPDATE; clearing would detach it and the new plan would never be written.

	/**
	 * The one guard that lets a payment extend Premium: CREATED (or FAILED: Razorpay lets the customer retry an order, and
	 * the retry can succeed) to PAID. 0 rows means it was applied already, from whichever path came first.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update Payment p set p.status = com.abhiraj.archyra.billing.PaymentStatus.PAID, p.razorpayPaymentId = :paymentId, p.paidAt = :now "
			+ "where p.id = :id and p.status in (com.abhiraj.archyra.billing.PaymentStatus.CREATED, com.abhiraj.archyra.billing.PaymentStatus.FAILED)")
	int markPaid(@Param("id") UUID id, @Param("paymentId") String razorpayPaymentId, @Param("now") Instant now);

	@Modifying(flushAutomatically = true)
	@Query("update Payment p set p.extendedFrom = :from, p.extendedTo = :to where p.id = :id")
	int recordExtension(@Param("id") UUID id, @Param("from") Instant from, @Param("to") Instant to);

	/** A failed attempt; never touches a payment that was paid (an earlier failure does not undo a later success). */
	@Modifying(flushAutomatically = true)
	@Query("update Payment p set p.status = com.abhiraj.archyra.billing.PaymentStatus.FAILED "
			+ "where p.id = :id and p.status = com.abhiraj.archyra.billing.PaymentStatus.CREATED")
	int markFailed(@Param("id") UUID id);

	/** Recorded only: a refund never shortens Premium by itself. */
	@Modifying(flushAutomatically = true)
	@Query("update Payment p set p.status = com.abhiraj.archyra.billing.PaymentStatus.REFUNDED "
			+ "where p.razorpayPaymentId = :paymentId and p.status = com.abhiraj.archyra.billing.PaymentStatus.PAID")
	int markRefunded(@Param("paymentId") String razorpayPaymentId);

	/**
	 * A subscription charge, keyed by its Razorpay payment id: a second delivery of the same charge (the checkout handler and
	 * the webhook, or the webhook twice) inserts nothing, and waits for the first one's transaction instead of racing it.
	 */
	@Modifying
	@Query(value = "insert into payments (id, user_id, mode, period, amount_paise, currency, status, razorpay_subscription_id, "
			+ "razorpay_payment_id, created_at) values (:id, :userId, 'SUBSCRIPTION', :period, :amount, :currency, 'CREATED', "
			+ ":subscriptionId, :paymentId, :now) on conflict (razorpay_payment_id) do nothing", nativeQuery = true)
	int insertSubscriptionCharge(@Param("id") UUID id, @Param("userId") UUID userId, @Param("period") String period,
			@Param("amount") long amountPaise, @Param("currency") String currency, @Param("subscriptionId") String subscriptionId,
			@Param("paymentId") String paymentId, @Param("now") Instant now);
}
