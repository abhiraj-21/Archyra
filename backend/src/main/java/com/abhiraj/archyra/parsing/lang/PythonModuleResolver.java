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
 * Python imports: dotted module names, absolute or relative ({@code from ..pkg import x}). Absolute
 * names are matched against the modules of the repository by suffix, because the import root is usually a
 * source directory such as {@code src/} that is not part of the name.
 */
@Component
public class PythonModuleResolver implements ModuleResolver {

	@Override
	public boolean supports(Language language) {
		return language == Language.PYTHON || language == Language.JUPYTER;
	}

	@Override
	public Optional<ModuleRef> resolve(FileIR from, String module, int relativeLevel, ModuleIndex index) {
		if (relativeLevel > 0) {
			return resolveRelative(from, module, relativeLevel, index);
		}
		if (module.isEmpty()) {
			return Optional.empty();
		}
		if (isPythonModule(module, index)) {
			return Optional.of(ModuleRef.ofModule(module));
		}
		List<String> candidates = new ArrayList<>();
		for (String name : index.moduleNames()) {
			if (name.endsWith("." + module) && isPythonModule(name, index)) {
				candidates.add(name);
			}
		}
		return candidates.stream().min(Comparator.comparingInt(String::length)).map(ModuleRef::ofModule);
	}

	private Optional<ModuleRef> resolveRelative(FileIR from, String module, int level, ModuleIndex index) {
		List<String> base = new ArrayList<>();
		if (!from.packageName().isEmpty()) {
			base.addAll(List.of(from.packageName().split("\\.")));
		}
		int up = level - 1;
		if (up > base.size()) {
			return Optional.empty();
		}
		base = base.subList(0, base.size() - up);
		String target = String.join(".", base);
		if (!module.isEmpty()) {
			target = target.isEmpty() ? module : target + "." + module;
		}
		return isPythonModule(target, index) ? Optional.of(ModuleRef.ofModule(target)) : Optional.empty();
	}

	private static boolean isPythonModule(String name, ModuleIndex index) {
		return index.containsModule(name) && index.files(name).stream()
				.anyMatch(file -> file.language() == Language.PYTHON || file.language() == Language.JUPYTER);
	}
}
