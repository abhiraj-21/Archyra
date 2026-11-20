package com.abhiraj.archyra.analysis;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.abhiraj.archyra.common.AnalysisNotFoundException;
import com.abhiraj.archyra.common.GlobalExceptionHandler;
import com.abhiraj.archyra.security.SecuritySliceConfig;
import com.abhiraj.archyra.common.ServiceBusyException;

@WebMvcTest(AnalysisController.class)
@Import({GlobalExceptionHandler.class, SecuritySliceConfig.class})
@WithMockUser
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
			"https://github.com/owner/repo/../other",
			"https://user:secret@github.com/owner/repo",
			"https://token@github.com/owner/repo",
			"https://github.com:443@evil.example/owner/repo",
			"ssh://git@github.com/owner/repo",
			"git://github.com/owner/repo",
			"git@github.com:owner/repo.git",
			"https://github.com/owner/repo\\n",
			"https://github.com/owner/repo ",
			"--upload-pack=touch /tmp/x",
			"https://github.com/-owner/repo;rm -rf /"
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

	@Test
	void statusReturns200WithIdStatusAndProgress() throws Exception {
		given(analysisService.getStatus(ANALYSIS_ID))
				.willReturn(new AnalysisStatusResponse(ANALYSIS_ID, AnalysisStatus.PARSING, 25, null));

		mockMvc.perform(get(URL + "/" + ANALYSIS_ID))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID))
				.andExpect(jsonPath("$.status").value("PARSING"))
				.andExpect(jsonPath("$.progress").value(25))
				.andExpect(jsonPath("$.failureReason").doesNotExist())
				.andExpect(jsonPath("$.length()").value(3));
	}

	@Test
	void statusOfFailedAnalysisIncludesTheFailureReason() throws Exception {
		given(analysisService.getStatus(ANALYSIS_ID)).willReturn(
				new AnalysisStatusResponse(ANALYSIS_ID, AnalysisStatus.FAILED, 25, "Repository is too large"));

		mockMvc.perform(get(URL + "/" + ANALYSIS_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.progress").value(25))
				.andExpect(jsonPath("$.failureReason").value("Repository is too large"))
				.andExpect(jsonPath("$.length()").value(4));
	}

	@Test
	void statusOfUnknownAnalysisReturns404WithMachineReadableCode() throws Exception {
		given(analysisService.getStatus(ANALYSIS_ID)).willThrow(new AnalysisNotFoundException("Analysis not found"));

		mockMvc.perform(get(URL + "/" + ANALYSIS_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Analysis not found"));
	}

	// ---- list and delete -----------------------------------------------------------------------

	@Test
	void listReturnsTheCallersAnalysesWithPaging() throws Exception {
		given(analysisService.list(0, 50)).willReturn(new AnalysisPageResponse(
				java.util.List.of(new AnalysisSummary(ANALYSIS_ID, "https://github.com/owner/repo", AnalysisStatus.COMPLETED, 100,
						java.time.Instant.parse("2026-01-01T00:00:00Z"))), 0, 50, 1, 1));

		mockMvc.perform(get(URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].analysisId").value(ANALYSIS_ID))
				.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void listPassesPageAndSizeToTheService() throws Exception {
		given(analysisService.list(2, 10)).willReturn(new AnalysisPageResponse(java.util.List.of(), 2, 10, 0, 0));

		mockMvc.perform(get(URL).param("page", "2").param("size", "10")).andExpect(status().isOk());

		verify(analysisService).list(2, 10);
	}

	@Test
	void listWithANonNumericPageIs400() throws Exception {
		mockMvc.perform(get(URL).param("page", "x")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void deleteReturns204() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(URL + "/" + ANALYSIS_ID))
				.andExpect(status().isNoContent());

		verify(analysisService).delete(ANALYSIS_ID);
	}

	@Test
	void deletingSomeoneElsesOrUnknownAnalysisIs404() throws Exception {
		org.mockito.Mockito.doThrow(new AnalysisNotFoundException("Analysis not found")).when(analysisService).delete(ANALYSIS_ID);

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(URL + "/" + ANALYSIS_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
	}

	@Test
	void deletingARunningAnalysisIs409() throws Exception {
		org.mockito.Mockito.doThrow(new com.abhiraj.archyra.common.AnalysisInProgressException("still running"))
				.when(analysisService).delete(ANALYSIS_ID);

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(URL + "/" + ANALYSIS_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ANALYSIS_IN_PROGRESS"));
	}

	@Test
	void anExceededAllowanceIs403WithTheUsageLimitCodeAndTheMessage() throws Exception {
		given(analysisService.createAnalysis(any())).willThrow(new com.abhiraj.archyra.common.UsageLimitExceededException(
				"You have used all 5 free repository analyses. Upgrade to Archyra Premium to continue."));

		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("https://github.com/owner/repo")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$.code").value("USAGE_LIMIT_EXCEEDED"))
				.andExpect(jsonPath("$.message").value("You have used all 5 free repository analyses. Upgrade to Archyra Premium to continue."));
	}

	// ---- reanalyze -----------------------------------------------------------------------------

	private static final String REANALYZE = URL + "/" + ANALYSIS_ID + "/reanalyze";

	@Test
	void reanalyzeReturns202WithTheNewAnalysisInTheSameShapeAsCreate() throws Exception {
		given(analysisService.reanalyze(ANALYSIS_ID)).willReturn(new CreateAnalysisResponse("new-id", AnalysisStatus.QUEUED));

		mockMvc.perform(post(REANALYZE))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$.analysisId").value("new-id"))
				.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	void reanalyzeOfAnUnknownOrForeignAnalysisIs404() throws Exception {
		given(analysisService.reanalyze(ANALYSIS_ID)).willThrow(new AnalysisNotFoundException("Analysis not found"));

		mockMvc.perform(post(REANALYZE))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
	}

	@Test
	void reanalyzeAtTheLimitIs403() throws Exception {
		given(analysisService.reanalyze(ANALYSIS_ID)).willThrow(new com.abhiraj.archyra.common.UsageLimitExceededException("limit"));

		mockMvc.perform(post(REANALYZE))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USAGE_LIMIT_EXCEEDED"));
	}

	@Test
	void reanalyzeWithAFullQueueIs503() throws Exception {
		given(analysisService.reanalyze(ANALYSIS_ID)).willThrow(new ServiceBusyException("busy"));

		mockMvc.perform(post(REANALYZE))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
	}

	// ---- analyze a restored entry --------------------------------------------------------------

	private static final String ANALYZE = URL + "/" + ANALYSIS_ID + "/analyze";

	@Test
	void analyzeReturns202InTheSameShapeAsCreate() throws Exception {
		given(analysisService.analyze(ANALYSIS_ID)).willReturn(new CreateAnalysisResponse(ANALYSIS_ID, AnalysisStatus.QUEUED));

		mockMvc.perform(post(ANALYZE))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID))
				.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	void analyzeOfAnythingButANotAnalysedEntryIs409AnalysisNotRestorable() throws Exception {
		given(analysisService.analyze(ANALYSIS_ID)).willThrow(new com.abhiraj.archyra.common.AnalysisNotRestorableException("no"));

		mockMvc.perform(post(ANALYZE))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_RESTORABLE"));
	}

	@Test
	void analyzeOfAnUnknownOrForeignAnalysisIs404AndAFullQueueIs503() throws Exception {
		given(analysisService.analyze(ANALYSIS_ID)).willThrow(new AnalysisNotFoundException("Analysis not found"))
				.willThrow(new ServiceBusyException("busy"));

		mockMvc.perform(post(ANALYZE)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
		mockMvc.perform(post(ANALYZE)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
	}
}
