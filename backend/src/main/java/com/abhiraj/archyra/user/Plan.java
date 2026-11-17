package com.abhiraj.archyra.user;

/** The subscription tier. A plan is not a role: it is read from the database whenever a premium feature is checked. */
public enum Plan {
	FREE,
	PREMIUM
}
