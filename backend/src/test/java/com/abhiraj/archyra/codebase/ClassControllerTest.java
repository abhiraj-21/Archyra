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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.abhiraj.archyra.common.AnalysisNotCompletedException;
import com.abhiraj.archyra.common.AnalysisNotFoundException;
import com.abhiraj.archyra.common.GlobalExceptionHandler;
import com.abhiraj.archyra.common.NodeNotFoundException;
import com.abhiraj.archyra.graph.ClassResponse;
import com.abhiraj.archyra.graph.EntitySummary;
import com.abhiraj.archyra.graph.NodeRef;
import com.abhiraj.archyra.ir.NodeType;

@WebMvcTest(ClassController.class)
@Import(GlobalExceptionHandler.class)
class ClassControllerTest {

	private static final String ANALYSIS_ID = UUID.randomUUID().toString();
	private static final String URL = "/api/v1/analyses/" + ANALYSIS_ID + "/classes/{classId}";
	private static final String CLASS_ID = "class:com.foo.UserService";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ClassService classService;

	@Test
	void returnsTheClassWithMethodsInterfacesAndDependencies() throws Exception {
		given(classService.getClassDetails(ANALYSIS_ID, CLASS_ID)).willReturn(new ClassResponse(CLASS_ID, "UserService",
				"com.foo.UserService", "CLASS", "src/UserService.java", 7, "file:src/UserService.java",
				List.of(new NodeRef("class:com.foo.Base", NodeType.CLASS, "Base", "com.foo.Base", "src/Base.java", false)),
				List.of(new EntitySummary("method:com.foo.UserService#find(String)", NodeType.METHOD, "find", null, 9, null)),
				List.of(new NodeRef("external:interface:java.io.Serializable", NodeType.INTERFACE, "Serializable",
						"java.io.Serializable", null, true)),
				List.of(new NodeRef("interface:com.foo.UserRepository", NodeType.INTERFACE, "UserRepository",
						"com.foo.UserRepository", "src/UserRepository.java", false))));

		mockMvc.perform(get(URL, CLASS_ID))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").value(CLASS_ID))
				.andExpect(jsonPath("$.name").value("UserService"))
				.andExpect(jsonPath("$.file").value("src/UserService.java"))
				.andExpect(jsonPath("$.kind").value("CLASS"))
				.andExpect(jsonPath("$.superclasses[0].id").value("class:com.foo.Base"))
				.andExpect(jsonPath("$.methods[0].id").value("method:com.foo.UserService#find(String)"))
				.andExpect(jsonPath("$.methods[0].parentId").doesNotExist())
				.andExpect(jsonPath("$.interfaces[0].external").value(true))
				.andExpect(jsonPath("$.dependencies[0].id").value("interface:com.foo.UserRepository"))
				.andExpect(jsonPath("$.dependencies[0].external").value(false));
	}

	@Test
	void aClassWithNothingToListHasEmptyLists() throws Exception {
		given(classService.getClassDetails(ANALYSIS_ID, CLASS_ID)).willReturn(new ClassResponse(CLASS_ID, "UserService",
				"com.foo.UserService", "CLASS", "src/UserService.java", 7, "file:src/UserService.java",
				List.of(), List.of(), List.of(), List.of()));

		mockMvc.perform(get(URL, CLASS_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.superclasses").isEmpty())
				.andExpect(jsonPath("$.methods").isEmpty())
				.andExpect(jsonPath("$.interfaces").isEmpty())
				.andExpect(jsonPath("$.dependencies").isEmpty());
	}

	@Test
	void unknownClassReturns404() throws Exception {
		given(classService.getClassDetails(ANALYSIS_ID, "class:Nope")).willThrow(new NodeNotFoundException("Class not found"));

		mockMvc.perform(get(URL, "class:Nope"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Class not found"));
	}

	@Test
	void unknownAnalysisReturns404() throws Exception {
		given(classService.getClassDetails(ANALYSIS_ID, CLASS_ID)).willThrow(new AnalysisNotFoundException("Analysis not found"));

		mockMvc.perform(get(URL, CLASS_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
	}

	@Test
	void unfinishedAnalysisReturns409() throws Exception {
		given(classService.getClassDetails(ANALYSIS_ID, CLASS_ID)).willThrow(new AnalysisNotCompletedException("not yet"));

		mockMvc.perform(get(URL, CLASS_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
	}
}
