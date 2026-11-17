package com.abhiraj.archyra.usage;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.abhiraj.archyra.common.GlobalExceptionHandler;
import com.abhiraj.archyra.security.SecuritySliceConfig;
import com.abhiraj.archyra.user.Plan;

@WebMvcTest(UsageController.class)
@Import({ GlobalExceptionHandler.class, SecuritySliceConfig.class })
class UsageControllerTest {

	private static final String ME = "6f1c1d8e-3a52-4b55-9d0e-0a8b1c2d3e4f";
	private static final UUID ME_ID = UUID.fromString(ME);

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private UsageService usageService;

	@Test
	@WithAnonymousUser
	void withoutAuthenticationItIs401() throws Exception {
		mockMvc.perform(get("/api/v1/usage")).andExpect(status().isUnauthorized());

		verifyNoInteractions(usageService);
	}

	@Test
	@WithMockUser(username = ME)
	void aFreeUserSeesUsedLimitRemainingAndLimitReached() throws Exception {
		given(usageService.usageOf(ME_ID)).willReturn(new UsageResponse(Plan.FREE, List.of(
				UsageEntry.limited(UsageType.REPOSITORY_ANALYSIS, 3, 5), UsageEntry.limited(UsageType.AI_QUERY, 0, 20))));

		mockMvc.perform(get("/api/v1/usage"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$.plan").value("FREE"))
				.andExpect(jsonPath("$.usage.length()").value(2))
				.andExpect(jsonPath("$.usage[0].length()").value(5))
				.andExpect(jsonPath("$.usage[0].usageType").value("REPOSITORY_ANALYSIS"))
				.andExpect(jsonPath("$.usage[0].used").value(3))
				.andExpect(jsonPath("$.usage[0].limit").value(5))
				.andExpect(jsonPath("$.usage[0].remaining").value(2))
				.andExpect(jsonPath("$.usage[0].limitReached").value(false))
				.andExpect(jsonPath("$.usage[1].usageType").value("AI_QUERY"))
				.andExpect(jsonPath("$.usage[1].used").value(0))
				.andExpect(jsonPath("$.usage[1].limit").value(20))
				.andExpect(jsonPath("$.usage[1].remaining").value(20))
				.andExpect(jsonPath("$.usage[1].limitReached").value(false));
	}

	@Test
	@WithMockUser(username = ME)
	void aFreeUserAtTheCap() throws Exception {
		given(usageService.usageOf(ME_ID)).willReturn(new UsageResponse(Plan.FREE, List.of(
				UsageEntry.limited(UsageType.REPOSITORY_ANALYSIS, 5, 5))));

		mockMvc.perform(get("/api/v1/usage"))
				.andExpect(jsonPath("$.usage[0].used").value(5))
				.andExpect(jsonPath("$.usage[0].limit").value(5))
				.andExpect(jsonPath("$.usage[0].remaining").value(0))
				.andExpect(jsonPath("$.usage[0].limitReached").value(true));
	}

	@Test
	@WithMockUser(username = ME)
	void aPremiumUserHasExplicitNullsAndNeverReachesALimit() throws Exception {
		given(usageService.usageOf(ME_ID)).willReturn(new UsageResponse(Plan.PREMIUM, List.of(
				UsageEntry.unlimited(UsageType.REPOSITORY_ANALYSIS, 12), UsageEntry.unlimited(UsageType.AI_QUERY, 0))));

		mockMvc.perform(get("/api/v1/usage"))
				.andExpect(jsonPath("$.plan").value("PREMIUM"))
				.andExpect(jsonPath("$.usage[0].length()").value(5)) // limit and remaining are present, as null
				.andExpect(jsonPath("$.usage[0].used").value(12))
				.andExpect(jsonPath("$.usage[0].limit").value((Object) null))
				.andExpect(jsonPath("$.usage[0].remaining").value((Object) null))
				.andExpect(jsonPath("$.usage[0].limitReached").value(false))
				.andExpect(jsonPath("$.usage[1].limit").value((Object) null));
	}

	@Test
	@WithMockUser(username = ME)
	void theServiceIsCalledWithTheAuthenticatedUserAndAUserIdParameterIsIgnored() throws Exception {
		UUID someoneElse = UUID.randomUUID();
		given(usageService.usageOf(ME_ID)).willReturn(new UsageResponse(Plan.FREE, List.of()));

		mockMvc.perform(get("/api/v1/usage").param("userId", someoneElse.toString()).header("X-User-Id", someoneElse.toString()))
				.andExpect(status().isOk());

		verify(usageService).usageOf(ME_ID);
		org.mockito.Mockito.verifyNoMoreInteractions(usageService);
	}

	@Test
	@WithMockUser(username = ME)
	void theCountersCannotBeChangedThroughTheApi() throws Exception {
		mockMvc.perform(post("/api/v1/usage").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
				.contentType("application/json").content("{\"used\":0}")).andExpect(status().isMethodNotAllowed());

		verifyNoInteractions(usageService);
	}
}
