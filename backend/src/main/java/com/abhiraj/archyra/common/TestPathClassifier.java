package com.abhiraj.archyra.common;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides from a repository-relative path whether a file is test code. Pure and stateless: path and file-name
 * conventions only (no annotation or framework detection), so it works on every stored analysis without a re-run.
 * Both {@code graph/} and {@code codebase/} need it, hence {@code common/}.
 *
 * <p>Rules, in this order, first match wins (the table below is the contract; {@code TestPathClassifierTest} runs
 * it row by row):
 * <ol>
 * <li>a {@code src/main/} directory pair: never a test (protects a package called {@code test} inside real code);</li>
 * <li>a test source set: {@code src/test/}, {@code src/<name>Test/}, {@code src/<name>Tests/},
 * {@code src/testFixtures/};</li>
 * <li>a directory named {@code test}, {@code tests}, {@code __tests__}, {@code __test__} or {@code spec}, at any
 * depth (exact, lower case);</li>
 * <li>a C# test project directory ending in {@code .Tests}, {@code .Test}, {@code .UnitTests} or
 * {@code .IntegrationTests};</li>
 * <li>a test file name (see {@link #TEST_FILE_NAMES});</li>
 * <li>otherwise not a test.</li>
 * </ol>
 *
 * <p>Known limitation: Rust unit tests written inline with {@code #[cfg(test)]} are not detected.
 */
public final class TestPathClassifier {

	/** Directory pairs {@code src/<second>} that mark a source set; the first is the main one (never a test). */
	private static final String SRC = "src";
	private static final String MAIN_SOURCE_SET = "main";
	private static final Set<String> TEST_SOURCE_SETS = Set.of("test", "testFixtures");
	/** {@code src/<name>Test} and {@code src/<name>Tests}, e.g. {@code integrationTest}, {@code functionalTests}. */
	private static final Pattern NAMED_TEST_SOURCE_SET = Pattern.compile(".+Tests?");

	private static final Set<String> TEST_DIRECTORIES = Set.of("test", "tests", "__tests__", "__test__", "spec");
	private static final List<String> TEST_PROJECT_SUFFIXES = List.of(".Tests", ".Test", ".UnitTests", ".IntegrationTests");

	private static final String JS_FAMILY = "(js|jsx|ts|tsx|mjs|cjs|vue|svelte)";
	/** File-name conventions, matched against the whole name and case-sensitive. */
	static final List<Pattern> TEST_FILE_NAMES = List.of(
			// Java, Kotlin
			Pattern.compile(".*Test\\.(java|kt)"), Pattern.compile(".*Tests\\.(java|kt)"),
			Pattern.compile(".*IT\\.(java|kt)"), Pattern.compile("Test.*\\.(java|kt)"),
			// Python
			Pattern.compile("test_.*\\.py"), Pattern.compile(".*_test\\.py"), Pattern.compile("conftest\\.py"),
			// Go
			Pattern.compile(".*_test\\.go"),
			// JavaScript, TypeScript, Vue, Svelte
			Pattern.compile(".*\\.test\\." + JS_FAMILY), Pattern.compile(".*\\.spec\\." + JS_FAMILY),
			// Ruby
			Pattern.compile(".*_spec\\.rb"), Pattern.compile(".*_test\\.rb"),
			// C#
			Pattern.compile(".*Tests?\\.cs"),
			// PHP
			Pattern.compile(".*Test\\.php"),
			// Solidity
			Pattern.compile(".*\\.t\\.sol"), Pattern.compile(".*\\.test\\.sol"),
			// Rust (inline #[cfg(test)] modules are not detected)
			Pattern.compile(".*_test\\.rs"));

	private TestPathClassifier() {
	}

	/**
	 * @param repoRelativePath the path as stored in the graph's {@code file} property: repository-relative, with
	 *                         {@code /} separators
	 * @return true for test code; false for anything else, including a null or blank path. Never throws.
	 */
	public static boolean isTestPath(String repoRelativePath) {
		if (repoRelativePath == null || repoRelativePath.isBlank()) {
			return false;
		}
		String[] segments = repoRelativePath.strip().split("/");
		if (segments.length == 0) {
			return false; // "/" and "//" split into nothing
		}
		int directories = segments.length - 1; // the last segment is the file name
		String fileName = segments[directories];

		// 1 and 2: source sets, looked at as directory pairs
		boolean mainSourceSet = false;
		boolean testSourceSet = false;
		for (int i = 0; i + 1 < directories; i++) {
			if (!SRC.equals(segments[i])) {
				continue;
			}
			String next = segments[i + 1];
			mainSourceSet |= MAIN_SOURCE_SET.equals(next);
			testSourceSet |= TEST_SOURCE_SETS.contains(next) || NAMED_TEST_SOURCE_SET.matcher(next).matches();
		}
		if (mainSourceSet) {
			return false; // rule 1 wins over everything below, a package named "test" inside main code included
		}
		if (testSourceSet) {
			return true;
		}

		// 3 and 4: directory names
		for (int i = 0; i < directories; i++) {
			String directory = segments[i];
			if (TEST_DIRECTORIES.contains(directory)
					|| TEST_PROJECT_SUFFIXES.stream().anyMatch(directory::endsWith)) {
				return true;
			}
		}

		// 5: file name
		return TEST_FILE_NAMES.stream().anyMatch(pattern -> pattern.matcher(fileName).matches());
	}

	/**
	 * A module is a test module only if it has at least one file and every one of them is a test file.
	 *
	 * @param filePaths the repository paths of the module's files (nulls are ignored)
	 */
	public static boolean isTestModule(List<String> filePaths) {
		List<String> files = filePaths == null ? List.of() : filePaths.stream().filter(path -> path != null).toList();
		return !files.isEmpty() && files.stream().allMatch(TestPathClassifier::isTestPath);
	}
}
