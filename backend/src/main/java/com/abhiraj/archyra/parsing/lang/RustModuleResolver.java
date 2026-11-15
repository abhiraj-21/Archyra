package com.abhiraj.archyra.parsing.lang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.parsing.ModuleIndex;
import com.abhiraj.archyra.parsing.ModuleRef;
import com.abhiraj.archyra.parsing.ModuleResolver;

/**
 * Rust paths, as {@link RustExtractor} normalises them: {@code crate.a.b} (from the crate root),
 * {@code self.a} (a child of the current module), {@code super} (relative level), and bare names
 * (a child module declared in the file, a workspace crate, or an external crate).
 */
@Component
public class RustModuleResolver implements ModuleResolver {

	@Override
	public boolean supports(Language language) {
		return language == Language.RUST;
	}

	@Override
	public Optional<ModuleRef> resolve(FileIR from, String module, int relativeLevel, ModuleIndex index) {
		if (relativeLevel > 0) {
			List<String> segments = new ArrayList<>(segments(from.moduleName()));
			if (relativeLevel > segments.size()) {
				return Optional.empty();
			}
			segments = segments.subList(0, segments.size() - relativeLevel);
			return rustModule(join(segments, module), index);
		}
		if (module.equals("crate") || module.startsWith("crate.")) {
			String rest = module.equals("crate") ? "" : module.substring("crate.".length());
			return rest.isEmpty() ? Optional.empty() : suffixMatch(rest, index);
		}
		if (module.equals("self") || module.startsWith("self.")) {
			String rest = module.equals("self") ? "" : module.substring("self.".length());
			return rustModule(join(segments(from.moduleName()), rest), index);
		}
		// a child module declared in this file, else a crate of the workspace
		Optional<ModuleRef> child = rustModule(join(segments(from.moduleName()), module), index);
		return child.isPresent() ? child : suffixMatch(module, index);
	}

	private static Optional<ModuleRef> rustModule(String name, ModuleIndex index) {
		return isRust(name, index) ? Optional.of(ModuleRef.ofModule(name)) : Optional.empty();
	}

	private static Optional<ModuleRef> suffixMatch(String path, ModuleIndex index) {
		if (isRust(path, index)) {
			return Optional.of(ModuleRef.ofModule(path));
		}
		return index.moduleNames().stream()
				.filter(name -> name.endsWith("." + path) && isRust(name, index))
				.min(Comparator.comparingInt(String::length))
				.map(ModuleRef::ofModule);
	}

	private static boolean isRust(String name, ModuleIndex index) {
		return index.containsModule(name) && index.files(name).stream().anyMatch(file -> file.language() == Language.RUST);
	}

	private static List<String> segments(String moduleName) {
		return moduleName.isEmpty() ? List.of() : List.of(moduleName.split("\\."));
	}

	private static String join(List<String> base, String rest) {
		String prefix = String.join(".", base);
		return rest.isEmpty() ? prefix : prefix.isEmpty() ? rest : prefix + "." + rest;
	}
}
