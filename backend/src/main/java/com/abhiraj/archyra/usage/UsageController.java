package com.abhiraj.archyra.usage;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.security.CurrentUser;

import lombok.RequiredArgsConstructor;

/** The signed-in user's own usage. The user comes from the security context only; there is no id parameter. */
@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

	private final UsageService usageService;

	@GetMapping
	public UsageResponse usage() {
		return usageService.usageOf(CurrentUser.id()
				.orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No signed-in user")));
	}
}
