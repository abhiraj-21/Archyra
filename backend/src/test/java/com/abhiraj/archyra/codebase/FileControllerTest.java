package com.abhiraj.archyra.codebase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
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
import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.common.NodeNotFoundException;
import com.abhiraj.archyra.graph.EntitySummary;
import com.abhiraj.archyra.graph.FileResponse;
import com.abhiraj.archyra.graph.NodeRef;
import com.abhiraj.archyra.ir.NodeType;

@WebMvcTest(FileController.class)
@Import({GlobalExceptionHandler.class, SecuritySliceConfig.class})
@WithMockUser
class FileControllerTest {

	private static final String ANALYSIS_ID = UUID.randomUUID().toString();
	private static final String URL = "/api/v1/analyses/" + ANALYSIS_ID + "/files";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FileService fileService;

	@Test
	void returnsAPageOfFiles() throws Exception {
		given(fileService.getFiles(ANALYSIS_ID, null, null, null, 0, 50)).willReturn(new FilePageResponse(
				List.of(new FileSummary("file:src/A.java", "A.java", "src/A.java", "Java", false)), 0, 50, 1, 1));

		mockMvc.perform(get(URL))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.items[0].id").value("file:src/A.java"))
				.andExpect(jsonPath("$.items[0].name").value("A.java"))
				.andExpect(jsonPath("$.items[0].path").value("src/A.java"))
				.andExpect(jsonPath("$.items[0].language").value("Java"))
				.andExpect(jsonPath("$.items[0].test").value(false))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(50))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1));
	}

	@Test
	void passesFiltersAndPagingToTheService() throws Exception {
		given(fileService.getFiles(any(), any(), any(), any(), anyInt(), anyInt()))
				.willReturn(new FilePageResponse(List.of(), 2, 10, 0, 0));

		mockMvc.perform(get(URL).param("language", "Java").param("path", "src/main").param("page", "2").param("size", "10"))
				.andExpect(status().isOk());

		verify(fileService).getFiles(ANALYSIS_ID, "Java", "src/main", null, 2, 10);
	}

	@Test
	void passesTheTestFilterToTheService() throws Exception {
		given(fileService.getFiles(any(), any(), any(), any(), anyInt(), anyInt()))
				.willReturn(new FilePageResponse(List.of(new FileSummary("file:src/test/ATest.java", "ATest.java",
						"src/test/ATest.java", "Java", true)), 0, 50, 1, 1));

		mockMvc.perform(get(URL).param("test", "true")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].test").value(true));

		verify(fileService).getFiles(ANALYSIS_ID, null, null, "true", 0, 50);
	}

	@Test
	void anInvalidTestValueReturns400() throws Exception {
		given(fileService.getFiles(ANALYSIS_ID, null, null, "maybe", 0, 50))
				.willThrow(new InvalidRequestException("Invalid value for test. Allowed values: true, false"));

		mockMvc.perform(get(URL).param("test", "maybe")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value("Invalid value for test. Allowed values: true, false"));
	}

	@Test
	void invalidParametersReturn400() throws Exception {
		given(fileService.getFiles(ANALYSIS_ID, "Klingon", null, null, 0, 50))
				.willThrow(new InvalidRequestException("Unknown language. Allowed values: Java"));

		mockMvc.perform(get(URL).param("language", "Klingon"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value("Unknown language. Allowed values: Java"));
		mockMvc.perform(get(URL).param("page", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void unknownAnalysisReturns404() throws Exception {
		given(fileService.getFiles(ANALYSIS_ID, null, null, null, 0, 50)).willThrow(new AnalysisNotFoundException("Analysis not found"));

		mockMvc.perform(get(URL)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
	}

	@Test
	void unfinishedAnalysisReturns409() throws Exception {
		given(fileService.getFiles(ANALYSIS_ID, null, null, null, 0, 50))
				.willThrow(new AnalysisNotCompletedException("not yet"));

		mockMvc.perform(get(URL)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
	}

	// ---- GET /files/{fileId} -------------------------------------------------------------------

	@Test
	void returnsTheFileWithItsEntities() throws Exception {
		String fileId = "file:src/main/App.java";
		given(fileService.getFile(ANALYSIS_ID, fileId)).willReturn(new FileResponse(fileId, "App.java", "src/main/App.java",
				"Java", true, new NodeRef("module:main", NodeType.MODULE, "main", null, null, false),
				List.of(new EntitySummary("class:main.App", NodeType.CLASS, "App", "CLASS", 3, fileId)),
				List.of(), List.of(new EntitySummary("method:main.App#run()", NodeType.METHOD, "run", null, 5, "class:main.App")),
				List.of(),
				List.of(new NodeRef("external:class:java.util.List", NodeType.CLASS, "List", "java.util.List", null, true)),
				List.of()));

		mockMvc.perform(get(URI.create("/api/v1/analyses/" + ANALYSIS_ID + "/files/file%3Asrc%2Fmain%2FApp.java")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(fileId))
				.andExpect(jsonPath("$.path").value("src/main/App.java"))
				.andExpect(jsonPath("$.language").value("Java"))
				.andExpect(jsonPath("$.module.id").value("module:main"))
				.andExpect(jsonPath("$.classes[0].id").value("class:main.App"))
				.andExpect(jsonPath("$.classes[0].parentId").value(fileId))
				.andExpect(jsonPath("$.interfaces").isEmpty())
				.andExpect(jsonPath("$.methods[0].name").value("run"))
				.andExpect(jsonPath("$.functions").isEmpty())
				.andExpect(jsonPath("$.imports[0].qualifiedName").value("java.util.List"))
				.andExpect(jsonPath("$.imports[0].external").value(true))
				.andExpect(jsonPath("$.imports[0].file").doesNotExist())
				.andExpect(jsonPath("$.references").isEmpty());
	}

	@Test
	void unknownFileReturns404() throws Exception {
		given(fileService.getFile(ANALYSIS_ID, "file:nope.java")).willThrow(new NodeNotFoundException("File not found"));

		mockMvc.perform(get(URL + "/{fileId}", "file:nope.java"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("File not found"));
	}

	@Test
	void fileOfAnUnfinishedAnalysisReturns409() throws Exception {
		given(fileService.getFile(ANALYSIS_ID, "file:a.java")).willThrow(new AnalysisNotCompletedException("not yet"));

		mockMvc.perform(get(URL + "/{fileId}", "file:a.java")).andExpect(status().isConflict());
	}
}
