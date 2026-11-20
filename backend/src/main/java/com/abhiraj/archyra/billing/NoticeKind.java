package com.abhiraj.archyra.billing;

/** The lifecycle steps of one Premium end date. Each happens at most once per user and end date ({@code billing_notices}). */
public enum NoticeKind {
	/** The reminder a few days before the end. */
	REMINDER,
	/** The "Premium has ended" email. */
	EXPIRED,
	/** The end of the grace period: the repositories beyond the kept ones were deleted and the plan went back to FREE. */
	DELETED
}
