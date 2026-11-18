package com.abhiraj.archyra.graph;

import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;

@WebMvcTest(GraphController.class)
@Import({GlobalExceptionHandler.class, SecuritySliceConfig.class})
@WithMockUser
class GraphControllerTest {

	private static final String ANALYSIS_ID = UUID.randomUUID().toString();
	private static final String URL = "/api/v1/analyses/" + ANALYSIS_ID + "/graph";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private GraphService graphService;

	@MockitoBean
	private GraphPremiumGate premiumGate;

	@Test
	void returnsNodesAndEdgesInTheReactFlowShape() throws Exception {
		given(graphService.getGraph(ANALYSIS_ID, null, null)).willReturn(new GraphResponse(
				List.of(new GraphNode("class:UserService", NodeType.CLASS, "UserService", "src/UserService.java", false),
						new GraphNode("module:app", NodeType.MODULE, "app", null, true)),
				List.of(new GraphEdge("class:UserService", "class:UserRepository", RelationshipType.DEPENDS_ON))));

		mockMvc.perform(get(URL))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.nodes[0].id").value("class:UserService"))
				.andExpect(jsonPath("$.nodes[0].type").value("CLASS"))
				.andExpect(jsonPath("$.nodes[0].name").value("UserService"))
				.andExpect(jsonPath("$.nodes[0].file").value("src/UserService.java"))
				.andExpect(jsonPath("$.nodes[0].test").value(false))     // always present, never omitted
				.andExpect(jsonPath("$.nodes[0].length()").value(5))
				.andExpect(jsonPath("$.nodes[1].file").doesNotExist())   // no file, no field
				.andExpect(jsonPath("$.nodes[1].test").value(true))
				.andExpect(jsonPath("$.edges[0].source").value("class:UserService"))
				.andExpect(jsonPath("$.edges[0].target").value("class:UserRepository"))
				.andExpect(jsonPath("$.edges[0].type").value("DEPENDS_ON"))
				.andExpect(jsonPath("$.edges[0].length()").value(3))
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void anEmptyGraphIsEmptyListsNotNull() throws Exception {
		given(graphService.getGraph(ANALYSIS_ID, null, null)).willReturn(new GraphResponse(List.of(), List.of()));

		mockMvc.perform(get(URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nodes").isEmpty())
				.andExpect(jsonPath("$.edges").isEmpty());
	}

	@Test
	void passesBothFiltersToTheService() throws Exception {
		given(graphService.getGraph(ANALYSIS_ID, NodeType.CLASS, RelationshipType.DEPENDS_ON))
				.willReturn(new GraphResponse(List.of(), List.of()));

		mockMvc.perform(get(URL).param("nodeType", "CLASS").param("relationshipType", "DEPENDS_ON"))
				.andExpect(status().isOk());
		org.mockito.Mockito.verify(graphService).getGraph(ANALYSIS_ID, NodeType.CLASS, RelationshipType.DEPENDS_ON);
	}

	@Test
	void passesEachFilterOnItsOwn() throws Exception {
		given(graphService.getGraph(ANALYSIS_ID, NodeType.CLASS, null)).willReturn(new GraphResponse(List.of(), List.of()));
		given(graphService.getGraph(ANALYSIS_ID, null, RelationshipType.CALLS)).willReturn(new GraphResponse(List.of(), List.of()));

		mockMvc.perform(get(URL).param("nodeType", "CLASS")).andExpect(status().isOk());
		mockMvc.perform(get(URL).param("relationshipType", "CALLS")).andExpect(status().isOk());
		org.mockito.Mockito.verify(graphService).getGraph(ANALYSIS_ID, NodeType.CLASS, null);
		org.mockito.Mockito.verify(graphService).getGraph(ANALYSIS_ID, null, RelationshipType.CALLS);
	}

	@ParameterizedTest
	@ValueSource(strings = {"class", "Class", "CLASSES", "TABLE", "' OR 1=1 //", "CLASS,FILE"})
	void unknownNodeTypeReturns400ListingTheAllowedValues(String value) throws Exception {
		mockMvc.perform(get(URL).param("nodeType", value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Invalid value for parameter 'nodeType'")))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CLASS, INTERFACE")))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(value))));
		org.mockito.Mockito.verifyNoInteractions(graphService);
	}

	@Test
	void unknownRelationshipTypeReturns400() throws Exception {
		mockMvc.perform(get(URL).param("relationshipType", "LOVES"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DEPENDS_ON")));
	}

	@Test
	void unknownAnalysisReturns404() throws Exception {
		given(graphService.getGraph(ANALYSIS_ID, null, null)).willThrow(new AnalysisNotFoundException("Analysis not found"));

		mockMvc.perform(get(URL))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
	}

	@Test
	void unfinishedAnalysisReturns409() throws Exception {
		given(graphService.getGraph(ANALYSIS_ID, null, null))
				.willThrow(new AnalysisNotCompletedException("The analysis has not completed yet (status: PARSING)"));

		mockMvc.perform(get(URL))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
	}

	// ---- premium gate --------------------------------------------------------------------------

	@Test
	void theGateSeesEveryQueryParameterAndTheLevel() throws Exception {
		given(graphService.getGraph(ANALYSIS_ID, null, null)).willReturn(new GraphResponse(List.of(), List.of()));

		mockMvc.perform(get(URL).param("level", "EXPERT").param("depth", "2").param("rootNodeId", "class:A"))
				.andExpect(status().isOk());

		verify(premiumGate).check(java.util.Set.of("level", "depth", "rootNodeId"), "EXPERT");
	}

	@Test
	void aPremiumFeatureOnTheFreePlanIs403WithTheDocumentedCodeAndNoGraphWork() throws Exception {
		org.mockito.Mockito.doThrow(new com.abhiraj.archyra.common.PremiumRequiredException("Expert graph requires Archyra Premium."))
				.when(premiumGate).check(any(), any());

		mockMvc.perform(get(URL).param("level", "EXPERT"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PREMIUM_FEATURE"))
				.andExpect(jsonPath("$.message").value("Expert graph requires Archyra Premium."));
		org.mockito.Mockito.verifyNoInteractions(graphService);
	}
}
