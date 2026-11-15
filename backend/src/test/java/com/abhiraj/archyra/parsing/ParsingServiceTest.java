package com.abhiraj.archyra.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;

class ParsingServiceTest {

	private static SourceFile java(String path) {
		return new SourceFile(path, Path.of(path), Language.JAVA);
	}

	private static LanguageExtractor extractor(java.util.function.Function<SourceFile, FileIR> behaviour) {
		return new LanguageExtractor() {
			@Override
			public boolean supports(Language language) {
				return language == Language.JAVA;
			}

			@Override
			public FileIR extract(SourceFile file) {
				return behaviour.apply(file);
			}
		};
	}

	private static FileIR emptyFile(SourceFile file) {
		return new FileIR(file.relativePath(), Language.JAVA, "", List.of(), List.of(), List.of());
	}

	@Test
	void parsesEveryFileAndBuildsTheRepositoryIr() {
		ParsingService service = new ParsingService(List.of(extractor(ParsingServiceTest::emptyFile)));

		ParseResult result = service.parse("repo", "https://github.com/o/repo", List.of(java("A.java"), java("B.java")));

		assertThat(result.repository().name()).isEqualTo("repo");
		assertThat(result.repository().url()).isEqualTo("https://github.com/o/repo");
		assertThat(result.repository().files()).extracting(FileIR::path).containsExactly("A.java", "B.java");
		assertThat(result.failures()).isEmpty();
	}

	@Test
	void aFileThatFailsToParseIsRecordedAndTheRestContinues() {
		ParsingService service = new ParsingService(List.of(extractor(file -> {
			if (file.relativePath().equals("Bad.java")) {
				throw new IllegalStateException("boom");
			}
			return emptyFile(file);
		})));

		ParseResult result = service.parse("repo", "url", List.of(java("A.java"), java("Bad.java"), java("C.java")));

		assertThat(result.repository().files()).extracting(FileIR::path).containsExactly("A.java", "C.java");
		assertThat(result.failures()).containsExactly(new ParseFailure("Bad.java", "IllegalStateException"));
	}

	@Test
	void filesInLanguagesWithoutAnExtractorAreSkipped() {
		ParsingService service = new ParsingService(List.of(extractor(ParsingServiceTest::emptyFile)));

		ParseResult result = service.parse("repo", "url", List.of(java("A.java"),
				new SourceFile("x.py", Path.of("x.py"), Language.PYTHON)));

		assertThat(result.skippedFiles()).isEqualTo(1);
		assertThat(result.repository().files()).hasSize(1);
	}

	@Test
	void failsWhenNothingCouldBeParsedAndNamesTheSupportedLanguages() {
		ParsingService service = new ParsingService(List.of(extractor(ParsingServiceTest::emptyFile)));

		assertThatThrownBy(() -> service.parse("repo", "url",
				List.of(new SourceFile("x.py", Path.of("x.py"), Language.PYTHON))))
				.isInstanceOf(AnalysisFailureException.class)
				.hasMessageContaining("Supported languages: Java");
		assertThatThrownBy(() -> service.parse("repo", "url", List.of()))
				.isInstanceOf(AnalysisFailureException.class);
	}

	@Test
	void failsWhenEveryFileFailsToParse() {
		ParsingService service = new ParsingService(List.of(extractor(file -> {
			throw new IllegalStateException("boom");
		})));

		assertThatThrownBy(() -> service.parse("repo", "url", List.of(java("A.java"))))
				.isInstanceOf(AnalysisFailureException.class);
	}

	@Test
	void anUnavailableParserFailsTheWholeStageInsteadOfEveryFile() {
		int[] calls = {0};
		ParsingService service = new ParsingService(List.of(extractor(file -> {
			calls[0]++;
			throw new AnalysisFailureException("The source code parser is not available on this server");
		})));

		assertThatThrownBy(() -> service.parse("repo", "url", List.of(java("A.java"), java("B.java"))))
				.isInstanceOf(AnalysisFailureException.class)
				.hasMessageContaining("parser is not available");
		assertThat(calls[0]).isEqualTo(1);
	}
}
