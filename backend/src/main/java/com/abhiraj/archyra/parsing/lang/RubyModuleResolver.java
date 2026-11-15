package com.abhiraj.archyra.parsing.lang;

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
 * Ruby {@code require} and {@code require_relative}: a file path without extension. Relative requires are
 * resolved against the requiring file; others by matching the end of a file path ({@code models/user} finds
 * {@code app/models/user.rb}); anything else is a gem or the standard library.
 */
@Component
public class RubyModuleResolver implements ModuleResolver {

	@Override
	public boolean supports(Language language) {
		return language == Language.RUBY;
	}

	@Override
	public Optional<ModuleRef> resolve(FileIR from, String module, int relativeLevel, ModuleIndex index) {
		String spec = module.endsWith(".rb") ? module.substring(0, module.length() - 3) : module;
		if (relativeLevel > 0) {
			String directory = from.path().contains("/") ? from.path().substring(0, from.path().lastIndexOf('/')) : "";
			String normalized = JavaScriptModuleResolver.normalize(directory + "/" + spec);
			return fileAt(normalized + ".rb", index);
		}
		if (spec.startsWith("/")) {
			return Optional.empty();
		}
		return index.filePaths().stream()
				.filter(path -> path.endsWith(".rb") && (path.equals(spec + ".rb") || path.endsWith("/" + spec + ".rb")))
				.min(Comparator.comparingInt(String::length))
				.flatMap(path -> fileAt(path, index));
	}

	private static Optional<ModuleRef> fileAt(String path, ModuleIndex index) {
		return index.fileAt(path).filter(file -> file.language() == Language.RUBY)
				.map(file -> new ModuleRef(file.moduleName(), List.of(path)));
	}

	@Override
	public boolean isRelative(String module, int relativeLevel) {
		return relativeLevel > 0;
	}
}
