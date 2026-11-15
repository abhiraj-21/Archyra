package com.abhiraj.archyra.technology;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.ImportIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.RepositoryIR;
import com.abhiraj.archyra.ir.TechnologyCategory;
import com.abhiraj.archyra.ir.TechnologyIR;

class TechnologyDetectorTest {

	@TempDir
	Path root;

	private final TechnologyDetector detector = new TechnologyDetector(new TechnologyCatalog());
	private final List<String> paths = new ArrayList<>();

	private void write(String path, String content) throws IOException {
		Path file = root.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		paths.add(path);
	}

	private List<TechnologyIR> detect() {
		return detect(new RepositoryIR("repo", "url", List.of()), Map.of());
	}

	private List<TechnologyIR> detect(RepositoryIR repository, Map<Language, Integer> languages) {
		return detector.detect(root, paths, repository, languages);
	}

	private static TechnologyIR named(List<TechnologyIR> found, String name) {
		return found.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
	}

	private void assertFound(List<TechnologyIR> found, String name, TechnologyCategory category, String evidence) {
		TechnologyIR technology = named(found, name);
		assertThat(technology).as(name).isNotNull();
		assertThat(technology.category()).isEqualTo(category);
		assertThat(technology.evidence()).isEqualTo(evidence);
	}

	// ---- manifests ------------------------------------------------------------------------------

	@Test
	void readsNpmDependenciesAcrossAMonorepo() throws IOException {
		write("package.json", """
				{"name": "root", "dependencies": {"react": "^18", "next": "14", "@reduxjs/toolkit": "2"},
				 "devDependencies": {"jest": "29", "vite": "5", "tailwindcss": "3"}}""");
		write("apps/mobile/package.json", """
				{"dependencies": {"react-native": "0.74", "expo": "51"}}""");
		write("services/api/package.json", """
				{"dependencies": {"@nestjs/core": "10", "express": "4", "prisma": "5", "ethers": "6"}}""");

		List<TechnologyIR> found = detect();

		assertFound(found, "React", TechnologyCategory.FRONTEND_FRAMEWORK, "package.json");
		assertFound(found, "Next.js", TechnologyCategory.FRONTEND_FRAMEWORK, "package.json");
		assertFound(found, "Jest", TechnologyCategory.TESTING, "package.json");
		assertFound(found, "Vite", TechnologyCategory.BUILD_TOOL, "package.json");
		assertFound(found, "React Native", TechnologyCategory.MOBILE, "apps/mobile/package.json");
		assertFound(found, "Expo", TechnologyCategory.MOBILE, "apps/mobile/package.json");
		assertFound(found, "NestJS", TechnologyCategory.BACKEND_FRAMEWORK, "services/api/package.json");
		assertFound(found, "Prisma", TechnologyCategory.DATABASE, "services/api/package.json");
		assertFound(found, "ethers.js", TechnologyCategory.BLOCKCHAIN, "services/api/package.json");
		assertFound(found, "npm", TechnologyCategory.BUILD_TOOL, "package.json");
	}

	@Test
	void readsPythonRequirementsAndPyprojectIncludingPoetry() throws IOException {
		write("requirements.txt", "# ml\ntorch==2.1\nPandas>=2\n-r other.txt\nscikit_learn\nflask[async]==3.0\n");
		write("pyproject.toml", """
				[project]
				dependencies = [
				  "fastapi>=0.100",
				  "transformers",
				]

				[tool.poetry.dependencies]
				python = "^3.11"
				langchain = "^0.1"
				numpy = { version = "1.26" }
				""");

		List<TechnologyIR> found = detect();

		assertFound(found, "PyTorch", TechnologyCategory.AI_ML, "requirements.txt");
		assertFound(found, "pandas", TechnologyCategory.DATA, "requirements.txt");
		assertFound(found, "scikit-learn", TechnologyCategory.AI_ML, "requirements.txt");
		assertFound(found, "Flask", TechnologyCategory.BACKEND_FRAMEWORK, "requirements.txt");
		assertFound(found, "FastAPI", TechnologyCategory.BACKEND_FRAMEWORK, "pyproject.toml");
		assertFound(found, "Hugging Face Transformers", TechnologyCategory.AI_ML, "pyproject.toml");
		assertFound(found, "LangChain", TechnologyCategory.AI_ML, "pyproject.toml");
		assertFound(found, "NumPy", TechnologyCategory.DATA, "pyproject.toml");
		assertThat(named(found, "python")).isNull();
	}

