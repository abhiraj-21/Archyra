package com.abhiraj.archyra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.common.PremiumRequiredException;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

	@Mock
	private UserService userService;
	private EntitlementService entitlements;
	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		entitlements = new EntitlementService(userService, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private User user(Plan plan, Instant expiresAt) {
		User user = User.registered("a@example.com", "A", null, NOW);
		ReflectionTestUtils.setField(user, "id", userId);
		user.changePlan(plan, expiresAt);
		given(userService.findById(userId)).willReturn(Optional.of(user));
		return user;
	}

	@Test
	void aFreeUserIsFree() {
		user(Plan.FREE, null);

		assertThat(entitlements.effectivePlan(userId)).isEqualTo(Plan.FREE);
	}

	@Test
	void premiumWithoutAnExpiryIsPremium() {
		user(Plan.PREMIUM, null);

		assertThat(entitlements.effectivePlan(userId)).isEqualTo(Plan.PREMIUM);
	}

	@Test
	void premiumBeforeItsExpiryIsPremium() {
		user(Plan.PREMIUM, NOW.plusSeconds(1));

		assertThat(entitlements.effectivePlan(userId)).isEqualTo(Plan.PREMIUM);
	}

	@Test
	void premiumAtOrAfterItsExpiryIsFree() {
		user(Plan.PREMIUM, NOW);

		assertThat(entitlements.effectivePlan(userId)).isEqualTo(Plan.FREE);
	}

	@Test
	void aDisabledPremiumUserIsFree() {
		user(Plan.PREMIUM, null).setEnabled(false);

		assertThat(entitlements.effectivePlan(userId)).isEqualTo(Plan.FREE);
	}

	@Test
	void aMissingUserIsFree() {
		given(userService.findById(userId)).willReturn(Optional.empty());

		assertThat(entitlements.effectivePlan(userId)).isEqualTo(Plan.FREE);
		assertThat(entitlements.canUse(userId, Feature.EXPERT_GRAPH)).isFalse();
	}

	@Test
	void requirePremiumRefusesAFreeCallerWithTheFeatureNameInTheMessage() {
		user(Plan.FREE, null);

		assertThatThrownBy(() -> TestCaller.runAs(userId, () -> {
			entitlements.requirePremium(Feature.EXPERT_GRAPH);
			return null;
		})).isInstanceOf(PremiumRequiredException.class).hasMessage("Expert graph requires Archyra Premium.");
	}

	@Test
	void requirePremiumLetsAnActivePremiumCallerThrough() {
		user(Plan.PREMIUM, NOW.plusSeconds(3600));

		assertThatCode(() -> TestCaller.runAs(userId, () -> {
			entitlements.requirePremium(Feature.EXPERT_GRAPH);
			return null;
		})).doesNotThrowAnyException();
	}

	@Test
	void requirePremiumRefusesWhenNobodyIsSignedIn() {
		assertThatThrownBy(() -> entitlements.requirePremium(Feature.INTERACTIVE_GRAPH))
				.isInstanceOf(PremiumRequiredException.class);
	}

	@ParameterizedTest
	@EnumSource(Feature.class)
	void everyFeatureNamesItselfInTheMessage(Feature feature) {
		assertThatThrownBy(() -> entitlements.requirePremium(feature)).hasMessage(feature.displayName() + " requires Archyra Premium.");
		assertThat(feature.displayName()).isNotBlank();
	}
}
