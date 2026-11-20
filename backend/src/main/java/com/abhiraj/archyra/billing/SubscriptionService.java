package com.abhiraj.archyra.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.common.BillingRuleException;
import com.abhiraj.archyra.security.RateLimiter;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-renewing Premium: Razorpay Subscriptions on a Plan per period. One row per user ({@code subscriptions}); webhook
 * events are matched to the user only through that row. Every charge is a payment applied through
 * {@link BillingService#applyPayment}, keyed by its Razorpay payment id, so the checkout handler and
 * {@code subscription.charged} extend once between them. Cancelling is always at the end of the current cycle: Premium stays
 * until the date already paid for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

	private final BillingProperties properties;
	private final SubscriptionRepository subscriptions;
	private final PaymentRepository payments;
	private final RazorpayGateway gateway;
	private final BillingService billing;
	private final UserService users;
	private final TransactionTemplate transactions;
	private final Clock clock;

	/**
	 * Creates a Razorpay subscription for the period's Plan and makes it the user's row (replacing one that will not charge
	 * again). No transaction is held while Razorpay is asked.
	 *
	 * @throws BillingRuleException {@code BILLING_UNAVAILABLE} (also when no Plan is configured for the period),
	 *                              {@code INVALID_PERIOD}, {@code SUBSCRIPTION_ACTIVE}
	 */
	public BillingDtos.CreateSubscriptionResponse create(UUID userId, String periodText) {
		billing.requireEnabled();
		BillingPeriod period = BillingService.period(periodText);
		String planId = properties.razorpay().planId(period);
		if (planId == null) {
			throw BillingRuleException.unavailable();
		}
		billing.requireActiveAccount(userId);
		billing.limit(RateLimiter.Rule.BILLING_CREATE, userId);
		Optional<Subscription> existing = subscriptions.findByUserId(userId);
		if (existing.map(Subscription::autoRenews).orElse(false)) {
			throw BillingRuleException.subscriptionActive();
		}
		RazorpayGateway.RemoteSubscription created;
		try {
			created = gateway.createSubscription(planId, period.subscriptionCycles(), Map.of("product", "archyra-premium", "period", period.name()));
		}
		catch (RazorpayUnavailableException ex) {
			log.warn("Could not create a Razorpay subscription ({})", ex.getMessage());
			throw BillingRuleException.unavailable();
		}
		if (!BillingService.validId(created.id())) {
			log.warn("Razorpay answered a subscription without a usable id");
			throw BillingRuleException.unavailable();
		}
		String replaced = transactions.execute(status -> {
			Instant now = clock.instant();
			Optional<Subscription> row = subscriptions.lockByUserId(userId);
			if (row.isPresent() && row.get().autoRenews()) {
				return "RACE"; // another request of this user won; the new Razorpay subscription is cancelled below
			}
			String old = row.filter(s -> s.getStatus() == SubscriptionStatus.CREATED).map(Subscription::getRazorpaySubscriptionId).orElse(null);
			if (row.isPresent()) {
				row.get().replaceWith(created.id(), planId, period, now);
			}
			else {
				subscriptions.save(new Subscription(userId, created.id(), planId, period, now));
			}
			return old;
		});
		if ("RACE".equals(replaced)) {
			cancelQuietly(created.id());
			throw BillingRuleException.subscriptionActive();
		}
		if (replaced != null) {
			// a checkout that was opened and abandoned earlier: it must not be authorised later in another tab and charge unseen
			cancelQuietly(replaced);
		}
		log.info("Subscription created for user {} ({})", userId, period);
		return new BillingDtos.CreateSubscriptionResponse(created.id(), properties.razorpay().keyId().strip(), period.name(),
				properties.pricePaise(period), properties.prices().currency());
	}

	/**
	 * The checkout's success handler for a subscription. The signature proves the customer authorised this subscription with
	 * this payment; the row must be the caller's. Razorpay is then asked for the subscription's state: once it is active and
	 * charged, that payment is the first cycle and is applied here (idempotently with {@code subscription.charged}). If Razorpay
	 * cannot be asked, the row is marked authenticated and the webhook does the rest.
	 */
	public BillingDtos.StatusResponse verify(UUID userId, BillingDtos.VerifySubscriptionRequest request) {
		billing.requireEnabled();
		billing.limit(RateLimiter.Rule.BILLING_VERIFY, userId);
		String subscriptionId = request == null ? null : request.razorpaySubscriptionId();
		String paymentId = request == null ? null : request.razorpayPaymentId();
		if (!BillingService.validId(subscriptionId) || !BillingService.validId(paymentId)
				|| !PaymentSignatures.subscriptionCheckout(properties.razorpay().keySecret(), paymentId, subscriptionId, request.razorpaySignature())) {
			log.info("A subscription checkout signature did not verify for user {}", userId);
			throw BillingRuleException.verificationFailed();
		}
		Subscription row = subscriptions.findByUserId(userId).filter(s -> s.getRazorpaySubscriptionId().equals(subscriptionId))
				.orElseThrow(BillingRuleException::verificationFailed);
		RazorpayGateway.RemoteSubscription remote = null;
		try {
			remote = gateway.fetchSubscription(row.getRazorpaySubscriptionId());
		}
		catch (RazorpayUnavailableException ex) {
			log.warn("Could not read a subscription after its checkout; the webhook will complete it ({})", ex.getMessage());
		}
		RazorpayGateway.RemoteSubscription fetched = remote;
		transactions.executeWithoutResult(status -> {
			SubscriptionStatus next = fetched == null ? SubscriptionStatus.AUTHENTICATED
					: SubscriptionStatus.fromRazorpay(fetched.status()).orElse(SubscriptionStatus.AUTHENTICATED);
			subscriptions.lockByRazorpayId(subscriptionId).ifPresent(locked -> {
				locked.moveTo(SubscriptionStatus.AUTHENTICATED, null, clock.instant());
				locked.moveTo(next, fetched == null ? null : fetched.currentEnd(), clock.instant());
				if (fetched != null && next == SubscriptionStatus.ACTIVE && fetched.paidCount() >= 1) {
					charge(locked, paymentId, properties.pricePaise(locked.getPeriod()), properties.prices().currency());
				}
			});
		});
		return billing.status(userId);
	}

	/**
	 * Cancels the caller's auto-renewal at the end of the current cycle (Razorpay first, then the row). Premium stays until
	 * {@code plan_expires_at}.
	 *
	 * @throws BillingRuleException {@code NO_ACTIVE_SUBSCRIPTION}, {@code BILLING_UNAVAILABLE}
	 */
	public BillingDtos.StatusResponse cancel(UUID userId) {
		billing.requireEnabled();
		Subscription row = subscriptions.findByUserId(userId).filter(Subscription::autoRenews)
				.orElseThrow(BillingRuleException::noActiveSubscription);
		try {
			gateway.cancelSubscription(row.getRazorpaySubscriptionId(), true);
		}
		catch (RazorpayUnavailableException ex) {
			log.warn("Could not cancel a subscription at Razorpay ({})", ex.getMessage());
			throw BillingRuleException.unavailable();
		}
		transactions.executeWithoutResult(status -> subscriptions.lockByUserId(userId).ifPresent(locked -> locked.cancelAtCycleEnd(clock.instant())));
		log.info("Auto-renew of user {} cancelled at the end of the cycle", userId);
		return billing.status(userId);
	}

	// ---- webhook events (in the webhook's transaction) ----------------------------------------------------------

	/** A status-only event ({@code authenticated}, {@code activated}, {@code pending}, {@code halted}, {@code cancelled}, {@code completed}). */
	@Transactional
	public void onStatus(String subscriptionId, SubscriptionStatus status, Instant currentEnd) {
		Optional<Subscription> row = BillingService.validId(subscriptionId) ? subscriptions.lockByRazorpayId(subscriptionId) : Optional.empty();
		if (row.isEmpty()) {
			log.info("A subscription event for a subscription we do not hold was ignored");
			return;
		}
		row.get().moveTo(status, currentEnd, clock.instant());
	}

	/** {@code subscription.charged}: the subscription is active and this payment extends Premium by one period, once. */
	@Transactional
	public void onCharged(String subscriptionId, Instant currentEnd, String paymentId, long amountPaise, String currency) {
		Optional<Subscription> row = BillingService.validId(subscriptionId) ? subscriptions.lockByRazorpayId(subscriptionId) : Optional.empty();
		if (row.isEmpty()) {
			log.info("A subscription charge for a subscription we do not hold was ignored");
			return;
		}
		Subscription subscription = row.get();
		subscription.moveTo(SubscriptionStatus.ACTIVE, currentEnd, clock.instant());
		if (!BillingService.validId(paymentId)) {
			log.warn("A subscription charge without a usable payment id was ignored");
			return;
		}
		charge(subscription, paymentId, amountPaise, currency);
	}

	/**
	 * One charge of the subscription: a payment row keyed by the Razorpay payment id (inserted once; a repeat finds it), at the
	 * configured price of the period, then {@link BillingService#applyPayment}, which compares the reported amount with it.
	 */
	private void charge(Subscription subscription, String paymentId, long amountPaise, String currency) {
		payments.insertSubscriptionCharge(UUID.randomUUID(), subscription.getUserId(), subscription.getPeriod().name(),
				properties.pricePaise(subscription.getPeriod()), properties.prices().currency(), subscription.getRazorpaySubscriptionId(), paymentId,
				clock.instant());
		payments.findByRazorpayPaymentId(paymentId).ifPresent(payment -> {
			if (!payment.getUserId().equals(subscription.getUserId())) {
				log.warn("A subscription charge carried a payment of another account; ignored");
				return;
			}
			billing.applyPayment(payment.getId(), paymentId, amountPaise, currency);
		});
	}

	// ---- account deletion ---------------------------------------------------------------------------------------

	/**
	 * The account is gone (after its deletion committed): its subscription must not charge again. Cancelled at Razorpay at once,
	 * best effort; if Razorpay cannot be reached the row stays live and {@link #cancelOrphans} retries it. Never throws, and
	 * logs no identifier of the account or the subscription.
	 */
	public void cancelForDeletedAccount(UUID userId) {
		subscriptions.findByUserId(userId).filter(s -> s.getStatus().live() || s.getStatus() == SubscriptionStatus.HALTED
				|| s.getStatus() == SubscriptionStatus.CREATED).ifPresent(this::cancelOrphan);
	}

	/** Retries the cancellation of subscriptions whose account no longer exists. Part of the lifecycle sweep; idempotent. */
	public int cancelOrphans() {
		int cancelled = 0;
		for (Subscription subscription : subscriptions.findByStatusIn(List.of(SubscriptionStatus.CREATED, SubscriptionStatus.AUTHENTICATED,
				SubscriptionStatus.ACTIVE, SubscriptionStatus.PENDING, SubscriptionStatus.HALTED))) {
			if (users.findById(subscription.getUserId()).isEmpty() && cancelOrphan(subscription)) {
				cancelled++;
			}
		}
		return cancelled;
	}

	private boolean cancelOrphan(Subscription subscription) {
		try {
			gateway.cancelSubscription(subscription.getRazorpaySubscriptionId(), false);
		}
		catch (RuntimeException ex) {
			log.warn("Could not cancel the subscription of a deleted account; the sweep retries it ({})", ex.getClass().getSimpleName());
			return false;
		}
		try {
			transactions.executeWithoutResult(status -> subscriptions.lockByRazorpayId(subscription.getRazorpaySubscriptionId())
					.ifPresent(row -> row.moveTo(SubscriptionStatus.CANCELLED, null, clock.instant())));
		}
		catch (RuntimeException ex) {
			log.warn("A subscription of a deleted account was cancelled at Razorpay but not marked here ({})", ex.getClass().getSimpleName());
		}
		return true;
	}

	private void cancelQuietly(String razorpaySubscriptionId) {
		try {
			gateway.cancelSubscription(razorpaySubscriptionId, false);
		}
		catch (RuntimeException ex) {
			log.warn("Could not cancel an abandoned subscription ({})", ex.getClass().getSimpleName());
		}
	}
}
