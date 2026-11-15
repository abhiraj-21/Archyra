package com.abhiraj.archyra.parsing.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.parsing.ModuleIndex;
import com.abhiraj.archyra.parsing.ModuleRef;
import com.abhiraj.archyra.parsing.ModuleResolver;

/**
 * JavaScript, TypeScript and single-file component imports: relative paths ({@code ./a},
 * {@code ../b/c}) resolved against the importing file, trying the usual extensions and
 * {@code index} files, plus path aliases ({@code @/x}, {@code ~/x}) and bare paths under common source
 * roots. Anything else is an npm package.
 */
@Component
public class JavaScriptModuleResolver implements ModuleResolver {

	private static final List<String> EXTENSIONS = List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".vue",
			".svelte");
	private static final List<String> ROOTS = List.of("", "src/", "app/", "lib/", "client/", "frontend/src/");
	private static final Set<Language> LANGUAGES = Set.of(Language.JAVASCRIPT, Language.TYPESCRIPT, Language.VUE,
			Language.SVELTE);

	@Override
	public boolean supports(Language language) {
		return LANGUAGES.contains(language);
	}

	@Override
	public Optional<ModuleRef> resolve(FileIR from, String module, int relativeLevel, ModuleIndex index) {
		if (module.isEmpty()) {
			return Optional.empty();
		}
		List<String> candidates = new ArrayList<>();
		if (module.startsWith(".")) {
			candidates.add(normalize(directoryOf(from.path()) + "/" + module));
		}
		else {
			String bare = module;
			for (String alias : List.of("@/", "~/", "#/")) {
				if (bare.startsWith(alias)) {
					bare = bare.substring(alias.length());
				}
			}
			for (String root : ROOTS) {
				candidates.add(root + bare);
			}
		}
		for (String candidate : candidates) {
			Optional<ModuleRef> found = find(candidate, index);
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	private static Optional<ModuleRef> find(String base, ModuleIndex index) {
		List<String> paths = new ArrayList<>();
		paths.add(base);
		for (String extension : EXTENSIONS) {
			paths.add(base + extension);
		}
		for (String extension : EXTENSIONS) {
			paths.add(base + "/index" + extension);
		}
		for (String path : paths) {
			Optional<FileIR> file = index.fileAt(path);
			if (file.isPresent() && LANGUAGES.contains(file.get().language())) {
				return Optional.of(new ModuleRef(file.get().moduleName(), List.of(path)));
			}
		}
		return Optional.empty();
	}

	/** A relative specifier that leads nowhere is a broken or generated file, not an npm package. */
	@Override
	public boolean isRelative(String module, int relativeLevel) {
		return module.startsWith(".");
	}

	private static String directoryOf(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	/** Resolves {@code .} and {@code ..} segments; a path that climbs above the root stays unresolved. */
	static String normalize(String path) {
		List<String> segments = new ArrayList<>();
		for (String segment : path.split("/")) {
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..")) {
				if (segments.isEmpty()) {
					return "\u0000";
				}
				segments.removeLast();
			}
			else {
				segments.add(segment);
			}
		}
		return String.join("/", segments);
	}
}
