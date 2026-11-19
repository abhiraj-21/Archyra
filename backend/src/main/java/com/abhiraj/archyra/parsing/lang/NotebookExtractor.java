package com.abhiraj.archyra.parsing.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.parsing.LanguageExtractor;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jupyter notebooks ({@code .ipynb}). A notebook is JSON; its code cells are joined into one Python source
 * (IPython magics {@code %...} and shell escapes {@code !...} are commented out) and parsed like a Python
 * module, so imports, functions and classes defined in cells appear in the graph. Markdown and outputs are
 * ignored. Line numbers refer to the joined code, not to the cells.
 */
@Component
@RequiredArgsConstructor
public class NotebookExtractor implements LanguageExtractor {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final PythonExtractor python;

	@Override
	public boolean supports(Language language) {
		return language == Language.JUPYTER;
	}

	@Override
	public FileIR extract(SourceFile file) {
		String source;
		try {
			source = joinedCode(file.absolutePath());
		}
		catch (IOException | RuntimeException ex) {
			throw new IllegalStateException("Notebook is not valid JSON", ex);
		}
		return python.extractSource(file, source, Language.JUPYTER);
	}

	/**
	 * The code cells of a notebook joined into the Python text the extractor reads (magics commented out). The line
	 * numbers of the extracted declarations refer to this text, not to the JSON file, so whoever shows the code of a
	 * declaration must read it from here.
	 */
	public static String joinedCode(Path notebookFile) throws IOException {
		JsonNode notebook = MAPPER.readTree(Files.readString(notebookFile, StandardCharsets.UTF_8));
		StringBuilder source = new StringBuilder();
		for (JsonNode cell : notebook.path("cells")) {
			if (!"code".equals(cell.path("cell_type").asString(""))) {
				continue;
			}
			JsonNode lines = cell.path("source");
			String code = lines.isArray() ? joinLines(lines) : lines.asString("");
			for (String line : code.split("\n", -1)) {
				boolean magic = line.stripLeading().startsWith("%") || line.stripLeading().startsWith("!");
				source.append(magic ? "# " + line : line).append('\n');
			}
			source.append('\n');
		}
		return source.toString();
	}

	private static String joinLines(JsonNode lines) {
		StringBuilder joined = new StringBuilder();
		for (JsonNode line : lines) {
			joined.append(line.asString(""));
		}
		return joined.toString();
	}
}
