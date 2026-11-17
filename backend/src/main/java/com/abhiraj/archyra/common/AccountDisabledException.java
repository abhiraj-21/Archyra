package com.abhiraj.archyra.common;

/** The password was right but an administrator has disabled the account. */
public class AccountDisabledException extends RuntimeException {

	public AccountDisabledException() {
		super("This account has been disabled");
	}
}
