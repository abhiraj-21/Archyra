package com.abhiraj.archyra.ai.chat;

import static com.abhiraj.archyra.ai.FakeProviders.GEMINI;
import static com.abhiraj.archyra.ai.FakeProviders.GROQ;
import static com.abhiraj.archyra.ai.FakeProviders.itemCount;
import static com.abhiraj.archyra.ai.FakeProviders.unescape;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.abhiraj.archyra.ai.ChatIntegrationSupport;

/**
 * The context budget is the chosen provider's: Groq-class budgets are small, so an overview prompt for such a provider
 * truncates the README first, then drops modules, and never the identity line (and never the preamble, which comes off the
 * budget first).
 */
@TestPropertySource(properties = { "archyra.ai.providers.gemini.api-key=", "archyra.ai.providers.groq.max-context-tokens=600" })
class OverviewSmallBudgetIntegrationTest extends ChatIntegrationSupport {

	@Test
	void aSmallProvidersOverviewKeepsTheIdentityLineAndFitsItsBudget() throws Exception {
		Ready docs = ready(newUser(), SHOP_DOCS);

		ask(docs.user(), docs.analysisId(), "What is this project about?", null);

		String prompt = unescape(GROQ.lastRequest().getBodyAsString());
		assertThat(GEMINI.requestCount()).isZero();
		assertThat(prompt).containsPattern("(?m)^\\[1\\] FACT: This repository is sample-shop-docs"); // never dropped
		assertThat(prompt).contains("Repository summary (background only, not a numbered source)");
		// the facts fit, so the README is what gave: cut or absent, never the whole of it plus every module
		String context = prompt.substring(prompt.indexOf("<repository_context>"), prompt.indexOf("</repository_context>"));
		assertThat(context.length() / 4.0 * 1.2).as("the context is within the provider's 600-token budget").isLessThan(600 + itemCount(prompt) * 3);
		assertThat(context).doesNotContain("The web module receives requests, the service module places orders");
	}

	@Test
	void aSpecificQuestionStillGetsThePreambleOnTheSmallBudget() throws Exception {
		Ready docs = ready(newUser(), SHOP_DOCS);

		ask(docs.user(), docs.analysisId(), PLACE_QUESTION, null);

		String prompt = unescape(GROQ.lastRequest().getBodyAsString());
		assertThat(prompt).contains("Repository summary (background only, not a numbered source): This repository is sample-shop-docs");
		assertThat(itemCount(prompt)).isPositive();
	}
}