	@Test
	void readsMavenAndGradleDependenciesAndPlugins() throws IOException {
		write("pom.xml", """
				<project>
				  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId></parent>
				  <dependencies>
				    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
				    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId></dependency>
				    <dependency><groupId>org.neo4j</groupId><artifactId>neo4j-ogm</artifactId></dependency>
				  </dependencies>
				</project>""");
		write("android/app/build.gradle.kts", """
				plugins { id("com.android.application"); kotlin("android") }
				dependencies { implementation("androidx.core:core-ktx:1.12.0"); testImplementation("org.mockito:mockito-core:5") }""");

		List<TechnologyIR> found = detect();

		assertFound(found, "Spring Boot", TechnologyCategory.BACKEND_FRAMEWORK, "pom.xml");
		assertFound(found, "PostgreSQL", TechnologyCategory.DATABASE, "pom.xml");
		assertFound(found, "JUnit", TechnologyCategory.TESTING, "pom.xml");
		assertFound(found, "Neo4j", TechnologyCategory.DATABASE, "pom.xml");
		assertFound(found, "Android", TechnologyCategory.MOBILE, "android/app/build.gradle.kts");
		assertFound(found, "Mockito", TechnologyCategory.TESTING, "android/app/build.gradle.kts");
		assertFound(found, "Maven", TechnologyCategory.BUILD_TOOL, "pom.xml");
		assertFound(found, "Gradle", TechnologyCategory.BUILD_TOOL, "android/app/build.gradle.kts");
	}

	@Test
	void readsGoRustPhpRubyDotnetFlutterManifests() throws IOException {
		write("go.mod", "module x\n\nrequire (\n\tgithub.com/gin-gonic/gin v1.9.1\n\tgorm.io/gorm v1.25.0\n\tgo.uber.org/zap v1.0.0\n)\n");
		write("Cargo.toml", "[package]\nname = \"c\"\n\n[dependencies]\ntokio = { version = \"1\", features = [\"full\"] }\nanchor-lang = \"0.29\"\nserde = \"1\"\n\n[dev-dependencies]\nclap = \"4\"\n");
		write("composer.json", "{\"require\": {\"laravel/framework\": \"^10\", \"php\": \">=8\"}, \"require-dev\": {\"phpunit/phpunit\": \"^10\"}}");
		write("Gemfile", "source 'https://rubygems.org'\ngem 'rails', '~> 7.1'\ngem \"sidekiq\"\ngem 'rspec-rails'\n");
		write("src/App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"><ItemGroup><PackageReference Include=\"Npgsql.EntityFrameworkCore.PostgreSQL\" Version=\"8\" /><PackageReference Include=\"xunit\" Version=\"2\" /></ItemGroup></Project>");
		write("pubspec.yaml", "name: app\ndependencies:\n  flutter:\n    sdk: flutter\n  flutter_bloc: ^8.0.0\n  dio: ^5.0.0\ndev_dependencies:\n  flutter_test:\n    sdk: flutter\n");

		List<TechnologyIR> found = detect();

		assertFound(found, "Gin", TechnologyCategory.BACKEND_FRAMEWORK, "go.mod");
		assertFound(found, "GORM", TechnologyCategory.DATABASE, "go.mod");
		assertFound(found, "Tokio", TechnologyCategory.LIBRARY, "Cargo.toml");
		assertFound(found, "Anchor", TechnologyCategory.BLOCKCHAIN, "Cargo.toml");
		assertFound(found, "clap", TechnologyCategory.LIBRARY, "Cargo.toml");
		assertFound(found, "Laravel", TechnologyCategory.BACKEND_FRAMEWORK, "composer.json");
		assertFound(found, "PHPUnit", TechnologyCategory.TESTING, "composer.json");
		assertFound(found, "Ruby on Rails", TechnologyCategory.BACKEND_FRAMEWORK, "Gemfile");
		assertFound(found, "RSpec", TechnologyCategory.TESTING, "Gemfile");
		assertFound(found, "ASP.NET Core", TechnologyCategory.BACKEND_FRAMEWORK, "src/App/App.csproj");
		assertFound(found, "PostgreSQL", TechnologyCategory.DATABASE, "src/App/App.csproj");
		assertFound(found, "xUnit", TechnologyCategory.TESTING, "src/App/App.csproj");
		assertFound(found, "Flutter", TechnologyCategory.MOBILE, "pubspec.yaml");
		assertFound(found, "BLoC", TechnologyCategory.LIBRARY, "pubspec.yaml");
	}

