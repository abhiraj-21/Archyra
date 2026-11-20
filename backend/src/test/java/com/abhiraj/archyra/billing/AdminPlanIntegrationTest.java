package com.abhiraj.archyra.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;

/** {@code PUT /admin/users/{id}/plan}: how an administrator handles a refund or grants Premium by hand. */
class AdminPlanIntegrationTest extends BillingIntegrationSupport {

	private User admin() {
		return userService.save(User.administrator("admin-" + FakeRazorpay.random() + "@example.com", "Admin",
				passwordEncoder.encode("irrelevant"), clock.instant()));
	}

	private MvcResult setPlan(User caller, User target, String body) throws Exception {
		return mockMvc.perform(put("/api/v1/admin/users/" + target.getId() + "/plan").header("Authorization", bearer(caller))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}

	@Test
	void afterARefundAnAdministratorEndsPremiumAndTheChangeIsAudited() throws Exception {
		User user = newUser();
		String paymentId = pay(user, "MONTHLY");
		webhook(RazorpayEvents.eventId(), RazorpayEvents.refundProcessed(paymentId, 70000));
		assertThat(reload(user).getPlan()).isEqualTo(Plan.PREMIUM); // the refund alone changes nothing

		MvcResult result = setPlan(admin(), user, "{\"plan\":\"FREE\",\"expiresAt\":null}");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat((String) json(result, "$.plan")).isEqualTo("FREE");
		assertThat(reload(user).getPlan()).isEqualTo(Plan.FREE);
		assertThat(reload(user).getPlanExpiresAt()).isNull();
	}

	@Test
	void anAdministratorGrantsPremiumUntilADateOrWithoutAnEnd() throws Exception {
		User user = newUser();
		User admin = admin();

		assertThat(setPlan(admin, user, "{\"plan\":\"PREMIUM\",\"expiresAt\":\"2027-01-01T00:00:00Z\"}").getResponse().getStatus()).isEqualTo(200);
		assertThat(expiry(user)).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
		assertThat(setPlan(admin, user, "{\"plan\":\"PREMIUM\"}").getResponse().getStatus()).isEqualTo(200);
		assertThat(reload(user).getPlan()).isEqualTo(Plan.PREMIUM);
		assertThat(expiry(user)).isNull();
	}

	@Test
	void invalidRequestsAndNonAdministratorsAreRefused() throws Exception {
		User user = newUser();
		User admin = admin();

		assertThat(setPlan(admin, user, "{\"plan\":\"PREMIUM\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}").getResponse().getStatus()).isEqualTo(400);
		assertThat(setPlan(admin, user, "{\"plan\":\"FREE\",\"expiresAt\":\"2027-01-01T00:00:00Z\"}").getResponse().getStatus()).isEqualTo(400);
		assertThat(setPlan(admin, user, "{\"plan\":\"GOLD\"}").getResponse().getStatus()).isEqualTo(400);
		assertThat(setPlan(admin, user, "{}").getResponse().getStatus()).isEqualTo(400);
		assertThat(setPlan(user, user, "{\"plan\":\"PREMIUM\"}").getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(put("/api/v1/admin/users/" + java.util.UUID.randomUUID() + "/plan").header("Authorization", bearer(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"FREE\"}")).andReturn().getResponse().getStatus()).isEqualTo(404);
		assertThat(reload(user).getPlan()).isEqualTo(Plan.FREE);
	}
}
