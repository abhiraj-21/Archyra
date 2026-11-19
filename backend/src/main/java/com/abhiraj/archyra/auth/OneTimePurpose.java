package com.abhiraj.archyra.auth;

enum OneTimePurpose {
	EMAIL_VERIFICATION,
	PASSWORD_RESET,
	/** Confirms deleting an account that has no password; valid for 15 minutes, single use. */
	ACCOUNT_DELETE
}
