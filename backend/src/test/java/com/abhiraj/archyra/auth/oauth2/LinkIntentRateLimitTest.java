package com.abhiraj.archyra.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

/** Starting a link is limited per user with the same RATE_LIMITED answer as the other limits. */
@SpringBootTest(properties = "app.security.rate-limit.link-intent-per-user-per-hour=2")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class LinkIntentRateLimitTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	JwtService jwtService;

	private MvcResult startLink(User user) throws Exception {
		return mockMvc.perform(post("/api/v1/users/me/linked-identities/google/link-intent")
				.header("Authorization", "Bearer " + jwtService.issueFor(user).value())).andReturn();
	}

	@Test
	void theThirdIntentInAnHourIsRefusedWithRetryAfterAndOtherUsersAreNotAffected() throws Exception {
		User user = userService.registerIfAbsent("rl-" + UUID.randomUUID() + "@example.com", "Rate", "x", Instant.now()).orElseThrow();
		User other = userService.registerIfAbsent("rl-" + UUID.randomUUID() + "@example.com", "Other", "x", Instant.now()).orElseThrow();

		assertThat(startLink(user).getResponse().getStatus()).isEqualTo(200);
		assertThat(startLink(user).getResponse().getStatus()).isEqualTo(200);
		MvcResult refused = startLink(user);

		assertThat(refused.getResponse().getStatus()).isEqualTo(429);
		assertThat((String) JsonPath.read(refused.getResponse().getContentAsString(), "$.code")).isEqualTo("RATE_LIMITED");
		assertThat(Long.parseLong(refused.getResponse().getHeader("Retry-After"))).isPositive();
		assertThat(refused.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("link_binding="));
		assertThat(startLink(other).getResponse().getStatus()).isEqualTo(200);
	}
}
