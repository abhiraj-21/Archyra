package com.abhiraj.archyra.codebase;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.abhiraj.archyra.common.AnalysisNotCompletedException;
import com.abhiraj.archyra.common.AnalysisNotFoundException;
import com.abhiraj.archyra.common.GlobalExceptionHandler;
import com.abhiraj.archyra.security.SecuritySliceConfig;

@WebMvcTest(RepositoryController.class)
@Import({GlobalExceptionHandler.class, SecuritySliceConfig.class})
@WithMockUser
class RepositoryControllerTest {

	private static final String ANALYSIS_ID = UUID.randomUUID().toString();
	private static final String URL = "/api/v1/analyses/" + ANALYSIS_ID + "/repository";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RepositoryService repositoryService;

	@Test
	void returnsTheRepositoryInformation() throws Exception {
		given(repositoryService.getRepository(ANALYSIS_ID))
				.willReturn(new RepositoryResponse("payment-service", List.of("Java", "SQL"), 143, 87, 412,
						List.of(new TechnologyResponse("Spring Boot", "BACKEND_FRAMEWORK"), new TechnologyResponse("Maven", "BUILD_TOOL"))));

		mockMvc.perform(get(URL))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.name").value("payment-service"))
				.andExpect(jsonPath("$.languages[0]").value("Java"))
				.andExpect(jsonPath("$.languages[1]").value("SQL"))
				.andExpect(jsonPath("$.files").value(143))
				.andExpect(jsonPath("$.classes").value(87))
				.andExpect(jsonPath("$.methods").value(412))
				.andExpect(jsonPath("$.technologies.length()").value(2))
				.andExpect(jsonPath("$.technologies[0].name").value("Spring Boot"))
				.andExpect(jsonPath("$.technologies[0].category").value("BACKEND_FRAMEWORK"))
				.andExpect(jsonPath("$.technologies[1].name").value("Maven"))
				.andExpect(jsonPath("$.technologies[1].category").value("BUILD_TOOL"))
				.andExpect(jsonPath("$.length()").value(6));
	}

	@Test
	void noTechnologiesIsAnEmptyArray() throws Exception {
		given(repositoryService.getRepository(ANALYSIS_ID))
				.willReturn(new RepositoryResponse("tiny", List.of("Python"), 1, 0, 2, List.of()));

		mockMvc.perform(get(URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.technologies").isArray())
				.andExpect(jsonPath("$.technologies.length()").value(0));
	}

	@Test
	void unknownAnalysisReturns404() throws Exception {
		given(repositoryService.getRepository(ANALYSIS_ID)).willThrow(new AnalysisNotFoundException("Analysis not found"));

		mockMvc.perform(get(URL))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Analysis not found"));
	}

	@Test
	void unfinishedAnalysisReturns409() throws Exception {
		given(repositoryService.getRepository(ANALYSIS_ID))
				.willThrow(new AnalysisNotCompletedException("The analysis has not completed yet (status: PARSING)"));

		mockMvc.perform(get(URL))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"))
				.andExpect(jsonPath("$.message").value("The analysis has not completed yet (status: PARSING)"));
	}
}
