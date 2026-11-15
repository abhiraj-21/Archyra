package com.abhiraj.archyra.parsing.lang;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.parsing.ModuleIndex;
import com.abhiraj.archyra.parsing.ModuleRef;
import com.abhiraj.archyra.parsing.ModuleResolver;

/**
 * Solidity imports name a file: {@code ./Token.sol}, {@code ../lib/Math.sol}. Package-style imports
 * ({@code @openzeppelin/contracts/...}, {@code forge-std/Test.sol}) are dependencies unless a file of that
 * path exists in the repository (a vendored {@code lib/} or {@code node_modules}-free layout).
 */
@Component
public class SolidityModuleResolver implements ModuleResolver {

	@Override
	public boolean supports(Language language) {
		return language == Language.SOLIDITY;
	}

	@Override
	public Optional<ModuleRef> resolve(FileIR from, String module, int relativeLevel, ModuleIndex index) {
		String path;
		if (module.startsWith(".")) {
			String directory = from.path().contains("/") ? from.path().substring(0, from.path().lastIndexOf('/')) : "";
			path = JavaScriptModuleResolver.normalize(directory + "/" + module);
		}
		else {
			path = module;
		}
		return index.fileAt(path).filter(file -> file.language() == Language.SOLIDITY)
				.map(file -> new ModuleRef(file.moduleName(), List.of(path)));
	}

	@Override
	public boolean isRelative(String module, int relativeLevel) {
		return module.startsWith(".");
	}
}
