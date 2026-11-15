package com.abhiraj.archyra.technology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the dependency names out of build and package manifests. Each reader is deliberately lenient and
 * regex based: a manifest that does not parse yields no dependencies rather than failing the analysis, and
 * no build tool is run.
 */
final class ManifestReader {

	/** A dependency: the ecosystem it belongs to and its name (for Maven, {@code group:artifact}). */
	record Dependency(String ecosystem, String name) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
	private static final Pattern REQUIREMENT = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)");
	private static final Pattern POM_DEPENDENCY = Pattern.compile(
			"<(?:dependency|parent)>\\s*<groupId>\\s*([^<\\s]+)\\s*</groupId>\\s*<artifactId>\\s*([^<\\s]+)\\s*</artifactId>",
			Pattern.DOTALL);
	private static final Pattern GRADLE_COORDINATE = Pattern.compile("[\"']([\\w.\\-]+:[\\w.\\-]+)(?::[^\"']*)?[\"']");
	private static final Pattern GRADLE_PLUGIN = Pattern.compile(
			"(?:id\\s*\\(?\\s*[\"']([\\w.\\-]+)[\"']|apply\\s+plugin:\\s*[\"']([\\w.\\-]+)[\"'])");
	private static final Pattern GRADLE_KOTLIN_PLUGIN = Pattern.compile("kotlin\\(\\s*[\"'](\\w+)[\"']\\s*\\)");
	private static final Pattern GO_REQUIRE = Pattern.compile("^\\s*(?:require\\s+)?([\\w.\\-]+\\.[\\w.\\-]+/[\\w./\\-~]+)\\s+v", Pattern.MULTILINE);
	private static final Pattern GEM = Pattern.compile("^\\s*gem\\s+[\"']([\\w.\\-]+)[\"']", Pattern.MULTILINE);
	private static final Pattern GEMSPEC = Pattern.compile("add_(?:runtime_|development_)?dependency\\s*\\(?\\s*[\"']([\\w.\\-]+)[\"']");
	private static final Pattern PACKAGE_REFERENCE = Pattern.compile("<PackageReference\\s+[^>]*Include\\s*=\\s*\"([^\"]+)\"");
	private static final Pattern SWIFT_PACKAGE = Pattern.compile("\\.package\\s*\\(\\s*url:\\s*\"[^\"]*/([\\w.\\-]+?)(?:\\.git)?\"");
	private static final Pattern POD = Pattern.compile("^\\s*pod\\s+['\"]([\\w+/.\\-]+)['\"]", Pattern.MULTILINE);

	private ManifestReader() {
	}

	/** Reads {@code content} of the manifest called {@code fileName}; unknown files give nothing. */
	static List<Dependency> read(String fileName, String content) {
		try {
			String name = fileName.toLowerCase();
			if (name.equals("package.json")) {
				return packageJson(content);
			}
			if (name.equals("composer.json")) {
				return composerJson(content);
			}
			if (name.startsWith("requirements") && name.endsWith(".txt")) {
				return requirements(content);
			}
			if (name.equals("pyproject.toml")) {
				return pyproject(content);
			}
			if (name.equals("pipfile")) {
				return tomlSectionKeys(content, "pypi", "packages", "dev-packages");
			}
			if (name.equals("environment.yml") || name.equals("environment.yaml")) {
				return condaEnvironment(content);
			}
			if (name.equals("pom.xml")) {
				return pom(content);
			}
			if (name.endsWith(".gradle") || name.endsWith(".gradle.kts")) {
				return gradle(content);
			}
			if (name.equals("go.mod")) {
				return matches(GO_REQUIRE, content, "go");
			}
			if (name.equals("cargo.toml")) {
				return tomlSectionKeys(content, "cargo", "dependencies", "dev-dependencies", "build-dependencies",
						"workspace.dependencies");
			}
			if (name.equals("gemfile")) {
				return matches(GEM, content, "rubygems");
			}
			if (name.endsWith(".gemspec")) {
				return matches(GEMSPEC, content, "rubygems");
			}
			if (name.endsWith(".csproj") || name.endsWith(".fsproj") || name.endsWith(".vbproj")) {
				return matches(PACKAGE_REFERENCE, content, "nuget");
			}
			if (name.equals("pubspec.yaml")) {
				return pubspec(content);
			}
			if (name.equals("package.swift")) {
				return matches(SWIFT_PACKAGE, content, "swiftpm", true);
			}
			if (name.equals("podfile")) {
				return matches(POD, content, "cocoapods");
			}
			return List.of();
		}
		catch (RuntimeException ex) {
			return List.of();
		}
	}

	private static List<Dependency> packageJson(String content) {
		JsonNode root = JSON.readTree(content);
		List<Dependency> result = new ArrayList<>();
		for (String section : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
			for (Map.Entry<String, JsonNode> entry : root.path(section).properties()) {
				result.add(new Dependency("npm", entry.getKey()));
			}
		}
		return result;
	}

	private static List<Dependency> composerJson(String content) {
		JsonNode root = JSON.readTree(content);
		List<Dependency> result = new ArrayList<>();
		for (String section : List.of("require", "require-dev")) {
			for (Map.Entry<String, JsonNode> entry : root.path(section).properties()) {
				result.add(new Dependency("composer", entry.getKey()));
			}
		}
		return result;
	}

	private static List<Dependency> requirements(String content) {
		List<Dependency> result = new ArrayList<>();
		for (String line : content.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
				continue;
			}
			Matcher matcher = REQUIREMENT.matcher(trimmed);
			if (matcher.find()) {
				result.add(new Dependency("pypi", matcher.group(1)));
			}
		}
		return result;
	}

	/** PEP 621 {@code dependencies = [...]} arrays plus Poetry's dependency tables. */
	private static List<Dependency> pyproject(String content) {
		List<Dependency> result = new ArrayList<>();
		Matcher arrays = Pattern.compile("(?:dependencies|requires)\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(content);
		while (arrays.find()) {
			Matcher quoted = QUOTED.matcher(arrays.group(1));
			while (quoted.find()) {
				Matcher requirement = REQUIREMENT.matcher(quoted.group(1).strip());
				if (requirement.find()) {
					result.add(new Dependency("pypi", requirement.group(1)));
				}
			}
		}
		result.addAll(tomlSectionKeys(content, "pypi", "tool.poetry.dependencies", "tool.poetry.dev-dependencies",
				"project.optional-dependencies"));
		for (String group : List.of("dev", "test", "docs")) {
			result.addAll(tomlSectionKeys(content, "pypi", "tool.poetry.group." + group + ".dependencies"));
		}
		result.removeIf(dependency -> dependency.name().equals("python"));
		return result;
	}

	/** The keys of the named TOML tables ({@code [dependencies]}, {@code [tool.poetry.dependencies]}, ...). */
	private static List<Dependency> tomlSectionKeys(String content, String ecosystem, String... sections) {
		List<String> wanted = List.of(sections);
		List<Dependency> result = new ArrayList<>();
		boolean inSection = false;
		for (String line : content.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.startsWith("[")) {
				String header = trimmed.replace("[", "").replace("]", "").strip();
				inSection = wanted.contains(header) || wanted.stream().anyMatch(section -> header.endsWith("." + section));
				continue;
			}
			if (inSection && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
				int equals = trimmed.indexOf('=');
				if (equals > 0) {
					String key = trimmed.substring(0, equals).strip().replace("\"", "");
					int dot = key.indexOf('.');
					result.add(new Dependency(ecosystem, dot > 0 ? key.substring(0, dot) : key));
				}
			}
		}
		return result;
	}

	private static List<Dependency> condaEnvironment(String content) {
		List<Dependency> result = new ArrayList<>();
		for (String line : content.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.startsWith("- ") && !trimmed.endsWith(":")) {
				Matcher requirement = REQUIREMENT.matcher(trimmed.substring(2).strip());
				if (requirement.find()) {
					result.add(new Dependency("pypi", requirement.group(1)));
				}
			}
		}
		return result;
	}

	private static List<Dependency> pom(String content) {
		List<Dependency> result = new ArrayList<>();
		Matcher matcher = POM_DEPENDENCY.matcher(content);
		while (matcher.find()) {
			result.add(new Dependency("maven", matcher.group(1) + ":" + matcher.group(2)));
		}
		return result;
	}

	private static List<Dependency> gradle(String content) {
		List<Dependency> result = new ArrayList<>(matches(GRADLE_COORDINATE, content, "maven"));
		Matcher plugins = GRADLE_PLUGIN.matcher(content);
		while (plugins.find()) {
			result.add(new Dependency("gradle-plugin", plugins.group(1) != null ? plugins.group(1) : plugins.group(2)));
		}
		Matcher kotlin = GRADLE_KOTLIN_PLUGIN.matcher(content);
		while (kotlin.find()) {
			result.add(new Dependency("gradle-plugin", "org.jetbrains.kotlin." + kotlin.group(1)));
		}
		return result;
	}

	/** {@code dependencies:} entries, and the {@code flutter} SDK dependency. */
	private static List<Dependency> pubspec(String content) {
		List<Dependency> result = new ArrayList<>();
		boolean inSection = false;
		for (String line : content.split("\n")) {
			if (!line.startsWith(" ") && !line.startsWith("\t") && !line.isBlank()) {
				inSection = line.startsWith("dependencies:") || line.startsWith("dev_dependencies:");
				continue;
			}
			if (inSection && line.matches("^ {2}[A-Za-z_][\\w]*:.*")) {
				result.add(new Dependency("pub", line.strip().substring(0, line.strip().indexOf(':'))));
			}
		}
		return result;
	}

	private static List<Dependency> matches(Pattern pattern, String content, String ecosystem) {
		return matches(pattern, content, ecosystem, false);
	}

	private static List<Dependency> matches(Pattern pattern, String content, String ecosystem, boolean lowerCase) {
		List<Dependency> result = new ArrayList<>();
		Matcher matcher = pattern.matcher(content);
		while (matcher.find()) {
			String name = matcher.group(1);
			result.add(new Dependency(ecosystem, lowerCase ? name.toLowerCase() : name));
		}
		return result;
	}
}
