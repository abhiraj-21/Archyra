package com.abhiraj.archyra.analysis;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.abhiraj.archyra.common.GlobalExceptionHandler;
import com.abhiraj.archyra.common.ServiceBusyException;

@WebMvcTest(AnalysisController.class)
@Import(GlobalExceptionHandler.class)
class AnalysisControllerTest {

	private static final String URL = "/api/v1/analyses";

	private static final String ANALYSIS_ID = UUID.randomUUID().toString();

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AnalysisService analysisService;

	@BeforeEach
	void stubService() {
		given(analysisService.createAnalysis(any()))
				.willReturn(new CreateAnalysisResponse(ANALYSIS_ID, AnalysisStatus.QUEUED));
	}

	private static String body(String repositoryUrl) {
		return "{\"repositoryUrl\":\"" + repositoryUrl + "\"}";
	}

	@Test
	void validRequestReturns202WithServiceResultAndDelegatesToService() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
						.content(body("https://github.com/spring-projects/spring-boot")))
				.andExpect(status().isAccepted())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.length()").value(2));

		verify(analysisService).createAnalysis(
				new CreateAnalysisRequest("https://github.com/spring-projects/spring-boot"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"https://github.com/owner/repo",
			"https://github.com/Owner-1/my_repo.v2",
			"https://github.com/owner/.github"
	})
	void acceptsValidGitHubUrls(String url) throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(url)))
				.andExpect(status().isAccepted());
	}

	@Test
	void missingRepositoryUrlReturns400() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value(containsString("must not be blank")));
	}

	@Test
	void nullRepositoryUrlReturns400() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":null}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   "})
	void blankRepositoryUrlReturns400(String url) throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(url)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value(containsString("must not be blank")));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"not-a-url",
			"http://github.com/owner/repo",
			"file:///etc/passwd",
			"https://gitlab.com/owner/repo",
			"https://github.com.evil.com/owner/repo",
			"https://github.com/owner",
			"https://github.com/owner/repo/tree/main",
			"https://github.com/owner/repo?x=1",
			"https://github.com/../repo",
			"https://github.com/owner/..",
			"https://github.com/owner/re..po",
			"https://github.com/owner/repo/../other"
	})
	void invalidRepositoryUrlReturns400(String url) throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(url)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value(containsString("GitHub repository URL")));
		verifyNoInteractions(analysisService);
	}

	@Test
	void missingBodyReturns400() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
	}

	@Test
	void fullAnalysisQueueReturns503WithMachineReadableCode() throws Exception {
		given(analysisService.createAnalysis(any())).willThrow(new ServiceBusyException("Too many analyses are running."));

		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
						.content(body("https://github.com/owner/repo")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
				.andExpect(jsonPath("$.message").value("Too many analyses are running."));
	}
}
