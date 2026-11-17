package com.abhiraj.archyra.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Platform statistics, ADMIN only (the route rule in the security chain, and re-checked by the service). */
@RestController
@RequestMapping("/api/v1/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

	private final AdminUserService adminUsers;

	@GetMapping
	public AdminStatsResponse stats() {
		return adminUsers.stats();
	}
}
