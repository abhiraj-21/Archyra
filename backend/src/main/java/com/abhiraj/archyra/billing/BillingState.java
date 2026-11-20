package com.abhiraj.archyra.billing;

/** Where a user stands with Premium; see {@link BillingDtos.StatusResponse}. */
enum BillingState {
	NONE,
	ACTIVE,
	EXPIRING,
	GRACE,
	LAPSED
}
