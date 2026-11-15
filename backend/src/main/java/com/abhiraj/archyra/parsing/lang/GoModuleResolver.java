package com.abhiraj.archyra.parsing.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.parsing.ModuleIndex;
import com.abhiraj.archyra.parsing.ModuleRef;
import com.abhiraj.archyra.parsing.ModuleResolver;

/**
 * Go imports name a package by import path, such as {@code github.com/acme/app/internal/store}. The path
 * starts with the module path from {@code go.mod}, which is not part of the repository layout, so a package is
 * matched by the longest of its directory's suffixes that the import path ends with. In a monorepo the Go
 * module often lives in a subdirectory ({@code services/api/internal/store}); its suffix
 * {@code internal/store} still matches. A suffix must have two or more segments, unless it is the whole
 * directory, so that a third-party package named {@code util} is not mistaken for a local one.
 */
@Component
public class GoModuleResolver implements ModuleResolver {

	@Override
	public boolean supports(Language language) {
		return language == Language.GO;
	}

	@Override
	public Optional<ModuleRef> resolve(FileIR from, String module, int relativeLevel, ModuleIndex index) {
		String best = null;
		int bestLength = 0;
		for (String name : index.moduleNames()) {
			Optional<String> directory = directoryOf(name, index);
			if (directory.isEmpty() || directory.get().isEmpty()) {
				continue;
			}
			for (String suffix : candidates(directory.get())) {
				if ((module.equals(suffix) || module.endsWith("/" + suffix)) && suffix.length() > bestLength) {
					best = name;
					bestLength = suffix.length();
				}
			}
		}
		return Optional.ofNullable(best).map(ModuleRef::ofModule);
	}

	/** The whole directory, and every proper suffix of two or more segments. */
	private static List<String> candidates(String directory) {
		List<String> result = new ArrayList<>();
		result.add(directory);
		String[] segments = directory.split("/");
		for (int from = 1; from < segments.length - 1; from++) {
			result.add(String.join("/", List.of(segments).subList(from, segments.length)));
		}
		return result;
	}

	private static Optional<String> directoryOf(String moduleName, ModuleIndex index) {
		return index.files(moduleName).stream().filter(file -> file.language() == Language.GO).findFirst()
				.map(FileIR::packageName);
	}
}