	@Test
	void malformedManifestsAreSkippedNotFatal() throws IOException {
		write("package.json", "{ this is not json");
		write("pom.xml", "<<<<");
		write("requirements.txt", "\u0000\u0001\n");
		write("go.mod", "");

		assertThat(detect()).extracting(TechnologyIR::name).doesNotContain("React");
	}

	// ---- files, infrastructure, ci, blockchain --------------------------------------------------

	@Test
	void recognisesInfrastructureAndCiFiles() throws IOException {
		write("Dockerfile", "FROM jupyter/scipy-notebook:latest\nRUN pip install x\n");
		write("deploy/docker-compose.yml", "services:\n  db:\n    image: postgres:16\n  cache:\n    image: \"redis:7\"\n  queue:\n    image: bitnami/kafka:3.6\n");
		write("infra/main.tf", "resource \"aws_s3_bucket\" \"b\" {}");
		write(".github/workflows/ci.yml", "name: ci");
		write("Jenkinsfile", "pipeline {}");
		write("k8s/app.yaml", "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: app\n");
		write("helm/Chart.yaml", "name: chart");
		write("config/settings.yaml", "debug: true");

		List<TechnologyIR> found = detect();

		assertFound(found, "Docker", TechnologyCategory.INFRASTRUCTURE, "Dockerfile");
		assertFound(found, "Docker Compose", TechnologyCategory.INFRASTRUCTURE, "deploy/docker-compose.yml");
		assertFound(found, "Terraform", TechnologyCategory.INFRASTRUCTURE, "infra/main.tf");
		assertFound(found, "GitHub Actions", TechnologyCategory.CI_CD, ".github/workflows/ci.yml");
		assertFound(found, "Jenkins", TechnologyCategory.CI_CD, "Jenkinsfile");
		assertFound(found, "Kubernetes", TechnologyCategory.INFRASTRUCTURE, "k8s/app.yaml");
		assertFound(found, "Helm", TechnologyCategory.INFRASTRUCTURE, "helm/Chart.yaml");
		// images name databases and brokers, in Compose and in Dockerfiles
		assertFound(found, "PostgreSQL", TechnologyCategory.DATABASE, "deploy/docker-compose.yml");
		assertFound(found, "Redis", TechnologyCategory.DATABASE, "deploy/docker-compose.yml");
		assertFound(found, "Apache Kafka", TechnologyCategory.INFRASTRUCTURE, "deploy/docker-compose.yml");
		assertFound(found, "Jupyter", TechnologyCategory.DATA, "Dockerfile");
		assertThat(named(found, "Kubernetes").evidence()).isEqualTo("k8s/app.yaml");
	}

	@Test
	void recognisesBlockchainMobileAndFrontendToolingByFile() throws IOException {
		write("hardhat.config.ts", "export default {}");
		write("foundry.toml", "[profile.default]");
		write("Anchor.toml", "[programs]");
		write("android/app/src/main/AndroidManifest.xml", "<manifest/>");
		write("ios/App.xcodeproj/project.pbxproj", "x");
		write("angular.json", "{}");
		write("next.config.js", "module.exports = {}");
		write("src-tauri/tauri.conf.json", "{}");
		write("prisma/schema.prisma", "model A {}");
		write("notebooks/a.ipynb", "{}");

		List<TechnologyIR> found = detect();

		assertFound(found, "Hardhat", TechnologyCategory.BLOCKCHAIN, "hardhat.config.ts");
		assertFound(found, "Foundry", TechnologyCategory.BLOCKCHAIN, "foundry.toml");
		assertFound(found, "Anchor", TechnologyCategory.BLOCKCHAIN, "Anchor.toml");
		assertFound(found, "Android", TechnologyCategory.MOBILE, "android/app/src/main/AndroidManifest.xml");
		assertFound(found, "iOS / Xcode", TechnologyCategory.MOBILE, "ios/App.xcodeproj/project.pbxproj");
		assertFound(found, "Angular", TechnologyCategory.FRONTEND_FRAMEWORK, "angular.json");
		assertFound(found, "Next.js", TechnologyCategory.FRONTEND_FRAMEWORK, "next.config.js");
		assertFound(found, "Tauri", TechnologyCategory.DESKTOP, "src-tauri/tauri.conf.json");
		assertFound(found, "Prisma", TechnologyCategory.DATABASE, "prisma/schema.prisma");
		assertFound(found, "Jupyter", TechnologyCategory.DATA, "notebooks/a.ipynb");
	}

