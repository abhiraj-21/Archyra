package com.abhiraj.archyra.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.common.AnalysisNotCompletedException;
import com.abhiraj.archyra.common.NodeNotFoundException;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;

@ExtendWith(MockitoExtension.class)
class GraphServiceTest {

	private static final UUID ID = UUID.randomUUID();

	@Mock
	private AnalysisService analysisService;
	@Mock
	private GraphQueryService graphQueryService;
	@Mock
	private CodeQueryService codeQueryService;
	@InjectMocks
	private GraphService service;

	@Test
	void queriesTheGraphOfTheValidatedAnalysisWithTheFilters() {
		GraphResponse graph = new GraphResponse(List.of(), List.of());
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(graphQueryService.getGraph(ID.toString(), NodeType.CLASS, RelationshipType.DEPENDS_ON)).thenReturn(graph);

		assertThat(service.getGraph(ID.toString(), NodeType.CLASS, RelationshipType.DEPENDS_ON)).isSameAs(graph);
	}

	@Test
	void anAnalysisThatIsNotCompletedNeverTouchesTheGraph() {
		when(analysisService.requireCompleted("x")).thenThrow(new AnalysisNotCompletedException("not yet"));

		assertThatThrownBy(() -> service.getGraph("x", null, null)).isInstanceOf(AnalysisNotCompletedException.class);
		verifyNoInteractions(graphQueryService);
	}

	@Test
	void nodeDetailsComeFromTheValidatedAnalysis() {
		NodeResponse node = new NodeResponse("class:A", NodeType.CLASS, "A", null, null, null, null, null, null, null);
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(graphQueryService.getNode(ID.toString(), "class:A")).thenReturn(java.util.Optional.of(node));

		assertThat(service.getNode(ID.toString(), "class:A")).isSameAs(node);
	}

	@Test
	void aMissingNodeIsNotFound() {
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(graphQueryService.getNode(ID.toString(), "class:A")).thenReturn(java.util.Optional.empty());

		assertThatThrownBy(() -> service.getNode(ID.toString(), "class:A"))
				.isInstanceOf(NodeNotFoundException.class).hasMessage("Node not found");
	}

	@Test
	void nodeLookupOfAnAnalysisThatIsNotCompletedNeverTouchesTheGraph() {
		when(analysisService.requireCompleted("x")).thenThrow(new AnalysisNotCompletedException("not yet"));

		assertThatThrownBy(() -> service.getNode("x", "class:A")).isInstanceOf(AnalysisNotCompletedException.class);
		verifyNoInteractions(graphQueryService);
	}

	@Test
	void filesAreQueriedForTheValidatedAnalysis() {
		FilePage page = new FilePage(List.of(), 0);
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(graphQueryService.getFiles(ID.toString(), Language.JAVA, "src", 1, 10)).thenReturn(page);

		assertThat(service.getFiles(ID.toString(), Language.JAVA, "src", 1, 10)).isSameAs(page);
	}

	@Test
	void fileDetailsComeFromTheValidatedAnalysis() {
		FileResponse file = new FileResponse("file:a.py", "a.py", "a.py", "Python", null, List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of());
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(codeQueryService.getFileDetails(ID.toString(), "file:a.py")).thenReturn(java.util.Optional.of(file));

		assertThat(service.getFileDetails(ID.toString(), "file:a.py")).isSameAs(file);
	}

	@Test
	void aMissingFileIsNotFound() {
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(codeQueryService.getFileDetails(ID.toString(), "file:a.py")).thenReturn(java.util.Optional.empty());

		assertThatThrownBy(() -> service.getFileDetails(ID.toString(), "file:a.py"))
				.isInstanceOf(NodeNotFoundException.class).hasMessage("File not found");
	}

	@Test
	void classDetailsComeFromTheValidatedAnalysis() {
		ClassResponse details = new ClassResponse("class:A", "A", "A", "CLASS", "A.java", 1, "file:A.java", List.of(),
				List.of(), List.of(), List.of());
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(codeQueryService.getClassDetails(ID.toString(), "class:A")).thenReturn(java.util.Optional.of(details));

		assertThat(service.getClassDetails(ID.toString(), "class:A")).isSameAs(details);
	}

	@Test
	void aMissingClassIsNotFound() {
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(codeQueryService.getClassDetails(ID.toString(), "class:A")).thenReturn(java.util.Optional.empty());

		assertThatThrownBy(() -> service.getClassDetails(ID.toString(), "class:A"))
				.isInstanceOf(NodeNotFoundException.class).hasMessage("Class not found");
	}

	@Test
	void fileAndClassLookupsOfAnAnalysisThatIsNotCompletedNeverTouchTheGraph() {
		when(analysisService.requireCompleted("x")).thenThrow(new AnalysisNotCompletedException("not yet"));

		assertThatThrownBy(() -> service.getFileDetails("x", "file:a")).isInstanceOf(AnalysisNotCompletedException.class);
		assertThatThrownBy(() -> service.getClassDetails("x", "class:a")).isInstanceOf(AnalysisNotCompletedException.class);
		verifyNoInteractions(codeQueryService);
	}
}
