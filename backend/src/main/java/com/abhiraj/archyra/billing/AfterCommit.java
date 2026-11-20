package com.abhiraj.archyra.billing;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;

/** Runs something once the current transaction has committed (at once without one). What it runs can never fail the caller. */
@Slf4j
final class AfterCommit {

	private AfterCommit() {
	}

	static void run(String what, Runnable action) {
		Runnable guarded = () -> {
			try {
				action.run();
			}
			catch (RuntimeException ex) {
				log.warn("Could not {} after the commit ({})", what, ex.getClass().getSimpleName());
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					guarded.run();
				}
			});
		}
		else {
			guarded.run();
		}
	}
}
