package com.abhiraj.archyra.common;

import java.util.UUID;

/**
 * Published by {@code AccountDeletionService.afterDeletion}, once the deletion has committed and outside any transaction,
 * for features that hold something outside our database for the account (billing cancels a live Razorpay subscription).
 * Listeners must never throw: nothing may fail a deletion that already happened.
 */
public record AccountDeleted(UUID userId) {
}
