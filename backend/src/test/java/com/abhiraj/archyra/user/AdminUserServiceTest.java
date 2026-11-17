package com.abhiraj.archyra.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.common.AdminRuleViolationException;
import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.common.UserNotFoundException;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.TestCaller;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

	@Mock
	private UserRepository users;
	@Mock
	private LinkedIdentityRepository identities;
	@Mock
	private ApplicationEventPublisher events;
	@Mock
	private SecurityAuditLogger audit;
	@Mock
	private AnalysisService analyses;
	private AdminUserService service;

	private User admin;
	private User user;

	@BeforeEach
	void setUp() {
		service = new AdminUserService(users, identities, events, audit, analyses, Clock.fixed(NOW, ZoneOffset.UTC));
		admin = withId(User.administrator("admin@example.com", "Admin", "{bcrypt}x", NOW));
		user = withId(User.registered("user@example.com", "User", "{bcrypt}x", NOW));
	}

	private static User withId(User user) {
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(user, "createdAt", NOW);
		return user;
	}

	private <T> T asAdmin(java.util.function.Supplier<T> action) {
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));
		return TestCaller.runAs(admin.getId(), action);
	}

	// ---- who may call ------------------------------------------------------------------------------------

	@Test
	void aNonAdministratorIsRefusedEvenWithAnAdminToken() {
		given(users.findById(user.getId())).willReturn(Optional.of(user));

		assertThatThrownBy(() -> TestCaller.runAs(user.getId(), () -> service.list(0, 10))).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> TestCaller.runAs(user.getId(), () -> service.setEnabled(user.getId().toString(), false, "ip")))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void aDisabledAdministratorWithAStillValidTokenIsRefused() {
		admin.setEnabled(false);

		assertThatThrownBy(() -> asAdmin(() -> service.list(0, 10))).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void aCallerWhoNoLongerExistsOrIsNotSignedInIsRefused() {
		assertThatThrownBy(() -> service.list(0, 10)).isInstanceOf(AccessDeniedException.class);
		given(users.findById(admin.getId())).willReturn(Optional.empty());
		assertThatThrownBy(() -> TestCaller.runAs(admin.getId(), () -> service.list(0, 10))).isInstanceOf(AccessDeniedException.class);
	}

	// ---- statistics ----------------------------------------------------------------------------------------

	@Test
	void statsComeFromCountQueriesWithTheActiveWindowAndTheCurrentTime() {
		given(users.count()).willReturn(1240L);
		given(users.countByEnabledTrueAndLastLoginAtGreaterThanEqual(NOW.minus(AdminUserService.ACTIVE_WINDOW))).willReturn(438L);
		given(users.countEffectivePremium(NOW)).willReturn(82L);
		given(analyses.countAll()).willReturn(3612L);

		AdminStatsResponse stats = asAdmin(() -> service.stats());

		assertThat(stats).isEqualTo(new AdminStatsResponse(1240, 438, 82, 3612));
	}

	@Test
	void statsAreRefusedForANonAdministratorWithoutTouchingTheCounts() {
		given(users.findById(user.getId())).willReturn(Optional.of(user));

		assertThatThrownBy(() -> TestCaller.runAs(user.getId(), () -> service.stats())).isInstanceOf(AccessDeniedException.class);
		verify(users, never()).count();
		verifyNoInteractions(analyses);
	}

	// ---- reading -------------------------------------------------------------------------------------------

	@Test
	void anUnknownOrMalformedUserIdIsNotFound() {
		given(users.findById(any(UUID.class))).willReturn(Optional.of(admin));
		given(users.findById(org.mockito.ArgumentMatchers.eq(UUID.fromString("00000000-0000-4000-8000-000000000001")))).willReturn(Optional.empty());

		assertThatThrownBy(() -> TestCaller.runAs(admin.getId(), () -> service.get("00000000-0000-4000-8000-000000000001")))
				.isInstanceOf(UserNotFoundException.class);
		assertThatThrownBy(() -> TestCaller.runAs(admin.getId(), () -> service.get("not-a-uuid"))).isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void listValidatesPaging() {
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));

		assertThatThrownBy(() -> TestCaller.runAs(admin.getId(), () -> service.list(-1, 10))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> TestCaller.runAs(admin.getId(), () -> service.list(0, 201))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> TestCaller.runAs(admin.getId(), () -> service.list(0, 0))).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void theResponseShowsTheEffectivePlanAndNoSecrets() {
		user.changePlan(Plan.PREMIUM, NOW.minusSeconds(1)); // expired
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));
		given(users.findById(user.getId())).willReturn(Optional.of(user));
		given(identities.findByUserId(user.getId())).willReturn(List.of(new LinkedIdentity(user.getId(), Provider.GITHUB, "1", "user@example.com")));

		AdminUserResponse response = TestCaller.runAs(admin.getId(), () -> service.get(user.getId().toString()));

		assertThat(response.plan()).isEqualTo(Plan.FREE);
		assertThat(response.linkedProviders()).containsExactly(Provider.GITHUB);
		assertThat(response.toString()).doesNotContain("bcrypt").doesNotContainIgnoringCase("password");
		assertThat(AdminUserResponse.class.getRecordComponents()).extracting(c -> c.getName().toLowerCase())
				.noneMatch(name -> name.contains("password") || name.contains("hash") || name.contains("token"));
	}

	// ---- disabling -----------------------------------------------------------------------------------------

	@Test
	void disablingAUserEndsTheirSessionsAndIsAudited() {
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));
		given(users.lockEnabledByRole(Role.ADMIN)).willReturn(List.of(admin));
		given(users.findById(user.getId())).willReturn(Optional.of(user));

		AdminUserResponse response = TestCaller.runAs(admin.getId(), () -> service.setEnabled(user.getId().toString(), false, "203.0.113.1"));

		assertThat(response.enabled()).isFalse();
		assertThat(user.isEnabled()).isFalse();
		verify(events).publishEvent(new UserDisabledEvent(user.getId()));
		verify(audit).event("USER_DISABLED", user.getId(), "203.0.113.1", admin.getId());
	}

	@Test
	void enablingAUserIsAuditedAndNeedsNoSessionChange() {
		user.setEnabled(false);
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));
		given(users.lockEnabledByRole(Role.ADMIN)).willReturn(List.of(admin));
		given(users.findById(user.getId())).willReturn(Optional.of(user));

		TestCaller.runAs(admin.getId(), () -> service.setEnabled(user.getId().toString(), true, "ip"));

		assertThat(user.isEnabled()).isTrue();
		verify(events, never()).publishEvent(any(Object.class));
		verify(audit).event("USER_ENABLED", user.getId(), "ip", admin.getId());
	}

	@Test
	void asking_forTheStateAUserAlreadyHasChangesNothing() {
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));
		given(users.lockEnabledByRole(Role.ADMIN)).willReturn(List.of(admin));
		given(users.findById(user.getId())).willReturn(Optional.of(user));

		TestCaller.runAs(admin.getId(), () -> service.setEnabled(user.getId().toString(), true, "ip"));

		verify(events, never()).publishEvent(any(Object.class));
		verifyNoInteractions(audit);
	}

	@Test
	void anAdministratorCannotDisableThemselves() {
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));

		assertThatThrownBy(() -> TestCaller.runAs(admin.getId(), () -> service.setEnabled(admin.getId().toString(), false, "ip")))
				.isInstanceOfSatisfying(AdminRuleViolationException.class, ex -> assertThat(ex.code()).isEqualTo("CANNOT_DISABLE_SELF"));
		assertThat(admin.isEnabled()).isTrue();
	}

	@Test
	void theLastEnabledAdministratorCannotBeDisabled() {
		User other = withId(User.administrator("other@example.com", "Other", "{bcrypt}x", NOW));
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));
		given(users.lockEnabledByRole(Role.ADMIN)).willReturn(List.of(other)); // only one left, and it is not the caller
		given(users.findById(other.getId())).willReturn(Optional.of(other));

		assertThatThrownBy(() -> TestCaller.runAs(admin.getId(), () -> service.setEnabled(other.getId().toString(), false, "ip")))
				.isInstanceOfSatisfying(AdminRuleViolationException.class, ex -> assertThat(ex.code()).isEqualTo("LAST_ADMIN"));
		assertThat(other.isEnabled()).isTrue();
		verifyNoInteractions(events);
	}

	@Test
	void anAdministratorCanBeDisabledWhileAnotherRemains() {
		User other = withId(User.administrator("other@example.com", "Other", "{bcrypt}x", NOW));
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));
		given(users.lockEnabledByRole(Role.ADMIN)).willReturn(List.of(admin, other));
		given(users.findById(other.getId())).willReturn(Optional.of(other));

		TestCaller.runAs(admin.getId(), () -> service.setEnabled(other.getId().toString(), false, "ip"));

		assertThat(other.isEnabled()).isFalse();
		ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
		verify(events).publishEvent(event.capture());
		assertThat(event.getValue()).isEqualTo(new UserDisabledEvent(other.getId()));
	}

	@Test
	void disablingAnUnknownUserIsNotFound() {
		given(users.findById(admin.getId())).willReturn(Optional.of(admin));
		given(users.lockEnabledByRole(Role.ADMIN)).willReturn(List.of(admin));
		UUID missing = UUID.randomUUID();
		given(users.findById(missing)).willReturn(Optional.empty());

		assertThatThrownBy(() -> TestCaller.runAs(admin.getId(), () -> service.setEnabled(missing.toString(), false, "ip")))
				.isInstanceOf(UserNotFoundException.class);
	}
}
