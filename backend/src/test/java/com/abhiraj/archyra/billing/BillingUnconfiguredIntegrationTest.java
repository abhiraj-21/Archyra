package com.abhiraj.archyra.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

/** Without Razorpay keys the application starts, the plans and the status still answer, and everything else is 503. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class BillingUnconfiguredIntegrationTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService users;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JwtService jwtService;

	@Test
	void everyBillingRouteButPlansAndStatusIsUnavailable() throws Exception {
		User user = users.registerIfAbsent("unconfigured-" + UUID.randomUUID() + "@example.com", "U", passwordEncoder.encode("irrelevant"),
				Instant.now()).orElseThrow();
		String bearer = "Bearer " + jwtService.issueFor(user).value();

		MvcResult plans = mockMvc.perform(get("/api/v1/billing/plans").header("Authorization", bearer)).andReturn();
		assertThat(plans.getResponse().getStatus()).isEqualTo(200);
		assertThat((Boolean) JsonPath.read(plans.getResponse().getContentAsString(), "$.enabled")).isFalse();
		assertThat((Object) JsonPath.read(plans.getResponse().getContentAsString(), "$.keyId")).isNull();
		assertThat(mockMvc.perform(get("/api/v1/billing/status").header("Authorization", bearer)).andReturn().getResponse().getStatus()).isEqualTo(200);

		for (String path : List.of("/api/v1/billing/orders", "/api/v1/billing/orders/verify", "/api/v1/billing/subscriptions",
				"/api/v1/billing/subscriptions/verify", "/api/v1/billing/subscriptions/cancel")) {
			MvcResult result = mockMvc.perform(post(path).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
					.content("{\"period\":\"MONTHLY\"}")).andReturn();
			assertThat(result.getResponse().getStatus()).as(path).isEqualTo(503);
			assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.code")).isEqualTo("BILLING_UNAVAILABLE");
		}
		MvcResult webhook = mockMvc.perform(post("/api/v1/billing/webhook").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
		assertThat(webhook.getResponse().getStatus()).isEqualTo(503);
	}
}
