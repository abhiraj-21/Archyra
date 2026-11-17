package com.abhiraj.archyra.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.common.ErrorResponse;
import com.abhiraj.archyra.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** The signed-in user's own account. */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

	/**
	 * The only editable field. Email, role, plan and status have no field here, so a request that carries them is
	 * simply ignored; the entity is never bound from a request.
	 */
	public record UpdateProfileRequest(
			@NotBlank(message = "name must not be blank")
			@Size(max = 100, message = "name must be at most 100 characters")
			@Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "name must not contain control characters") String name) {
	}

	private final UserService userService;

	@GetMapping
	public ResponseEntity<?> me(Authentication authentication) {
		return CurrentUser.id(authentication).flatMap(userService::profile)
				.<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(UserController::unauthorized);
	}

	@PatchMapping
	public ResponseEntity<?> update(Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
		return CurrentUser.id(authentication).flatMap(id -> userService.updateName(id, request.name()))
				.<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(UserController::unauthorized);
	}

	private static ResponseEntity<?> unauthorized() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(new ErrorResponse("AUTHENTICATION_REQUIRED", "Authentication is required"));
	}
}
