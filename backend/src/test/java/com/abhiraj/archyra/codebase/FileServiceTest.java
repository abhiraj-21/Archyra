package com.abhiraj.archyra.codebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.graph.FileInfo;
import com.abhiraj.archyra.graph.FilePage;
import com.abhiraj.archyra.graph.GraphService;
import com.abhiraj.archyra.ir.Language;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

	private static final String ID = "a-analysis-id";

	@Mock
	private GraphService graphService;
	@InjectMocks
	private FileService service;

	@Test
	void mapsTheFilesAndComputesThePaging() {
		when(graphService.getFiles(ID, null, null, 1, 2)).thenReturn(new FilePage(
				List.of(new FileInfo("file:a.cs", "a.cs", "a.cs", Language.CSHARP),
						new FileInfo("file:b.go", "b.go", "b.go", Language.GO)), 5));

		FilePageResponse response = service.getFiles(ID, null, null, 1, 2);

		assertThat(response.items()).containsExactly(
				new FileSummary("file:a.cs", "a.cs", "a.cs", "C#"), new FileSummary("file:b.go", "b.go", "b.go", "Go"));
		assertThat(response.page()).isEqualTo(1);
		assertThat(response.size()).isEqualTo(2);
		assertThat(response.totalElements()).isEqualTo(5);
		assertThat(response.totalPages()).isEqualTo(3);
	}

	@Test
	void noMatchesIsAnEmptyPageWithZeroPages() {
		when(graphService.getFiles(ID, Language.JAVA, null, 0, 50)).thenReturn(new FilePage(List.of(), 0));

		FilePageResponse response = service.getFiles(ID, "Java", null, 0, 50);

		assertThat(response.items()).isEmpty();
		assertThat(response.totalPages()).isZero();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			Java|JAVA
			java|JAVA
			JAVA|JAVA
			  Java  |JAVA
			C#|CSHARP
			CSHARP|CSHARP
			c#|CSHARP
			typescript|TYPESCRIPT
			Jupyter Notebook|JUPYTER
			HCL (Terraform)|HCL
			""")
	void languageMatchesTheDisplayNameOrTheConstantInAnyCase(String input, Language expected) {
		when(graphService.getFiles(any(), any(), any(), anyInt(), anyInt())).thenReturn(new FilePage(List.of(), 0));

		service.getFiles(ID, input, null, 0, 50);

		verify(graphService).getFiles(ID, expected, null, 0, 50);
	}

	@ParameterizedTest
	@ValueSource(strings = {"Klingon", "Jav", "Java;", "' OR 1=1", "J ava"})
	void unknownLanguageIsRejectedWithTheAllowedValues(String language) {
		assertThatThrownBy(() -> service.getFiles(ID, language, null, 0, 50))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageStartingWith("Unknown language. Allowed values: Java, Kotlin");
		verifyNoInteractions(graphService);
	}

	@Test
	void theRejectedLanguageIsNotEchoed() {
		assertThatThrownBy(() -> service.getFiles(ID, "Klingon", null, 0, 50)).hasMessageNotContaining("Klingon");
	}

	@Test
	void blankLanguageAndPathMeanNoFilter() {
		when(graphService.getFiles(ID, null, null, 0, 50)).thenReturn(new FilePage(List.of(), 0));

		service.getFiles(ID, "  ", "   ", 0, 50);

		verify(graphService).getFiles(ID, null, null, 0, 50);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			src/main|src/main
			/src/main|src/main
			./src/main/|src/main
			src/main///|src/main
			.//src/main|src/main
			  src/main  |src/main
			src/Main.java|src/Main.java
			""")
	void pathIsNormalised(String input, String expected) {
		when(graphService.getFiles(any(), any(), anyString(), anyInt(), anyInt())).thenReturn(new FilePage(List.of(), 0));

		service.getFiles(ID, null, input, 0, 50);

		verify(graphService).getFiles(ID, null, expected, 0, 50);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/", "./", "//"})
	void aPathThatIsOnlySlashesMeansNoFilter(String path) {
		when(graphService.getFiles(ID, null, null, 0, 50)).thenReturn(new FilePage(List.of(), 0));

		service.getFiles(ID, null, path, 0, 50);

		verify(graphService).getFiles(ID, null, null, 0, 50);
	}

	@Test
	void aTooLongPathIsRejected() {
		assertThatThrownBy(() -> service.getFiles(ID, null, "a".repeat(FileService.MAX_PATH_LENGTH + 1), 0, 50))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(graphService);
	}

	@ParameterizedTest
	@CsvSource({"-1,50", "0,0", "0,-5", "0,201", "0,100000"})
	void pageAndSizeAreBounded(int page, int size) {
		assertThatThrownBy(() -> service.getFiles(ID, null, null, page, size)).isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(graphService);
	}

	@Test
	void theLargestAllowedSizeIsAccepted() {
		when(graphService.getFiles(ID, null, null, 0, FileService.MAX_PAGE_SIZE)).thenReturn(new FilePage(List.of(), 0));

		assertThat(service.getFiles(ID, null, null, 0, FileService.MAX_PAGE_SIZE).size()).isEqualTo(200);
	}
}
