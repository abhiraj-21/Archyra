package com.abhiraj.archyra.ai.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads what a repository says about itself while its clone exists: the root README and the name, description and keywords
 * its root manifests declare. They become two extra chunks of the repository node, so "what is this project about?" can be
 * answered from the project's own words. Nothing is executed: files are read as text (at most {@value #README_MAX_BYTES}
 * bytes of a README, {@value #MANIFEST_MAX_BYTES} of a manifest), manifests are parsed defensively (a regular expression
 * or Jackson's tree reader, never an XML or TOML engine with entities or includes) and any failure only means that chunk is
 * not made. Never logs README or manifest text.
 */
@Slf4j
@Component
public class ProjectDocs {

	static final int README_MAX_BYTES = 40 * 1024;
	static final int MANIFEST_MAX_BYTES = 64 * 1024;
	/** The manifest chunk, in characters, however many manifests there are. */
	static final int MANIFEST_MAX_CHARS = 1500;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Root-level README names, best first: {@code README.md}, {@code .markdown}, {@code .rst}, {@code .txt}, then none. */
	private static final List<String> README_NAMES = List.of("readme.md", "readme.markdown", "readme.rst", "readme.txt", "readme");
	private static final List<String> MANIFESTS = List.of("pom.xml", "package.json", "pyproject.toml", "Cargo.toml", "go.mod");

	private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
	private static final Pattern HTML_TAG = Pattern.compile("</?[A-Za-z][^>\\n]*>");
	private static final Pattern MD_BADGE_LINK = Pattern.compile("\\[!\\[[^\\]]*\\]\\([^)]*\\)\\]\\([^)]*\\)|\\[!\\[[^\\]]*\\]\\[[^\\]]*\\]\\]\\([^)]*\\)");
	private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)|!\\[[^\\]]*\\]\\[[^\\]]*\\]");
	private static final Pattern BADGE_DEFINITION = Pattern.compile("(?i)^\\s*\\[[^\\]]+\\]:\\s*\\S*(shields\\.io|badge|badgen|travis-ci|circleci|codecov|coveralls)\\S*.*$");
	private static final Pattern RST_IMAGE = Pattern.compile("^\\s*\\.\\.\\s+(\\|[^|]+\\|\\s+)?(image|figure|raw)::.*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern RST_OPTION = Pattern.compile("^\\s+:[A-Za-z-]+:.*$");

	/** The root README as a chunk of {@code repositoryNodeId}, or empty when there is none or it holds nothing but badges. */
	public Optional<ChunkData> readme(Path root, String repositoryNodeId, int maxChars) {
		try {
			Optional<Path> file = rootFile(root, README_NAMES);
			if (file.isEmpty()) {
				return Optional.empty();
			}
			String cleaned = cleanReadme(read(file.get(), README_MAX_BYTES));
			if (cleaned.isBlank()) {
				return Optional.empty();
			}
			if (cleaned.length() > maxChars) {
				cleaned = cleaned.substring(0, maxChars) + ChunkExtractor.TRUNCATION_MARKER;
			}
			return Optional.of(new ChunkData(repositoryNodeId, ChunkKind.README, file.get().getFileName().toString(), 1,
					lineCount(cleaned), cleaned));
		}
		catch (IOException | RuntimeException ex) {
			log.debug("Skipping the README ({})", ex.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	/** The root manifests' name, description and keywords as one chunk of {@code repositoryNodeId}, or empty. */
	public Optional<ChunkData> manifest(Path root, String repositoryNodeId) {
		List<String> lines = new ArrayList<>();
		String firstFile = null;
		for (String name : MANIFESTS) {
			try {
				Path file = root.resolve(name).normalize();
				if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				String text = read(file, MANIFEST_MAX_BYTES);
				String summary = switch (name) {
					case "pom.xml" -> pom(text);
					case "package.json" -> packageJson(text);
					case "pyproject.toml" -> toml(text, List.of("project", "tool.poetry"));
					case "Cargo.toml" -> toml(text, List.of("package"));
					default -> goMod(text);
				};
				if (summary != null && !summary.isBlank()) {
					lines.add(name + ": " + summary);
					if (firstFile == null) {
						firstFile = name;
					}
				}
			}
			catch (IOException | RuntimeException ex) {
				log.debug("Skipping a manifest ({})", ex.getClass().getSimpleName());
			}
		}
		if (lines.isEmpty()) {
			return Optional.empty();
		}
		String text = String.join("\n", lines);
		if (text.length() > MANIFEST_MAX_CHARS) {
			text = text.substring(0, MANIFEST_MAX_CHARS);
		}
		return Optional.of(new ChunkData(repositoryNodeId, ChunkKind.MANIFEST, firstFile, 1, lineCount(text), text));
	}

	// ---- README --------------------------------------------------------------------------------

	/** Without badge and image lines, HTML and runs of blank lines. Public for the tests. */
	static String cleanReadme(String raw) {
		String text = HTML_COMMENT.matcher(raw.replace("\u0000", "")).replaceAll("");
		List<String> kept = new ArrayList<>();
		boolean skippingRstOptions = false;
		for (String line : text.split("\\r?\\n", -1)) {
			if (RST_IMAGE.matcher(line).matches()) {
				skippingRstOptions = true; // the directive and the options indented under it
				continue;
			}
			if (skippingRstOptions && RST_OPTION.matcher(line).matches()) {
				continue;
			}
			skippingRstOptions = false;
			if (BADGE_DEFINITION.matcher(line).matches()) {
				continue;
			}
			String stripped = MD_IMAGE.matcher(MD_BADGE_LINK.matcher(line).replaceAll("")).replaceAll("");
			stripped = HTML_TAG.matcher(stripped).replaceAll("");
			boolean hadMedia = !stripped.equals(line.replaceAll("</?[A-Za-z][^>\\n]*>", ""));
			if (hadMedia && stripped.replaceAll("[\\s\\[\\]()|*_-]+", "").isEmpty()) {
				continue; // a line that was only badges or images
			}
			kept.add(stripped.stripTrailing());
		}
		StringBuilder out = new StringBuilder();
		int blanks = 0;
		for (String line : kept) {
			if (line.isBlank()) {
				blanks++;
				if (blanks == 1 && out.length() > 0) {
					out.append('\n');
				}
				continue;
			}
			blanks = 0;
			out.append(line).append('\n');
		}
		return out.toString().strip();
	}

	// ---- manifests -----------------------------------------------------------------------------

	private static final Pattern POM_BLOCKS = Pattern.compile(
			"<(parent|dependencies|dependencyManagement|build|profiles|reporting|modules|repositories|pluginRepositories|distributionManagement|scm|licenses|developers|contributors|organization|issueManagement|ciManagement|mailingLists|properties)\\b.*?</\\1>",
			Pattern.DOTALL);

	private static String pom(String xml) {
		String text = POM_BLOCKS.matcher(HTML_COMMENT.matcher(xml).replaceAll("")).replaceAll("");
		String name = firstTag(text, "name");
		String description = firstTag(text, "description");
		String artifact = firstTag(text, "artifactId");
		return fields("name", name != null ? name : artifact, "description", description);
	}

	private static String firstTag(String xml, String tag) {
		Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
		return matcher.find() ? unescapeXml(matcher.group(1)) : null;
	}

	private static String unescapeXml(String text) {
		return clean(text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&"));
	}

	private static String packageJson(String json) {
		JsonNode tree = JSON.readTree(json);
		if (tree == null || !tree.isObject()) {
			return null;
		}
		List<String> keywords = new ArrayList<>();
		JsonNode words = tree.get("keywords");
		if (words != null && words.isArray()) {
			for (JsonNode word : words) {
				if (word.isString() && keywords.size() < 12) {
					keywords.add(clean(word.asString()));
				}
			}
		}
		return fields("name", text(tree, "name"), "description", text(tree, "description"), "keywords",
				keywords.isEmpty() ? null : String.join(", ", keywords));
	}

	private static String text(JsonNode tree, String field) {
		JsonNode value = tree.get(field);
		return value != null && value.isString() ? clean(value.asString()) : null;
	}

	/** {@code name} and {@code description} of the first of {@code sections} that has them. */
	private static String toml(String text, List<String> sections) {
		String section = "";
		String name = null;
		String description = null;
		Pattern keyValue = Pattern.compile("^\\s*(name|description)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)')\\s*(?:#.*)?$");
		for (String line : text.split("\\r?\\n")) {
			String trimmed = line.strip();
			if (trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.startsWith("[[")) {
				section = trimmed.substring(1, trimmed.length() - 1).strip();
				continue;
			}
			if (!sections.contains(section)) {
				continue;
			}
			Matcher matcher = keyValue.matcher(line);
			if (matcher.matches()) {
				String value = clean(matcher.group(2) != null ? matcher.group(2).replace("\\\"", "\"") : matcher.group(3));
				if (matcher.group(1).equals("name") && name == null) {
					name = value;
				}
				else if (matcher.group(1).equals("description") && description == null) {
					description = value;
				}
			}
		}
		return fields("name", name, "description", description);
	}

	private static String goMod(String text) {
		Matcher matcher = Pattern.compile("(?m)^\\s*module\\s+(\\S+)").matcher(text);
		return matcher.find() ? fields("module", clean(matcher.group(1))) : null;
	}

	/** {@code name: x; description: y}, leaving out what is missing; null when nothing is left. */
	private static String fields(String... pairs) {
		List<String> parts = new ArrayList<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			if (pairs[i + 1] != null && !pairs[i + 1].isBlank()) {
				parts.add(pairs[i] + ": " + pairs[i + 1]);
			}
		}
		return parts.isEmpty() ? null : String.join("; ", parts);
	}

	/** One printable line, no control characters, bounded. */
	private static String clean(String text) {
		String line = text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
		return line.length() > 300 ? line.substring(0, 300) : line;
	}

	// ---- files ---------------------------------------------------------------------------------

	/** A regular file of the repository root (not a link, not outside the root) whose name matches one of {@code names}, best first. */
	private static Optional<Path> rootFile(Path root, List<String> names) throws IOException {
		try (Stream<Path> files = Files.list(root)) {
			List<Path> candidates = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
			for (String wanted : names) {
				for (Path candidate : candidates) {
					if (candidate.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted)) {
						return Optional.of(candidate);
					}
				}
			}
		}
		return Optional.empty();
	}

	private static String read(Path file, int maxBytes) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8).replace("\u0000", "");
		}
	}

	private static int lineCount(String text) {
		return Math.max(1, (int) text.lines().count());
	}
}
