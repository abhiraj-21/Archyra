package com.abhiraj.archyra.codebase;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.graph.FilePage;
import com.abhiraj.archyra.graph.FileResponse;
import com.abhiraj.archyra.graph.GraphService;
import com.abhiraj.archyra.ir.Language;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FileService {

	static final int DEFAULT_PAGE_SIZE = 50;
	static final int MAX_PAGE_SIZE = 200;
	static final int MAX_PATH_LENGTH = 500;

	private final GraphService graphService;

	/**
	 * Lists the files of a completed analysis, optionally narrowed by language and path.
	 *
	 * @param language language display name ({@code Java}, {@code C#}) or constant ({@code CSHARP}), any case;
	 *                 null or blank for all
	 * @param path     repository path such as {@code src/main}; matches that file or everything below it;
	 *                 null or blank for all
	 * @throws InvalidRequestException                                  for an unknown language, a path that is
	 *                                                                  too long, or a page or size out of range
	 * @throws com.abhiraj.archyra.common.AnalysisNotFoundException     if the analysis id is malformed or unknown
	 * @throws com.abhiraj.archyra.common.AnalysisNotCompletedException if the analysis is not {@code COMPLETED}
	 */
	public FilePageResponse getFiles(String analysisId, String language, String path, int page, int size) {
		if (page < 0) {
			throw new InvalidRequestException("page must not be negative");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		Language languageFilter = parseLanguage(language);
		String pathFilter = normalisePath(path);

		FilePage result = graphService.getFiles(analysisId, languageFilter, pathFilter, page, size);
		return new FilePageResponse(
				result.files().stream()
						.map(file -> new FileSummary(file.id(), file.name(), file.path(), file.language().displayName()))
						.toList(),
				page, size, result.total(), (result.total() + size - 1) / size);
	}

	private static Language parseLanguage(String language) {
		if (language == null || language.isBlank()) {
			return null;
		}
		String wanted = language.strip();
		return Arrays.stream(Language.values())
				.filter(candidate -> candidate.displayName().equalsIgnoreCase(wanted) || candidate.name().equalsIgnoreCase(wanted))
				.findFirst()
				.orElseThrow(() -> new InvalidRequestException("Unknown language. Allowed values: "
						+ Arrays.stream(Language.values()).map(Language::displayName).collect(Collectors.joining(", "))));
	}

	/** Drops leading {@code ./} and slashes and trailing slashes, so {@code /src/main/} means {@code src/main}. */
	private static String normalisePath(String path) {
		if (path == null) {
			return null;
		}
		if (path.length() > MAX_PATH_LENGTH) {
			throw new InvalidRequestException("path must not be longer than " + MAX_PATH_LENGTH + " characters");
		}
		String normalised = path.strip();
		while (normalised.startsWith("./") || normalised.startsWith("/")) {
			normalised = normalised.startsWith("./") ? normalised.substring(2) : normalised.substring(1);
		}
		while (normalised.endsWith("/")) {
			normalised = normalised.substring(0, normalised.length() - 1);
		}
		return normalised.isEmpty() ? null : normalised;
	}

	/**
	 * One file with the entities extracted from it.
	 *
	 * @param fileId the node id of the file as listed by {@code /files}, e.g. {@code file:src/main/App.java}
	 * @throws com.abhiraj.archyra.common.NodeNotFoundException         if the analysis has no such file
	 * @throws com.abhiraj.archyra.common.AnalysisNotFoundException     if the analysis id is malformed or unknown
	 * @throws com.abhiraj.archyra.common.AnalysisNotCompletedException if the analysis is not {@code COMPLETED}
	 */
	public FileResponse getFile(String analysisId, String fileId) {
		return graphService.getFileDetails(analysisId, fileId);
	}
}
