package com.abhiraj.archyra.parsing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.RepositoryIR;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Parsing stage: runs the matching extractor over every source file. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParsingService {

	private final List<LanguageExtractor> extractors;

	/**
	 * Parses all {@code files}. A file that fails to parse is logged and recorded, never fatal. The
	 * stage fails only when the parser itself is unavailable or nothing at all could be parsed.
	 */
	public ParseResult parse(String repositoryName, String repositoryUrl, List<SourceFile> files) {
		List<FileIR> parsed = new ArrayList<>();
		List<ParseFailure> failures = new ArrayList<>();
		int skipped = 0;

		for (SourceFile file : files) {
			Optional<LanguageExtractor> extractor = extractors.stream()
					.filter(candidate -> candidate.supports(file.language()))
					.findFirst();
			if (extractor.isEmpty()) {
				skipped++;
				continue;
			}
			try {
				parsed.add(extractor.get().extract(file));
			}
			catch (AnalysisFailureException ex) {
				throw ex; // the parser itself is unavailable: no point continuing
			}
			catch (Exception | LinkageError ex) {
				log.warn("Could not parse {}: {}", file.relativePath(), ex.toString());
				failures.add(new ParseFailure(file.relativePath(), ex.getClass().getSimpleName()));
			}
		}

		if (parsed.isEmpty()) {
			throw new AnalysisFailureException("No source files could be analysed. Supported languages: "
					+ supportedLanguages());
		}
		log.info("Parsed {} files ({} failed, {} in unsupported languages)", parsed.size(), failures.size(), skipped);
		return new ParseResult(new RepositoryIR(repositoryName, repositoryUrl, parsed), List.copyOf(failures), skipped);
	}

	/** Whether code in {@code language} is analysed, that is an extractor exists for it. */
	public boolean canParse(Language language) {
		return extractors.stream().anyMatch(extractor -> extractor.supports(language));
	}

	private String supportedLanguages() {
		return Arrays.stream(Language.values())
				.filter(language -> extractors.stream().anyMatch(extractor -> extractor.supports(language)))
				.map(Language::displayName)
				.collect(Collectors.joining(", "));
	}
}
