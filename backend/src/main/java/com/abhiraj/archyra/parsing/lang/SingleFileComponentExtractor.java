package com.abhiraj.archyra.parsing.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.ImportIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.parsing.LanguageExtractor;

import lombok.RequiredArgsConstructor;

/**
 * Vue ({@code .vue}) and Svelte ({@code .svelte}) single-file components. The component logic lives in
 * {@code <script>} blocks, which are plain JavaScript or TypeScript ({@code lang="ts"}); each block is handed to
 * the matching extractor with its original line offset, so imports of other components, functions and
 * classes appear exactly as in a script file. The template and styles carry no declarations.
 */
@Component
@RequiredArgsConstructor
public class SingleFileComponentExtractor implements LanguageExtractor {

	private static final Pattern SCRIPT = Pattern.compile("<script([^>]*)>(.*?)</script>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern TYPESCRIPT = Pattern.compile("lang\\s*=\\s*[\"'](ts|typescript)[\"']",
			Pattern.CASE_INSENSITIVE);

	private final JavaScriptExtractor javaScript;
	private final TypeScriptExtractor typeScript;

	@Override
	public boolean supports(Language language) {
		return language == Language.VUE || language == Language.SVELTE;
	}

	@Override
	public FileIR extract(SourceFile file) {
		String content;
		try {
			content = new String(Files.readAllBytes(file.absolutePath()), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("File could not be read", ex);
		}

		List<ImportIR> imports = new ArrayList<>();
		List<TypeIR> types = new ArrayList<>();
		List<MethodIR> functions = new ArrayList<>();
		String moduleName = null;
		String packageName = null;

		Matcher matcher = SCRIPT.matcher(content);
		while (matcher.find()) {
			boolean isTypeScript = TYPESCRIPT.matcher(matcher.group(1)).find();
			// keep line numbers: pad with the newlines that precede the script body
			String padding = "\n".repeat((int) content.substring(0, matcher.start(2)).chars().filter(c -> c == '\n').count());
			FileIR block = (isTypeScript ? typeScript : javaScript)
					.extractSource(file, padding + matcher.group(2), file.language());
			imports.addAll(block.imports());
			types.addAll(block.types());
			functions.addAll(block.functions());
			moduleName = block.moduleName();
			packageName = block.packageName();
		}
		if (moduleName == null) { // a component without logic is still a file of its directory
			FileIR empty = javaScript.extractSource(file, "", file.language());
			moduleName = empty.moduleName();
			packageName = empty.packageName();
		}
		return new FileIR(file.relativePath(), file.language(), moduleName, packageName, imports, types, functions);
	}
}
