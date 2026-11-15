package com.abhiraj.archyra.technology;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.ImportIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.RepositoryIR;
import com.abhiraj.archyra.ir.TechnologyCategory;
import com.abhiraj.archyra.ir.TechnologyIR;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Identifies the frameworks, libraries, tools and platforms a repository uses, without running anything:
 * <ol>
 * <li><b>Files</b> that are a technology by themselves: {@code Dockerfile}, {@code *.tf},
 * {@code .github/workflows}, {@code hardhat.config.*}, {@code AndroidManifest.xml}, Kubernetes manifests.</li>
 * <li><b>Manifests</b> ({@code package.json}, {@code pom.xml}, {@code requirements.txt}, {@code go.mod},
 * {@code Cargo.toml}, ...): their dependencies are looked up in the {@link TechnologyCatalog}.</li>
 * <li><b>Languages</b> that are frameworks (Vue, Svelte) and notebooks.</li>
 * <li><b>Imports</b> in the code, for repositories without a manifest (a notebook importing {@code torch}).</li>
 * </ol>
 * Everything is best effort: a file that cannot be read is skipped, never fatal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TechnologyDetector {

	private static final long MAX_FILE_BYTES = 1024 * 1024;
	private static final int MAX_MANIFESTS = 300;
	private static final int MAX_YAML_SCANS = 300;
	private static final int YAML_SCAN_BYTES = 4096;

	private static final Pattern KUBERNETES_KIND = Pattern.compile(
			"(?m)^kind:\\s*(Deployment|Service|Ingress|StatefulSet|DaemonSet|ConfigMap|Pod|CronJob|Job|Namespace|"
					+ "Secret|HorizontalPodAutoscaler|PersistentVolumeClaim)\\b");
	private static final Pattern IMAGE = Pattern.compile("(?mi)^\\s*(?:image:|from)\\s+[\"']?([\\w./:@-]+)");
	private static final Pattern COMPOSE_IMAGE = Pattern.compile("(?m)^\\s*image:\\s*[\"']?([\\w./:@-]+)");

	/** A file name or path that is a technology on its own. */
	private record FileRule(String name, TechnologyCategory category, String ecosystem, Predicate<String> matches) {
	}

	private static final List<FileRule> FILE_RULES = List.of(
			// containers and orchestration
			rule("Docker", TechnologyCategory.INFRASTRUCTURE, "docker", path -> fileName(path).startsWith("dockerfile")
					|| fileName(path).endsWith(".dockerfile") || fileName(path).equals("containerfile")),
			rule("Docker Compose", TechnologyCategory.INFRASTRUCTURE, "docker", path -> fileName(path).matches("(docker-)?compose(\\.[\\w-]+)?\\.ya?ml")),
			rule("Terraform", TechnologyCategory.INFRASTRUCTURE, "terraform", path -> path.endsWith(".tf")),
			rule("Helm", TechnologyCategory.INFRASTRUCTURE, "helm", path -> fileName(path).equals("chart.yaml")),
			rule("Kustomize", TechnologyCategory.INFRASTRUCTURE, "kubernetes", path -> fileName(path).matches("kustomization\\.ya?ml")),
			rule("Pulumi", TechnologyCategory.INFRASTRUCTURE, "pulumi", path -> fileName(path).equals("pulumi.yaml")),
			rule("Ansible", TechnologyCategory.INFRASTRUCTURE, "ansible", path -> fileName(path).equals("ansible.cfg")),
			rule("Serverless Framework", TechnologyCategory.INFRASTRUCTURE, "serverless", path -> fileName(path).matches("serverless\\.(ya?ml|ts|js)")),
			rule("Vagrant", TechnologyCategory.INFRASTRUCTURE, "vagrant", path -> fileName(path).equals("vagrantfile")),
			// CI/CD
			rule("GitHub Actions", TechnologyCategory.CI_CD, "ci", path -> path.startsWith(".github/workflows/")),
			rule("GitLab CI", TechnologyCategory.CI_CD, "ci", path -> path.equals(".gitlab-ci.yml")),
			rule("Jenkins", TechnologyCategory.CI_CD, "ci", path -> fileName(path).equals("jenkinsfile")),
			rule("CircleCI", TechnologyCategory.CI_CD, "ci", path -> path.startsWith(".circleci/")),
			rule("Azure Pipelines", TechnologyCategory.CI_CD, "ci", path -> fileName(path).equals("azure-pipelines.yml")),
			rule("Bitbucket Pipelines", TechnologyCategory.CI_CD, "ci", path -> fileName(path).equals("bitbucket-pipelines.yml")),
			rule("Travis CI", TechnologyCategory.CI_CD, "ci", path -> fileName(path).equals(".travis.yml")),
			// build tools
			rule("Maven", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("pom.xml")),
			rule("Gradle", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).matches("(build|settings)\\.gradle(\\.kts)?")),
			rule("npm", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("package.json")),
			rule("Yarn", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("yarn.lock")),
			rule("pnpm", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("pnpm-lock.yaml")),
			rule("Cargo", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("cargo.toml")),
			rule("Go modules", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("go.mod")),
			rule("pip", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).matches("requirements[\\w.-]*\\.txt")),
			rule("Poetry", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("poetry.lock")),
			rule("Pipenv", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("pipfile")),
			rule("Bundler", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("gemfile")),
			rule("Composer", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("composer.json")),
			rule(".NET SDK", TechnologyCategory.BUILD_TOOL, "build", path -> path.endsWith(".csproj") || path.endsWith(".fsproj")),
			rule("Pub", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("pubspec.yaml")),
			rule("Swift Package Manager", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("package.swift")),
			rule("CocoaPods", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("podfile")),
			rule("Make", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("makefile")),
			rule("CMake", TechnologyCategory.BUILD_TOOL, "build", path -> fileName(path).equals("cmakelists.txt")),
			// frontend frameworks and tooling configured by file
			rule("Angular", TechnologyCategory.FRONTEND_FRAMEWORK, "config", path -> fileName(path).equals("angular.json")),
			rule("Next.js", TechnologyCategory.FRONTEND_FRAMEWORK, "config", path -> fileName(path).matches("next\\.config\\.\\w+")),
			rule("Nuxt", TechnologyCategory.FRONTEND_FRAMEWORK, "config", path -> fileName(path).matches("nuxt\\.config\\.\\w+")),
			rule("SvelteKit", TechnologyCategory.FRONTEND_FRAMEWORK, "config", path -> fileName(path).matches("svelte\\.config\\.\\w+")),
			rule("Astro", TechnologyCategory.FRONTEND_FRAMEWORK, "config", path -> fileName(path).matches("astro\\.config\\.\\w+")),
			rule("Vite", TechnologyCategory.BUILD_TOOL, "config", path -> fileName(path).matches("vite\\.config\\.\\w+")),
			rule("Tailwind CSS", TechnologyCategory.LIBRARY, "config", path -> fileName(path).matches("tailwind\\.config\\.\\w+")),
			// mobile and desktop
			rule("Android", TechnologyCategory.MOBILE, "platform", path -> fileName(path).equals("androidmanifest.xml")),
			rule("iOS / Xcode", TechnologyCategory.MOBILE, "platform", path -> path.endsWith(".xcodeproj/project.pbxproj")),
			rule("Capacitor", TechnologyCategory.MOBILE, "config", path -> fileName(path).matches("capacitor\\.config\\.\\w+")),
			rule("Ionic", TechnologyCategory.MOBILE, "config", path -> fileName(path).equals("ionic.config.json")),
			rule("Tauri", TechnologyCategory.DESKTOP, "config", path -> fileName(path).equals("tauri.conf.json")),
			rule("Electron", TechnologyCategory.DESKTOP, "config", path -> fileName(path).startsWith("electron-builder.")),
			// blockchain and web3
			rule("Hardhat", TechnologyCategory.BLOCKCHAIN, "config", path -> fileName(path).matches("hardhat\\.config\\.\\w+")),
			rule("Foundry", TechnologyCategory.BLOCKCHAIN, "config", path -> fileName(path).equals("foundry.toml")),
			rule("Truffle", TechnologyCategory.BLOCKCHAIN, "config", path -> fileName(path).matches("truffle(-config)?\\.js")),
			rule("Brownie", TechnologyCategory.BLOCKCHAIN, "config", path -> fileName(path).matches("brownie-config\\.ya?ml")),
			rule("Anchor", TechnologyCategory.BLOCKCHAIN, "config", path -> fileName(path).equals("anchor.toml")),
			rule("Move", TechnologyCategory.BLOCKCHAIN, "config", path -> fileName(path).equals("move.toml")),
			// databases and data
			rule("Prisma", TechnologyCategory.DATABASE, "config", path -> fileName(path).equals("schema.prisma")),
			rule("Alembic", TechnologyCategory.DATABASE, "config", path -> fileName(path).equals("alembic.ini")),
			rule("Flyway", TechnologyCategory.DATABASE, "config", path -> fileName(path).matches("flyway\\.(conf|toml)")),
			rule("MLflow", TechnologyCategory.AI_ML, "config", path -> fileName(path).equals("mlproject")),
			rule("DVC", TechnologyCategory.AI_ML, "config", path -> fileName(path).equals("dvc.yaml")),
			rule("Jupyter", TechnologyCategory.DATA, "files", path -> path.endsWith(".ipynb")));

	private static final Set<String> MANIFEST_NAMES = Set.of("package.json", "composer.json", "pyproject.toml",
			"pipfile", "environment.yml", "environment.yaml", "pom.xml", "go.mod", "cargo.toml", "gemfile", "pubspec.yaml",
			"package.swift", "podfile");

	private final TechnologyCatalog catalog;

	private static FileRule rule(String name, TechnologyCategory category, String ecosystem, Predicate<String> matches) {
		return new FileRule(name, category, ecosystem, matches);
	}

	/**
	 * @param root          the cloned repository
	 * @param paths         repository-relative paths of its files
	 * @param repository    the parsed code, for imports
	 * @param languageFiles files per detected language
	 */
	public List<TechnologyIR> detect(Path root, List<String> paths, RepositoryIR repository,
			Map<Language, Integer> languageFiles) {
		Map<String, TechnologyIR> found = new LinkedHashMap<>();

		detectFromFiles(root, paths, found);
		detectFromManifests(root, paths, found);
		detectFromLanguages(languageFiles, found);
		detectFromImports(repository, found);

		List<TechnologyIR> result = new ArrayList<>(found.values());
		result.sort(Comparator.comparing(TechnologyIR::category).thenComparing(TechnologyIR::name));
		return result;
	}

	// ---- files ----------------------------------------------------------------------------------

	private void detectFromFiles(Path root, List<String> paths, Map<String, TechnologyIR> found) {
		int yamlScans = 0;
		for (String path : paths) {
			String lower = path.toLowerCase(Locale.ROOT);
			for (FileRule rule : FILE_RULES) {
				if (rule.matches().test(lower)) {
					add(found, rule.name(), rule.category(), rule.ecosystem(), path);
				}
			}
			String fileName = fileName(lower);
			if (fileName.matches("(docker-)?compose(\\.[\\w-]+)?\\.ya?ml")) {
				imagesIn(root, path, COMPOSE_IMAGE, found);
			}
			else if (fileName.startsWith("dockerfile") || fileName.endsWith(".dockerfile")) {
				imagesIn(root, path, IMAGE, found);
			}
			else if ((lower.endsWith(".yaml") || lower.endsWith(".yml")) && !lower.startsWith(".github/")
					&& !found.containsKey("Kubernetes") && yamlScans < MAX_YAML_SCANS) {
				yamlScans++;
				read(root, path, YAML_SCAN_BYTES).filter(content -> content.contains("apiVersion:")
						&& KUBERNETES_KIND.matcher(content).find())
						.ifPresent(content -> add(found, "Kubernetes", TechnologyCategory.INFRASTRUCTURE, "kubernetes", path));
			}
		}
	}

	/** Technologies named by container images: {@code image: postgres:16}, {@code FROM jupyter/base}. */
	private void imagesIn(Path root, String path, Pattern pattern, Map<String, TechnologyIR> found) {
		read(root, path, (int) MAX_FILE_BYTES).ifPresent(content -> {
			Matcher matcher = pattern.matcher(content);
			while (matcher.find()) {
				String image = matcher.group(1).split("@")[0];
				int colon = image.indexOf(':');
				String repository = colon > 0 ? image.substring(0, colon) : image;
				// strip registries: docker.io/library/postgres, ghcr.io/org/image
				List<String> segments = List.of(repository.split("/"));
				for (int from = 0; from < segments.size(); from++) {
					String candidate = String.join("/", segments.subList(from, segments.size()));
					Optional<TechnologyCatalog.Match> match = catalog.match("docker-image", candidate);
					if (match.isPresent()) {
						add(found, match.get().name(), match.get().category(), "docker-image", path);
						break;
					}
				}
			}
		});
	}

	// ---- manifests ------------------------------------------------------------------------------

	private void detectFromManifests(Path root, List<String> paths, Map<String, TechnologyIR> found) {
		int manifests = 0;
		for (String path : paths) {
			String fileName = fileName(path.toLowerCase(Locale.ROOT));
			boolean manifest = MANIFEST_NAMES.contains(fileName) || (fileName.startsWith("requirements") && fileName.endsWith(".txt"))
					|| fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts") || fileName.endsWith(".csproj")
					|| fileName.endsWith(".fsproj") || fileName.endsWith(".gemspec");
			if (!manifest || manifests++ >= MAX_MANIFESTS) {
				continue;
			}
			Optional<String> content = read(root, path, (int) MAX_FILE_BYTES);
			if (content.isEmpty()) {
				continue;
			}
			for (ManifestReader.Dependency dependency : ManifestReader.read(fileName(path), content.get())) {
				catalog.match(dependency.ecosystem(), dependency.name())
						.ifPresent(match -> add(found, match.name(), match.category(), dependency.ecosystem(), path));
			}
			if (fileName.endsWith(".csproj") && content.get().contains("Microsoft.NET.Sdk.Web")) {
				add(found, "ASP.NET Core", TechnologyCategory.BACKEND_FRAMEWORK, "nuget", path);
			}
		}
	}

	// ---- languages and imports -----------------------------------------------------------------

	private void detectFromLanguages(Map<Language, Integer> languageFiles, Map<String, TechnologyIR> found) {
		if (languageFiles.containsKey(Language.VUE)) {
			add(found, "Vue", TechnologyCategory.FRONTEND_FRAMEWORK, "files", "*.vue files");
		}
		if (languageFiles.containsKey(Language.SVELTE)) {
			add(found, "Svelte", TechnologyCategory.FRONTEND_FRAMEWORK, "files", "*.svelte files");
		}
	}

	/** Libraries that the code imports, for repositories that declare no manifest. */
	private void detectFromImports(RepositoryIR repository, Map<String, TechnologyIR> found) {
		Set<String> seen = new LinkedHashSet<>();
		for (FileIR file : repository.files()) {
			String ecosystem = ecosystemOf(file.language());
			if (ecosystem == null) {
				continue;
			}
			for (ImportIR imported : file.imports()) {
				if (imported.relativeLevel() > 0 || imported.module() == null || imported.module().isBlank()
						|| imported.module().startsWith(".") || imported.module().startsWith("/")) {
					continue;
				}
				String key = ecosystem + "|" + packageOf(file.language(), imported);
				if (seen.add(key)) {
					String name = packageOf(file.language(), imported);
					matchImport(ecosystem, name).ifPresent(match -> add(found, match.name(), match.category(),
							ecosystem, "imports in " + file.path()));
				}
			}
		}
	}

	private Optional<TechnologyCatalog.Match> matchImport(String ecosystem, String name) {
		if (ecosystem.equals("java-package") || ecosystem.equals("csharp-namespace") || ecosystem.equals("php-namespace")) {
			// longest package prefix first: org.springframework.boot.autoconfigure → org.springframework.boot → org.springframework
			String candidate = name;
			while (!candidate.isEmpty()) {
				Optional<TechnologyCatalog.Match> match = catalog.match(ecosystem, candidate);
				if (match.isPresent()) {
					return match;
				}
				int dot = candidate.lastIndexOf('.');
				candidate = dot < 0 ? "" : candidate.substring(0, dot);
			}
			return Optional.empty();
		}
		return catalog.match(ecosystem, name);
	}

	private static String ecosystemOf(Language language) {
		return switch (language) {
			case PYTHON, JUPYTER -> "pypi";
			case JAVASCRIPT, TYPESCRIPT, VUE, SVELTE -> "npm";
			case GO -> "go";
			case RUST -> "cargo";
			case RUBY -> "rubygems";
			case JAVA, KOTLIN -> "java-package";
			case CSHARP -> "csharp-namespace";
			case PHP -> "php-namespace";
			default -> null;
		};
	}

	/** The part of an import that names the library: a top-level package, an npm package, a Go module path. */
	private static String packageOf(Language language, ImportIR imported) {
		String module = imported.module();
		return switch (language) {
			case PYTHON, JUPYTER, RUST, RUBY -> module.split("[./]")[0];
			case JAVASCRIPT, TYPESCRIPT, VUE, SVELTE -> {
				String[] parts = module.split("/");
				yield module.startsWith("@") && parts.length > 1 ? parts[0] + "/" + parts[1] : parts[0];
			}
			default -> module;
		};
	}

	// ---- helpers --------------------------------------------------------------------------------

	private static void add(Map<String, TechnologyIR> found, String name, TechnologyCategory category,
			String ecosystem, String evidence) {
		found.putIfAbsent(name, new TechnologyIR(name, category, ecosystem, evidence));
	}

	private static String fileName(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	/** The first {@code maxBytes} of a file, or empty if it cannot be read. */
	private static Optional<String> read(Path root, String relativePath, int maxBytes) {
		Path file = root.resolve(relativePath).normalize();
		if (!file.startsWith(root)) {
			return Optional.empty();
		}
		try {
			if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > MAX_FILE_BYTES) {
				return Optional.empty();
			}
			byte[] bytes = Files.readAllBytes(file);
			int length = Math.min(bytes.length, maxBytes);
			return Optional.of(new String(bytes, 0, length, StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			log.debug("Could not read {}: {}", relativePath, ex.toString());
			return Optional.empty();
		}
	}
}
