package com.abhiraj.archyra.graph;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.abhiraj.archyra.common.AnalysisNotCompletedException;
import com.abhiraj.archyra.common.AnalysisNotFoundException;
import com.abhiraj.archyra.common.GlobalExceptionHandler;
import com.abhiraj.archyra.common.NodeNotFoundException;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;

@WebMvcTest(NodeController.class)
@Import(GlobalExceptionHandler.class)
class NodeControllerTest {

	private static final String ANALYSIS_ID = UUID.randomUUID().toString();
	private static final String CLASS_ID = "class:com.foo.UserService";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private GraphService graphService;

	private static NodeResponse userService() {
		Map<RelationshipType, Long> outgoing = new EnumMap<>(RelationshipType.class);
		outgoing.put(RelationshipType.DEPENDS_ON, 2L);
		return new NodeResponse(CLASS_ID, NodeType.CLASS, "UserService", "com.foo.UserService",
				"src/UserService.java", 12, "CLASS", null, "file:src/UserService.java",
				new NodeResponse.Relationships(outgoing, Map.of()));
	}

	@Test
	void returnsTheNodeDetails() throws Exception {
		given(graphService.getNode(ANALYSIS_ID, CLASS_ID)).willReturn(userService());

		mockMvc.perform(get("/api/v1/analyses/{id}/nodes/{nodeId}", ANALYSIS_ID, CLASS_ID))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").value(CLASS_ID))
				.andExpect(jsonPath("$.type").value("CLASS"))
				.andExpect(jsonPath("$.name").value("UserService"))
				.andExpect(jsonPath("$.qualifiedName").value("com.foo.UserService"))
				.andExpect(jsonPath("$.file").value("src/UserService.java"))
				.andExpect(jsonPath("$.line").value(12))
				.andExpect(jsonPath("$.kind").value("CLASS"))
				.andExpect(jsonPath("$.language").doesNotExist())
				.andExpect(jsonPath("$.parentId").value("file:src/UserService.java"))
				.andExpect(jsonPath("$.relationships.outgoing.DEPENDS_ON").value(2))
				.andExpect(jsonPath("$.relationships.incoming").isEmpty());
	}

	@Test
	void anEncodedSlashAndSpecialCharactersArriveDecodedInTheNodeId() throws Exception {
		String id = "method:com.foo.UserService#find(String, int)";
		given(graphService.getNode(ANALYSIS_ID, "file:src/main/App.java"))
				.willReturn(new NodeResponse("file:src/main/App.java", NodeType.FILE, "App.java", "src/main/App.java",
						"src/main/App.java", null, null, "Java", "module:main", null));
		given(graphService.getNode(ANALYSIS_ID, id))
				.willReturn(new NodeResponse(id, NodeType.METHOD, "find", null, null, null, null, null, null, null));

		mockMvc.perform(get(URI.create("/api/v1/analyses/" + ANALYSIS_ID + "/nodes/file%3Asrc%2Fmain%2FApp.java")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("file:src/main/App.java"))
				.andExpect(jsonPath("$.language").value("Java"))
				.andExpect(jsonPath("$.kind").doesNotExist());
		mockMvc.perform(get(URI.create("/api/v1/analyses/" + ANALYSIS_ID
						+ "/nodes/method%3Acom.foo.UserService%23find(String,%20int)")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id));
	}

	@Test
	void unknownNodeReturns404() throws Exception {
		given(graphService.getNode(ANALYSIS_ID, "class:nope")).willThrow(new NodeNotFoundException("Node not found"));

		mockMvc.perform(get("/api/v1/analyses/{id}/nodes/{nodeId}", ANALYSIS_ID, "class:nope"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Node not found"));
	}

	@Test
	void unknownAnalysisReturns404() throws Exception {
		given(graphService.getNode(ANALYSIS_ID, CLASS_ID)).willThrow(new AnalysisNotFoundException("Analysis not found"));

		mockMvc.perform(get("/api/v1/analyses/{id}/nodes/{nodeId}", ANALYSIS_ID, CLASS_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
	}

	@Test
	void unfinishedAnalysisReturns409() throws Exception {
		given(graphService.getNode(ANALYSIS_ID, CLASS_ID))
				.willThrow(new AnalysisNotCompletedException("The analysis has not completed yet (status: PARSING)"));

		mockMvc.perform(get("/api/v1/analyses/{id}/nodes/{nodeId}", ANALYSIS_ID, CLASS_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
	}
}
