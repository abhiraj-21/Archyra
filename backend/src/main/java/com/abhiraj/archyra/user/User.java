package com.abhiraj.archyra.user;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An Archyra account ({@code users} table). The id is what the access token carries as {@code sub}.
 *
 * <p>Email is stored trimmed and lower-cased, see {@link UserService#normalizeEmail}. {@code passwordHash} is
 * null for accounts that only ever signed in through a social provider. Never print this entity: it holds the
 * password hash.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true, length = 254)
	private String email;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "password_hash", length = 100)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Plan plan;

	/** Written by billing later. Security only reads it: a PREMIUM plan past this instant counts as FREE. */
	@Column(name = "plan_expires_at")
	private Instant planExpiresAt;

	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "failed_login_attempts", nullable = false)
	private int failedLoginAttempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "password_changed_at")
	private Instant passwordChangedAt;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private User(String email, String name, String passwordHash, Role role, boolean emailVerified, Instant now) {
		this.email = email;
		this.name = name;
		this.passwordHash = passwordHash;
		this.role = role;
		this.plan = Plan.FREE;
		this.emailVerified = emailVerified;
		this.enabled = true;
		this.passwordChangedAt = passwordHash == null ? null : now;
	}

	/** A self-registered account: {@code USER}, {@code FREE}, email not yet verified. */
	public static User registered(String email, String name, String passwordHash, Instant now) {
		return new User(email, name, passwordHash, Role.USER, false, now);
	}

	/** An account created by a social login: the provider vouched for the email, so it is verified and has no password. */
	public static User fromSocialLogin(String email, String name) {
		return new User(email, name, null, Role.USER, true, null);
	}

	/** The bootstrap administrator: verified and enabled. Admins are never created through register or OAuth2. */
	public static User administrator(String email, String name, String passwordHash, Instant now) {
		return new User(email, name, passwordHash, Role.ADMIN, true, now);
	}

	/**
	 * The plan that counts right now: PREMIUM only while {@code planExpiresAt} is unset or still in the future. The
	 * stored plan is never trusted on its own, so an expired subscription stops granting access without any job.
	 */
	public Plan effectivePlan(Instant now) {
		if (plan == Plan.PREMIUM && (planExpiresAt == null || planExpiresAt.isAfter(now))) {
			return Plan.PREMIUM;
		}
		return Plan.FREE;
	}

	/** Called by billing (out of scope here) when a subscription starts, renews or ends. */
	public void changePlan(Plan newPlan, Instant newExpiresAt) {
		this.plan = newPlan;
		this.planExpiresAt = newExpiresAt;
	}

	public boolean hasPassword() {
		return passwordHash != null;
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	/** Counts a failed password attempt and locks the account for {@code lockDuration} at {@code maxAttempts}. */
	public void recordFailedLogin(int maxAttempts, java.time.Duration lockDuration, Instant now) {
		if (lockedUntil != null && !lockedUntil.isAfter(now)) { // an expired lock starts a fresh count
			failedLoginAttempts = 0;
			lockedUntil = null;
		}
		failedLoginAttempts++;
		if (failedLoginAttempts >= maxAttempts) {
			lockedUntil = now.plus(lockDuration);
			failedLoginAttempts = 0;
		}
	}

	public void recordSuccessfulLogin(Instant now) {
		failedLoginAttempts = 0;
		lockedUntil = null;
		lastLoginAt = now;
	}

	public void resetLockout() {
		failedLoginAttempts = 0;
		lockedUntil = null;
	}

	public void markEmailVerified() {
		this.emailVerified = true;
	}

	public void changePassword(String newPasswordHash, Instant now) {
		this.passwordHash = newPasswordHash;
		this.passwordChangedAt = now;
	}

	/** Used when a social login takes over an account whose email was never verified (pre-hijack protection). */
	public void clearPassword(Instant now) {
		this.passwordHash = null;
		this.passwordChangedAt = now;
	}

	public void rename(String newName) {
		this.name = newName;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public void setRole(Role role) {
		this.role = role;
	}
}
