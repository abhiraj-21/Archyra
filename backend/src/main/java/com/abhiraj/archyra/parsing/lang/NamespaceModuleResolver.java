package com.abhiraj.archyra.parsing.lang;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.parsing.ModuleIndex;
import com.abhiraj.archyra.parsing.ModuleRef;
import com.abhiraj.archyra.parsing.ModuleResolver;

/**
 * Languages whose imports name a namespace exactly as it is declared: Java and Kotlin packages,
 * C# namespaces, PHP namespaces (separators normalised to dots by the extractor).
 */
@Component
public class NamespaceModuleResolver implements ModuleResolver {

	@Override
	public boolean supports(Language language) {
		return language == Language.JAVA || language == Language.KOTLIN || language == Language.CSHARP
				|| language == Language.PHP;
	}

	@Override
	public Optional<ModuleRef> resolve(FileIR from, String module, int relativeLevel, ModuleIndex index) {
		return index.containsModule(module) ? Optional.of(ModuleRef.ofModule(module)) : Optional.empty();
	}

	@Override
	public boolean importsTypes() {
		return true;
	}
}
