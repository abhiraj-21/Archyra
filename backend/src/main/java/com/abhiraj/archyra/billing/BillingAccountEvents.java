package com.abhiraj.archyra.billing;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.common.AccountDeleted;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Account deletion and billing: a deleted account's live subscription is cancelled at Razorpay at once (best effort, after the
 * deletion committed; the lifecycle sweep retries one that could not be). The payments stay as a record of money received:
 * they hold ids and amounts, nothing personal. Never throws, and logs no identifier.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BillingAccountEvents {

	private final SubscriptionService subscriptions;
	private final BillingNoticeRepository notices;
	private final org.springframework.transaction.support.TransactionTemplate transactions;

	@EventListener
	void onAccountDeleted(AccountDeleted event) {
		try {
			subscriptions.cancelForDeletedAccount(event.userId());
		}
		catch (RuntimeException ex) {
			log.warn("Could not cancel the subscription of a deleted account; the sweep retries it ({})", ex.getClass().getSimpleName());
		}
		try {
			transactions.executeWithoutResult(status -> notices.deleteByUserId(event.userId()));
		}
		catch (RuntimeException ex) {
			log.warn("Could not remove the billing notices of a deleted account ({})", ex.getClass().getSimpleName());
		}
	}
}
