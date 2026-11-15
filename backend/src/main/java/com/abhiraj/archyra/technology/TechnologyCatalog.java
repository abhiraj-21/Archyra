package com.abhiraj.archyra.technology;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ir.TechnologyCategory;

/**
 * The known frameworks, libraries and tools, loaded from {@code technologies.tsv}. A row is
 * {@code ecosystem, pattern, technology, category}; a pattern ending in {@code *} matches by prefix, any
 * other must match the whole name (case-insensitively, with {@code _} and {@code .} read as {@code -} for
 * Python and Ruby names, and {@code _} for Rust crates). Supporting another library is a new row, not new code.
 */
@Component
public class TechnologyCatalog {

	/** A matched technology. */
	public record Match(String name, TechnologyCategory category) {
	}

	private record Row(String pattern, boolean prefix, String name, TechnologyCategory category) {
	}

	private final Map<String, List<Row>> byEcosystem = new HashMap<>();

	public TechnologyCatalog() {
		try (InputStream stream = TechnologyCatalog.class.getResourceAsStream("/technologies.tsv")) {
			if (stream == null) {
				throw new IllegalStateException("technologies.tsv is missing from the classpath");
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] columns = line.split("\t");
				if (columns.length != 4) {
					throw new IllegalStateException("Malformed technologies.tsv row: " + line);
				}
				boolean prefix = columns[1].endsWith("*");
				String pattern = normalise(columns[0], prefix ? columns[1].substring(0, columns[1].length() - 1) : columns[1]);
				byEcosystem.computeIfAbsent(columns[0], key -> new ArrayList<>())
						.add(new Row(pattern, prefix, columns[2], TechnologyCategory.valueOf(columns[3])));
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("technologies.tsv could not be read", ex);
		}
	}

	/** The technology a dependency or import of {@code ecosystem} stands for, if known. */
	public Optional<Match> match(String ecosystem, String name) {
		List<Row> rows = byEcosystem.get(ecosystem);
		if (rows == null || name == null || name.isBlank()) {
			return Optional.empty();
		}
		String normalised = normalise(ecosystem, name);
		for (Row row : rows) {
			if (row.prefix() ? normalised.startsWith(row.pattern()) : normalised.equals(row.pattern())) {
				return Optional.of(new Match(row.name(), row.category()));
			}
		}
		return Optional.empty();
	}

	private static String normalise(String ecosystem, String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return switch (ecosystem) {
			case "pypi", "rubygems" -> lower.replace('_', '-').replace('.', '-');
			case "cargo" -> lower.replace('_', '-'); // anchor_lang in code, anchor-lang in Cargo.toml
			default -> lower;
		};
	}
}
