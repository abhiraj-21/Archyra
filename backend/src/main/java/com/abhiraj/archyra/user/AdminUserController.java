package com.abhiraj.archyra.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.security.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/** Account administration, ADMIN only (the route rule in the security chain, and re-checked by the service). */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

	/** {@code enabled} is the only thing this endpoint can change. */
	public record StatusRequest(@NotNull(message = "enabled must be true or false") Boolean enabled) {
	}

	private final AdminUserService adminUsers;
	private final ClientIpResolver clientIps;

	@GetMapping
	public AdminUserPageResponse list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return adminUsers.list(page, size);
	}

	@GetMapping("/{userId}")
	public AdminUserResponse get(@PathVariable String userId) {
		return adminUsers.get(userId);
	}

	@PatchMapping("/{userId}/status")
	public AdminUserResponse setStatus(@PathVariable String userId, @Valid @RequestBody StatusRequest request,
			HttpServletRequest http) {
		return adminUsers.setEnabled(userId, request.enabled(), clientIps.resolve(http));
	}
}
