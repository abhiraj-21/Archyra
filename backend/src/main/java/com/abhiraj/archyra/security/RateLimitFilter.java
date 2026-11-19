package com.abhiraj.archyra.security;

import java.io.IOException;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Throttles the endpoints an attacker can hammer without an account, per client IP, before any authentication work:
 * login (brute force) and register, forgot-password and resend-verification (email flooding), plus the three account
 * deletion routes (password guessing and the confirmation email). A refused request is
 * answered {@code 429 RATE_LIMITED} with {@code Retry-After}. Not a Spring bean on purpose: it is created inside the
 * security chain, so it is not also registered as a servlet filter.
 *
 * <p>Creating analyses is limited per user inside the service, because the user is not known at this point.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private static final RequestMatcher LOGIN = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/login");
	/** Deleting the account with the password, or with the emailed token: guessable, so the login bucket. */
	private static final RequestMatcher DELETE_ACCOUNT = new org.springframework.security.web.util.matcher.OrRequestMatcher(
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/users/me/delete"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/users/me/delete-confirm"));
	private static final RequestMatcher SENSITIVE = new org.springframework.security.web.util.matcher.OrRequestMatcher(
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/register"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/forgot-password"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/resend-verification"),
			// sends an email, like the others
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/users/me/delete-request"));

	private final RateLimiter rateLimiter;
	private final ClientIpResolver clientIps;
	private final SecurityAuditLogger audit;

	public RateLimitFilter(RateLimiter rateLimiter, ClientIpResolver clientIps, SecurityAuditLogger audit) {
		this.rateLimiter = rateLimiter;
		this.clientIps = clientIps;
		this.audit = audit;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		RateLimiter.Rule rule = ruleFor(request);
		if (rule != null) {
			String ip = clientIps.resolve(request);
			RateLimiter.Decision decision = rateLimiter.tryConsume(rule, ip);
			if (!decision.allowed()) {
				audit.warn("RATE_LIMITED_" + rule, null, ip);
				response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
				ApiErrorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Try again later.");
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private static RateLimiter.Rule ruleFor(HttpServletRequest request) {
		if (LOGIN.matches(request) || DELETE_ACCOUNT.matches(request)) {
			return RateLimiter.Rule.LOGIN;
		}
		if (SENSITIVE.matches(request)) {
			return RateLimiter.Rule.SENSITIVE;
		}
		return null;
	}
}
