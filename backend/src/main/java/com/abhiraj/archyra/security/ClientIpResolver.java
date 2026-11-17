package com.abhiraj.archyra.security;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address of the client, for rate limiting and audit lines. It is the request's remote address and nothing
 * else: {@code X-Forwarded-For} is never read here. Behind a reverse proxy the servlet container has already
 * replaced the remote address with the real client, but only for proxies it trusts
 * ({@code server.forward-headers-strategy=native}, {@code server.tomcat.remoteip.internal-proxies}), so a client
 * cannot choose its own address by sending the header. One class so that rule lives in one place.
 */
@Component
public class ClientIpResolver {

	public String resolve(HttpServletRequest request) {
		return request.getRemoteAddr();
	}
}
