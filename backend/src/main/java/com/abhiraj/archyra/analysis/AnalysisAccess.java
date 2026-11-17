package com.abhiraj.archyra.analysis;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.security.CurrentUser;

/**
 * Who is asking, for the ownership rule of the analysis feature: an analysis belongs to the user who started it and
 * to nobody else, administrators included. {@link AnalysisService} compares owners and answers 404 for anything the
 * caller does not own, so ids cannot be probed.
 */
@Component
class AnalysisAccess {

	/** The signed-in caller's user id. A request that got this far without one is answered 401. */
	UUID callerId() {
		return CurrentUser.id().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No signed-in user"));
	}
}
