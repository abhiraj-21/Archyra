package com.abhiraj.archyra.ai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The deterministic rules that tell a question about the whole project from a specific one. */
class QuestionIntentsTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"What is this project about?",
			"what is this project",
			"What's this project about?",
			"what is this repo about",
			"What is this repository?",
			"What does this repository do?",
			"What does this project do?",
			"What is this codebase about?",
			"What does this codebase do",
			"what does this app do?",
			"What is this application?",
			"What is this app for?",
			"What is it about?",
			"what does this do?",
			"Summarize this project",
			"Summarise the repo",
			"Give me an overview of this project",
			"give me a summary of this repository please",
			"Can you give me a high-level overview of the codebase?",
			"Could you explain this project to me?",
			"Describe this codebase",
			"Tell me about this project",
			"Project overview",
			"What is the purpose of the project?",
			"purpose of this repository",
			"What is the tech stack?",
			"Tech stack",
			"What tech stack does this use?",
			"Which languages does it use?",
			"What languages does this project use?",
			"Which frameworks are used?",
			"What technologies does it use",
			"What is it written in?",
			"How is the project structured?",
			"How is this repo organised?",
			"How is it structured",
			"What is the structure of this codebase?",
			"High-level architecture",
			"What is the high level architecture of this project?",
			"Please summarize the repository briefly" })
	void overviewPhrasings(String question) {
		assertThat(QuestionIntents.classify(question)).as(question).isEqualTo(QuestionIntent.OVERVIEW);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"What is this class about?",
			"What does UserService do?",
			"What does the UserService class do in this project?",
			"What is the weather",
			"Explain the project plan for Q4",
			"What is a project manager?",
			"How do I create a new project in Eclipse?",
			"What is the repository pattern?",
			"What does this method do?",
			"What does this file do?",
			"What is this function for?",
			"What does the app module do?",
			"What is the purpose of the UserService class?",
			"Explain how authentication works",
			"How does the repository layer work?",
			"What languages does Python support?",
			"What is the tech stack of Netflix?",
			"Write me a poem",
			"How do I center a div?",
			"Fix this bug",
			"Refactor OrderService",
			"Who wrote this project?",
			"What is the testing strategy of this project?",
			"How do I structure a project?",
			"What is this?",
			"What is the purpose of life?",
			"Which classes depend on `Order`?",
			"What is `com.example.shop.web` about?",
			"What does src/main/java/App.java do?",
			"What does order_service.py do?",
			"Explain OrderController.create",
			"Where is the project configured?",
			"How many files does the project have?",
			"Is this project well tested?",
			"What is a codebase?",
			"What does the OrderController in this repository do?",
			"summarize the OrderService class",
			"what is this project's license",
			"Give me an overview of the Order flow",
			"hello" })
	void specificQuestions(String question) {
		assertThat(QuestionIntents.classify(question)).as(question).isEqualTo(QuestionIntent.SPECIFIC);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "?", "\n" })
	void nothingIsSpecific(String question) {
		assertThat(QuestionIntents.classify(question)).isEqualTo(QuestionIntent.SPECIFIC);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"What is this project about? What does UserService do?",
			"What is the OrderService project about?",
			"Summarize this project: OrderController and `place`" })
	void aQuestionThatNamesCodeIsSpecificEvenWithAnOverviewPhrase(String question) {
		assertThat(QuestionIntents.classify(question)).as(question).isEqualTo(QuestionIntent.SPECIFIC);
	}
}
