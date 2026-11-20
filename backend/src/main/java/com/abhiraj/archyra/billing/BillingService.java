package com.abhiraj.archyra.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.analysis.AnalysisRetention;
import com.abhiraj.archyra.common.AccountDisabledException;
import com.abhiraj.archyra.common.BillingRuleException;
import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.common.RateLimitedException;
import com.abhiraj.archyra.mail.BillingMails;
import com.abhiraj.archyra.mail.EmailService;
import com.abhiraj.archyra.security.RateLimiter;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.usage.UsageProperties;
import com.abhiraj.archyra.usage.UsageType;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Premium through Razorpay: what it costs, where a user stands, one-time orders and {@link #applyPayment}, <b>the single
 * place that extends Premium</b>. Every confirmation of a payment (the checkout's verify call, {@code payment.captured},
 * {@code order.paid}, {@code subscription.charged}) ends up there, and its guarded UPDATE lets exactly one of them extend.
 *
 * <p>Never logs a key, a signature, a body, an email or an amount; a Razorpay id appears only where a payment has to be
 * traced (a payment for a deleted account).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

	/** Razorpay ids ({@code order_...}, {@code pay_...}, {@code sub_...}). */
	static final Pattern RAZORPAY_ID = Pattern.compile("[A-Za-z0-9_]{1,64}");
	static final int MAX_PAGE_SIZE = 100;

	/** What {@link #applyPayment} did. */
	enum Applied {
		/** Premium was extended by this call. */
		EXTENDED,
		/** Another confirmation of the same payment came first: nothing changed. */
		ALREADY_APPLIED,
		/** The reported amount or currency is not what the order was for: ignored, Premium unchanged. */
		MISMATCH,
		/** The account no longer exists: the payment is recorded as paid and nothing else happens. */
		USER_GONE,
		/** No payment row: nothing to apply. */
		UNKNOWN,
		/** The user has Premium without an end (an administrator's grant): the payment is recorded, the plan is unchanged. */
		NO_END
	}

	private final BillingProperties properties;
	private final PaymentRepository payments;
	private final SubscriptionRepository subscriptions;
	private final BillingNoticeRepository notices;
	private final RazorpayGateway gateway;
	private final UserService users;
	private final AnalysisRetention retention;
	private final UsageProperties usage;
	private final SecurityProperties security;
	private final RateLimiter rateLimiter;
	private final EmailService email;
	private final Clock clock;

	// ---- reading ---------------------------------------------------------------------------------------------

	public BillingDtos.PlansResponse plans() {
		List<BillingDtos.Price> prices = List.of(BillingPeriod.values()).stream().map(period -> new BillingDtos.Price(period.name(),
				properties.pricePaise(period), display(properties.pricePaise(period)), properties.razorpay().planId(period) != null)).toList();
		return new BillingDtos.PlansResponse(properties.enabled(), properties.enabled() ? properties.razorpay().keyId().strip() : null,
				currency(), prices, new BillingDtos.Limits(usage.freeLimit(UsageType.REPOSITORY_ANALYSIS), usage.freeLimit(UsageType.AI_QUERY)),
				new BillingDtos.Limits(null, null), security.rateLimit().aiChatPerUserPerMinute(), properties.keepRepositories(),
				properties.graceDays(), properties.reminderDays());
	}

	@Transactional(readOnly = true)
	public BillingDtos.StatusResponse status(UUID userId) {
		User user = users.findById(userId).orElseThrow(AccountDisabledException::new);
		Instant now = clock.instant();
		Optional<Subscription> subscription = subscriptions.findByUserId(userId);
		BillingState state = stateOf(user, subscription.map(Subscription::autoRenews).orElse(false), now);
		BillingDtos.Grace grace = null;
		if ((state == BillingState.EXPIRING || state == BillingState.GRACE) && user.getPlanExpiresAt() != null) {
			grace = new BillingDtos.Grace(graceEndsAt(userId, user.getPlanExpiresAt()),
					retention.rank(userId, properties.keepRepositories()).atRisk().size());
		}
		BillingDtos.AutoRenew autoRenew = subscription.filter(s -> s.getStatus().live())
				.map(s -> new BillingDtos.AutoRenew(true, s.getPeriod().name(), s.isCancelAtCycleEnd(),
						s.isCancelAtCycleEnd() ? null : (s.getCurrentEnd() != null ? s.getCurrentEnd() : user.getPlanExpiresAt())))
				.orElse(new BillingDtos.AutoRenew(false, null, false, null));
		return new BillingDtos.StatusResponse(user.getPlan().name(), user.effectivePlan(now).name(), user.getPlanExpiresAt(), state.name(),
				autoRenew, grace);
	}

	/**
	 * NONE, ACTIVE, EXPIRING, GRACE or LAPSED. A stored PREMIUM plan past its end is GRACE until the lifecycle job has dealt
	 * with it (it then sets FREE and clears the end), so the few minutes between the end of the grace period and the next sweep
	 * still read as GRACE. A FREE user who ever paid, or whose Premium ever ended, is LAPSED.
	 */
	BillingState stateOf(User user, boolean autoRenews, Instant now) {
		Instant end = user.getPlanExpiresAt();
		if (user.getPlan() == Plan.PREMIUM) {
			if (end == null) {
				return BillingState.ACTIVE;
			}
			if (end.isAfter(now)) {
				boolean soon = !end.isAfter(reminderFrom(now));
				return soon && !autoRenews ? BillingState.EXPIRING : BillingState.ACTIVE;
			}
			return BillingState.GRACE;
		}
		boolean hadPremium = payments.existsByUserIdAndStatusIn(user.getId(), List.of(PaymentStatus.PAID, PaymentStatus.REFUNDED))
				|| notices.existsByUserId(user.getId());
		return hadPremium ? BillingState.LAPSED : BillingState.NONE;
	}

	@Transactional(readOnly = true)
	public BillingDtos.RetentionResponse retention(UUID userId) {
		AnalysisRetention.Retention ranked = retention.rank(userId, properties.keepRepositories());
		return new BillingDtos.RetentionResponse(properties.keepRepositories(), ranked.kept().stream().map(BillingService::item).toList(),
				ranked.atRisk().stream().map(BillingService::item).toList());
	}

	@Transactional(readOnly = true)
	public BillingDtos.PaymentPage payments(UUID userId, int page, int size) {
		if (page < 0) {
			throw new InvalidRequestException("page must not be negative");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		Page<Payment> result = payments.findByUserId(userId,
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
		return new BillingDtos.PaymentPage(result.getContent().stream().map(payment -> new BillingDtos.PaymentItem(payment.getId().toString(),
				payment.getMode().name(), payment.getPeriod().name(), payment.getAmountPaise(), payment.getCurrency(), payment.getStatus().name(),
				payment.getCreatedAt(), payment.getPaidAt(), payment.getExtendedTo())).toList(), page, size, result.getTotalElements(),
				result.getTotalPages());
	}

	// ---- one-time orders ------------------------------------------------------------------------------------------

	/**
	 * Creates a Razorpay order for the configured price of the period and records it as a CREATED payment. Not transactional:
	 * no database transaction is held while Razorpay is asked, and the row is written only once the order exists.
	 *
	 * @throws BillingRuleException {@code BILLING_UNAVAILABLE}, {@code INVALID_PERIOD} or {@code SUBSCRIPTION_ACTIVE}
	 */
	public BillingDtos.CreateOrderResponse createOrder(UUID userId, String periodText) {
		requireEnabled();
		BillingPeriod period = period(periodText);
		requireActiveAccount(userId);
		limit(RateLimiter.Rule.BILLING_CREATE, userId);
		if (subscriptions.findByUserId(userId).map(Subscription::autoRenews).orElse(false)) {
			throw BillingRuleException.subscriptionActive();
		}
		UUID paymentId = UUID.randomUUID();
		long amount = properties.pricePaise(period);
		RazorpayGateway.Order order;
		try {
			// only our own id in the notes: never an email or a name
			order = gateway.createOrder(amount, currency(), paymentId.toString(), Map.of("paymentId", paymentId.toString()));
		}
		catch (RazorpayUnavailableException ex) {
			log.warn("Could not create a Razorpay order ({})", ex.getMessage());
			throw BillingRuleException.unavailable();
		}
		if (!RAZORPAY_ID.matcher(order.id()).matches()) {
			log.warn("Razorpay answered an order without a usable id");
			throw BillingRuleException.unavailable();
		}
		payments.save(Payment.forOrder(paymentId, userId, period, amount, currency(), order.id(), clock.instant()));
		log.info("Order created for payment {} ({})", paymentId, period);
		return new BillingDtos.CreateOrderResponse(order.id(), amount, currency(), properties.razorpay().keyId().strip(), paymentId.toString());
	}

	/**
	 * The checkout's success handler: the signature proves Razorpay saw this payment for this order. The order must be the
	 * caller's. Then the payment is applied, idempotently with the webhook. Every call takes a token of the verify rate limit,
	 * so wrong signatures count.
	 *
	 * @throws BillingRuleException {@code PAYMENT_VERIFICATION_FAILED} for a wrong or malformed signature, an unknown order or
	 *                              someone else's
	 */
	@Transactional
	public BillingDtos.StatusResponse verifyOrder(UUID userId, BillingDtos.VerifyOrderRequest request) {
		requireEnabled();
		limit(RateLimiter.Rule.BILLING_VERIFY, userId);
		String orderId = request == null ? null : request.razorpayOrderId();
		String paymentId = request == null ? null : request.razorpayPaymentId();
		if (!validId(orderId) || !validId(paymentId)
				|| !PaymentSignatures.orderCheckout(properties.razorpay().keySecret(), orderId, paymentId, request.razorpaySignature())) {
			log.info("A checkout signature did not verify for user {}", userId);
			throw BillingRuleException.verificationFailed();
		}
		Payment payment = payments.findByRazorpayOrderId(orderId).filter(row -> row.getUserId().equals(userId))
				.orElseThrow(BillingRuleException::verificationFailed);
		applyPayment(payment.getId(), paymentId, payment.getAmountPaise(), payment.getCurrency());
		return status(userId);
	}

	// ---- the one place that extends Premium -----------------------------------------------------------------------

	/**
	 * Applies one payment, exactly once however often and from whichever path it is reached, in the caller's transaction or
	 * its own:
	 * <ol>
	 * <li>the reported amount and currency must equal our row (else it is ignored and logged without details);</li>
	 * <li>the user row is locked, so two confirmations of one payment serialise;</li>
	 * <li>the guarded UPDATE flips the payment to PAID; 0 rows means it was applied already, and nothing else happens;</li>
	 * <li>Premium goes to {@code max(now, current end) + one period} through {@link User#changePlan}, and the extension is
	 *     stored on the payment;</li>
	 * <li>after the commit, the "Premium is active" email (a mail failure never fails this).</li>
	 * </ol>
	 */
	@Transactional
	public Applied applyPayment(UUID paymentRowId, String razorpayPaymentId, long amountPaise, String currency) {
		Optional<Payment> found = payments.findById(paymentRowId);
		if (found.isEmpty()) {
			return Applied.UNKNOWN;
		}
		Payment payment = found.get();
		if (payment.getAmountPaise() != amountPaise || currency == null || !payment.getCurrency().equalsIgnoreCase(currency)) {
			log.warn("A confirmation for payment {} reported another amount or currency; ignored", payment.getId());
			return Applied.MISMATCH;
		}
		Optional<User> locked = users.lockForUpdate(payment.getUserId());
		Instant now = clock.instant();
		if (payments.markPaid(payment.getId(), razorpayPaymentId, now) == 0) {
			return Applied.ALREADY_APPLIED;
		}
		if (locked.isEmpty()) {
			log.warn("Payment {} belongs to an account that no longer exists; recorded as paid, nothing extended", razorpayPaymentId);
			return Applied.USER_GONE;
		}
		User user = locked.get();
		if (user.getPlan() == Plan.PREMIUM && user.getPlanExpiresAt() == null) {
			// Premium without an end (granted by an administrator) cannot be extended; the payment is recorded
			log.warn("Payment {} was made by an account whose Premium has no end; recorded, nothing changed", payment.getId());
			return Applied.NO_END;
		}
		Instant current = user.getPlan() == Plan.PREMIUM ? user.getPlanExpiresAt() : null;
		Instant from = current != null && current.isAfter(now) ? current : now;
		Instant to = payment.getPeriod().addTo(from, properties.zoneId());
		user.changePlan(Plan.PREMIUM, to);
		payments.recordExtension(payment.getId(), from, to);
		log.info("Premium of user {} extended by payment {} ({}) to {}", user.getId(), payment.getId(), payment.getPeriod(), to);

		boolean autoRenew = payment.getMode() == PaymentMode.SUBSCRIPTION;
		String address = user.getEmail();
		String name = user.getName();
		BillingMails.PremiumActivated mail = new BillingMails.PremiumActivated(payment.getPeriod() == BillingPeriod.MONTHLY ? "monthly" : "annual",
				display(payment.getAmountPaise()), to, properties.zoneId(), autoRenew, link("/settings#subscription"));
		AfterCommit.run("send the Premium email", () -> email.sendPremiumActivated(address, name, mail));
		return Applied.EXTENDED;
	}

	// ---- shared rules ----------------------------------------------------------------------------------------------

	/** An expiry notice sent later than this after the end (the job was down, or the end was set in the past) restarts the grace. */
	static final java.time.Duration LATE_NOTICE = java.time.Duration.ofHours(1);

	/**
	 * When the repositories beyond the kept ones go for the period ending at {@code end}: {@code grace-days} after the end, or,
	 * when the "Premium has ended" email went out (or would go out now) more than {@link #LATE_NOTICE} after the end,
	 * {@code grace-days} after that email. Nobody loses a repository without having been told the date first.
	 */
	@Transactional(readOnly = true)
	public Instant graceEndsAt(UUID userId, Instant end) {
		Instant now = clock.instant();
		Instant told = notices.findByUserIdAndKindAndPeriodEnd(userId, NoticeKind.EXPIRED, end).map(BillingNotice::getSentAt)
				.orElse(end.isAfter(now) ? end : now);
		return told.isAfter(end.plus(LATE_NOTICE)) ? deletionAt(told) : deletionAt(end);
	}

	/** {@code grace-days} calendar days after {@code from}, in the billing zone. */
	Instant deletionAt(Instant end) {
		return end.atZone(properties.zoneId()).plusDays(properties.graceDays()).toInstant();
	}

	/** An end at or before this is "within the reminder days". */
	Instant reminderFrom(Instant now) {
		return now.atZone(properties.zoneId()).plusDays(properties.reminderDays()).toInstant();
	}

	String link(String path) {
		return security.frontendBaseUrl().replaceAll("/+$", "") + path;
	}

	void requireEnabled() {
		if (!properties.enabled()) {
			throw BillingRuleException.unavailable();
		}
	}

	void requireActiveAccount(UUID userId) {
		User user = users.findById(userId).orElseThrow(AccountDisabledException::new);
		if (!user.isEnabled()) {
			throw new AccountDisabledException();
		}
	}

	void limit(RateLimiter.Rule rule, UUID userId) {
		RateLimiter.Decision decision = rateLimiter.tryConsume(rule, userId.toString());
		if (!decision.allowed()) {
			throw new RateLimitedException(decision.retryAfterSeconds());
		}
	}

	static BillingPeriod period(String text) {
		if (text == null) {
			throw BillingRuleException.invalidPeriod();
		}
		try {
			return BillingPeriod.valueOf(text.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw BillingRuleException.invalidPeriod();
		}
	}

	static boolean validId(String id) {
		return id != null && RAZORPAY_ID.matcher(id).matches();
	}

	String currency() {
		return properties.prices().currency();
	}

	/** "700 INR", or "700.50 INR" when there are paise. */
	String display(long paise) {
		String amount = paise % 100 == 0 ? String.valueOf(paise / 100) : String.format(Locale.ROOT, "%d.%02d", paise / 100, paise % 100);
		return amount + " " + currency();
	}

	private static BillingDtos.RetentionItem item(AnalysisRetention.Item item) {
		return new BillingDtos.RetentionItem(item.analysisId().toString(), item.repositoryUrl(), item.lastActivityAt(), item.status().name());
	}
}
