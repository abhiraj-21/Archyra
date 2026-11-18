package com.abhiraj.archyra.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The rule table of {@link TestPathClassifier}, row by row: every rule, every language, every negative case. */
class TestPathClassifierTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(delimiter = '|', textBlock = """
			# rule 1: main source sets are never tests
			src/main/java/com/x/test/Util.java|false
			src/main/java/com/x/tests/UtilTest.java|false
			backend/src/main/kotlin/app/TestHelper.kt|false
			src/main/resources/spec/schema.json|false
			module/src/main/java/FooTest.java|false
			# rule 2: test source sets
			src/test/java/com/x/FooTest.java|true
			src/test/java/com/x/Helper.java|true
			backend/src/test/resources/data.json|true
			src/integrationTest/java/com/x/Flow.java|true
			src/functionalTest/kotlin/Flow.kt|true
			src/integrationTests/java/Flow.java|true
			src/testFixtures/java/com/x/Builders.java|true
			src/contest/App.java|false
			src/latest/App.java|false
			src/Test/App.java|false
			# rule 3: test directories at any depth
			tests/helpers.py|true
			test/helpers.py|true
			app/__tests__/button.js|true
			app/__test__/button.js|true
			lib/spec/models/user.rb|true
			a/b/c/test/d/e.go|true
			testing/helpers.py|false
			specs/a.rb|false
			docs/specs/a.md|false
			latest/app.py|false
			Tests/helpers.py|false
			# rule 4: C# test projects
			Shop.Tests/OrderServiceTests.cs|true
			Shop.Test/Thing.cs|true
			Shop.UnitTests/Thing.cs|true
			Shop.IntegrationTests/Thing.cs|true
			src/Shop.Tests/Thing.cs|true
			Shop.Contests/Thing.cs|false
			Shop/Thing.cs|false
			# rule 5: Java and Kotlin file names
			src/com/x/FooTest.java|true
			src/com/x/FooTests.java|true
			src/com/x/FooIT.java|true
			src/com/x/FooTest.kt|true
			src/com/x/TestFoo.java|true
			src/com/x/TestFoo.kt|true
			src/com/x/Foo.java|false
			src/com/x/Testing.txt|false
			src/com/x/FooTest.scala|false
			# Python
			pkg/test_foo.py|true
			pkg/foo_test.py|true
			pkg/conftest.py|true
			pkg/foo.py|false
			pkg/latest.py|false
			pkg/testfoo.py|false
			# Go
			pkg/foo_test.go|true
			pkg/foo.go|false
			# JavaScript, TypeScript, Vue, Svelte
			src/a.test.js|true
			src/a.test.jsx|true
			src/a.test.ts|true
			src/a.test.tsx|true
			src/a.test.mjs|true
			src/a.test.cjs|true
			src/a.test.vue|true
			src/a.test.svelte|true
			src/a.spec.js|true
			src/a.spec.tsx|true
			src/a.spec.vue|true
			src/a.js|false
			src/a.tests.js|false
			src/a.test.json|false
			src/atest.js|false
			# Ruby
			app/foo_spec.rb|true
			app/foo_test.rb|true
			app/foo.rb|false
			# C#
			Shop/OrderTest.cs|true
			Shop/OrderTests.cs|true
			Shop/Order.cs|false
			# PHP
			app/OrderTest.php|true
			app/Order.php|false
			app/OrderTests.php|false
			# Solidity
			contracts/Token.t.sol|true
			contracts/Token.test.sol|true
			contracts/Token.sol|false
			# Rust (inline #[cfg(test)] is a documented limitation)
			src/foo_test.rs|true
			src/foo.rs|false
			src/lib.rs|false
			# names are case-sensitive
			src/foo_TEST.go|false
			src/FOOTEST.JAVA|false
			# a bare file name
			FooTest.java|true
			Foo.java|false
			README.md|false
			""")
	void rulesTable(String path, boolean expected) {
		assertThat(TestPathClassifier.isTestPath(path)).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t", "/", "//" })
	void aNullBlankOrDegeneratePathIsNotATestAndNeverThrows(String path) {
		assertThat(TestPathClassifier.isTestPath(path)).isFalse();
	}

	@Test
	void theMainRuleBeatsEveryRuleAfterIt() {
		assertThat(TestPathClassifier.isTestPath("src/main/java/test/spec/__tests__/FooTest.java")).isFalse();
	}

	@Test
	void aModuleIsATestOnlyIfItHasFilesAndAllOfThemAreTests() {
		assertThat(TestPathClassifier.isTestModule(List.of("src/test/A.java", "src/test/B.java"))).isTrue();
		assertThat(TestPathClassifier.isTestModule(List.of("src/test/A.java", "src/main/B.java"))).isFalse();
		assertThat(TestPathClassifier.isTestModule(List.of("src/main/B.java"))).isFalse();
		assertThat(TestPathClassifier.isTestModule(List.of())).isFalse();
		assertThat(TestPathClassifier.isTestModule(null)).isFalse();
		assertThat(TestPathClassifier.isTestModule(Arrays.asList("src/test/A.java", null))).isTrue();
		assertThat(TestPathClassifier.isTestModule(Arrays.asList((String) null))).isFalse();
	}
}
