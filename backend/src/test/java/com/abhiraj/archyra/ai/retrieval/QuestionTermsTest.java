package com.abhiraj.archyra.ai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class QuestionTermsTest {

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			What classes depend on PaymentService?                       | PaymentService
			What depends on `PaymentService`?                            | PaymentService
			Explain Order                                                | Order
			How does `create()` work?                                    | create
			What does `OrderService#place(OrderRequest)` do?             | OrderService#place,OrderService,place
			explain clean_data and MAX_SIZE                              | clean_data,MAX_SIZE
			where is getUser called from                                 | getUser
			What is in cleaner.py?                                       | cleaner.py
			what does src/main/java/com/foo/App.java do                  | src/main/java/com/foo/App.java,App.java
			Who implements com.foo.UserService?                          | com.foo.UserService,UserService
			How does UserController call userService.createUser?         | UserController,userService.createUser,userService,createUser
			""")
	void identifiersAreFoundInTheOrderOfTheQuestion(String question, String expected) {
		assertThat(QuestionTerms.extract(question)).containsExactly(expected.trim().split(","));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			How does authentication work in this repository?
			Where does a request to /users go?
			What is the role of this module, e.g. the web layer, i.e. controllers?
			Tell me about the architecture
			""")
	void plainEnglishHasNoCandidates(String question) {
		assertThat(QuestionTerms.extract(question)).isEmpty();
	}

	@Test
	void nullAndBlankHaveNoCandidates() {
		assertThat(QuestionTerms.extract(null)).isEmpty();
		assertThat(QuestionTerms.extract("   ")).isEmpty();
	}

	@Test
	void repeatedNamesAppearOnce() {
		assertThat(QuestionTerms.extract("PaymentService and PaymentService again, and `PaymentService`"))
				.containsExactly("PaymentService");
	}

	@Test
	void theNumberOfCandidatesIsBounded() {
		String question = IntStream.range(0, 100).mapToObj(i -> "ServiceNumber" + i + "Impl").reduce("", (a, b) -> a + " " + b);

		assertThat(QuestionTerms.extract(question)).hasSize(QuestionTerms.MAX_TERMS);
	}

	@Test
	void aBacktickedTermWithSpacesIsNotAName() {
		assertThat(QuestionTerms.extract("what does `some phrase here` mean")).isEmpty();
	}
}