	// ---- languages and imports ------------------------------------------------------------------

	@Test
	void frameworkLanguagesAreTechnologies() {
		List<TechnologyIR> found = detect(new RepositoryIR("r", "u", List.of()),
				Map.of(Language.VUE, 4, Language.SVELTE, 1, Language.JAVA, 9));

		assertFound(found, "Vue", TechnologyCategory.FRONTEND_FRAMEWORK, "*.vue files");
		assertFound(found, "Svelte", TechnologyCategory.FRONTEND_FRAMEWORK, "*.svelte files");
		assertThat(named(found, "Java")).isNull();
	}

	@Test
	void importsRevealLibrariesInRepositoriesWithoutManifests() {
		RepositoryIR repository = new RepositoryIR("r", "u", List.of(
				file("analysis.ipynb", Language.JUPYTER, ImportIR.module("torch", null), ImportIR.module("sklearn.model_selection", null),
						ImportIR.module("pandas", "pd")),
				file("web/app.tsx", Language.TYPESCRIPT, ImportIR.symbol("react", "default", "React"),
						ImportIR.symbol("@angular/core", "Component"), ImportIR.symbol("./local", "x"), ImportIR.module("@/alias", null)),
				file("Main.java", Language.JAVA, ImportIR.symbol("org.springframework.boot.autoconfigure", "SpringBootApplication"),
						ImportIR.symbol("jakarta.persistence", "Entity")),
				file("App.php", Language.PHP, ImportIR.symbol("Illuminate.Support", "Str")),
				file("main.go", Language.GO, ImportIR.module("github.com/gin-gonic/gin", "gin")),
				file("lib.rs", Language.RUST, ImportIR.symbol("tokio.sync", "Mutex"), ImportIR.symbol("anchor_lang.prelude", "*"))));

		List<TechnologyIR> found = detect(repository, Map.of());

		assertFound(found, "PyTorch", TechnologyCategory.AI_ML, "imports in analysis.ipynb");
		assertFound(found, "scikit-learn", TechnologyCategory.AI_ML, "imports in analysis.ipynb");
		assertFound(found, "pandas", TechnologyCategory.DATA, "imports in analysis.ipynb");
		assertFound(found, "React", TechnologyCategory.FRONTEND_FRAMEWORK, "imports in web/app.tsx");
		assertFound(found, "Angular", TechnologyCategory.FRONTEND_FRAMEWORK, "imports in web/app.tsx");
		assertFound(found, "Spring Boot", TechnologyCategory.BACKEND_FRAMEWORK, "imports in Main.java");
		assertFound(found, "JPA", TechnologyCategory.DATABASE, "imports in Main.java");
		assertFound(found, "Laravel", TechnologyCategory.BACKEND_FRAMEWORK, "imports in App.php");
		assertFound(found, "Gin", TechnologyCategory.BACKEND_FRAMEWORK, "imports in main.go");
		assertFound(found, "Tokio", TechnologyCategory.LIBRARY, "imports in lib.rs");
		assertFound(found, "Anchor", TechnologyCategory.BLOCKCHAIN, "imports in lib.rs");
	}

	private static FileIR file(String path, Language language, ImportIR... imports) {
		return new FileIR(path, language, "", List.of(imports), List.of(), List.of());
	}

	// ---- result shape ---------------------------------------------------------------------------

	@Test
	void reportsEachTechnologyOnceWithTheStrongestEvidenceFirstFoundAndSorted() throws IOException {
		write("package.json", "{\"dependencies\": {\"react\": \"18\", \"react-dom\": \"18\", \"express\": \"4\"}}");
		RepositoryIR repository = new RepositoryIR("r", "u",
				List.of(file("src/a.js", Language.JAVASCRIPT, ImportIR.symbol("react", "default", "React"))));

		List<TechnologyIR> found = detect(repository, Map.of());

		assertThat(found.stream().filter(t -> t.name().equals("React"))).hasSize(1);
		assertThat(named(found, "React").evidence()).isEqualTo("package.json");
		assertThat(found).isSortedAccordingTo(java.util.Comparator.comparing(TechnologyIR::category)
				.thenComparing(TechnologyIR::name));
	}
}
