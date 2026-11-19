package com.abhiraj.archyra.ai.chat;

import static com.abhiraj.archyra.ai.FakeProviders.GEMINI;
import static com.abhiraj.archyra.ai.FakeProviders.unescape;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ai.ChatIntegrationSupport;
import com.jayway.jsonpath.JsonPath;

/**
 * "What is this project about?" over a real analysis (real pipeline, Neo4j, pgvector, the real model) with a fake provider
 * that is shown the prompt: the README, the manifest and the project facts reach it, the answer cites them, and every source is
 * verified in Neo4j like any other.
 */
class OverviewChatIntegrationTest extends ChatIntegrationSupport {

	private static final String OVERVIEW = "What is this project about?";
	private static final Pattern README = Pattern.compile("(?m)^\\[(\\d+)\\] REPOSITORY \\S+ README\\.md:\\d+-\\d+");
	private static final Pattern WEB_MODULE = Pattern.compile("(?m)^\\[(\\d+)\\] FACT: Module com\\.example\\.shop\\.web holds");

	private static int number(Pattern pattern, String prompt) {
		Matcher matcher = pattern.matcher(prompt);
		assertThat(matcher.find()).as(pattern + " in the prompt").isTrue();
		return Integer.parseInt(matcher.group(1));
	}

	private Ready docsShop() throws Exception {
		return ready(newUser(), SHOP_DOCS);
	}

	/** An answer that cites the README and the web module's fact, as a model that read the prompt would. */
	private static String citingAnswer(String requestBody) {
		String prompt = unescape(requestBody);
		return "Sample Shop takes orders over HTTP [" + number(README, prompt) + "]. The web module receives the requests [" + number(WEB_MODULE, prompt) + "].";
	}

	private List<String> sourceTypes(String body) {
		return JsonPath.read(body, "$.sources[*].type");
	}

	@Test
	void anOverviewQuestionIsAnsweredFromTheReadmeTheManifestAndTheProjectFacts() throws Exception {
		Ready shop = docsShop();
		GEMINI.answerWith(OverviewChatIntegrationTest::citingAnswer);

		String body = ask(shop.user(), shop.analysisId(), OVERVIEW, null);

		// what the model was shown
		String prompt = unescape(GEMINI.lastRequest().getBodyAsString());
		assertThat(prompt).contains("Sample Shop is a tiny order service. It takes orders over HTTP")
				.contains("name: Sample Shop").contains("description: A tiny order service used as a test fixture") // the manifest
				.contains("FACT: This repository is sample-shop-docs").contains("Languages by number of files: Java")
				.contains("FACT: Module com.example.shop.web holds").contains("is depended on by")
				.contains("4 to 8 sentences") // the overview rule of the system prompt
				.doesNotContain("travis").doesNotContain("shields.io").doesNotContain("<h1").doesNotContain("architecture.png");
		assertThat(prompt).containsPattern("(?m)^\\[1\\] FACT: This repository is "); // the identity fact comes first
		// the answer and its sources: the repository node (its excerpt is the README text) and a module
		assertThat((String) read(body, "$.answer")).contains("takes orders over HTTP [1]").contains("web module");
		List<String> types = sourceTypes(body);
		assertThat(types).contains("REPOSITORY", "MODULE");
		List<String> ids = JsonPath.read(body, "$.sources[*].id");
		assertThat(ids).contains("module:com.example.shop.web").anyMatch(id -> id.startsWith("repository:"));
		List<String> repositoryExcerpts = JsonPath.read(body, "$.sources[?(@.type=='REPOSITORY')].excerpt");
		assertThat(repositoryExcerpts.getFirst()).contains("Sample Shop is a tiny order service");
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(1);
	}

	@Test
	void anOldIndexWithoutAReadmeStillAnswersFromTheProjectFacts() throws Exception {
		Ready shop = docsShop();
		jdbc.update("DELETE FROM code_chunks WHERE analysis_id = ? AND kind IN ('README', 'MANIFEST')", shop.analysisId());
		GEMINI.answerWith(prompt -> "A small shop [1]. The web module [" + number(WEB_MODULE, unescape(prompt)) + "].");

		String body = ask(shop.user(), shop.analysisId(), OVERVIEW, null);

		String prompt = unescape(GEMINI.lastRequest().getBodyAsString());
		assertThat(prompt).doesNotContain("Sample Shop is a tiny order service").contains("FACT: This repository is sample-shop-docs");
		assertThat(sourceTypes(body)).contains("REPOSITORY", "MODULE");
	}

	@Test
	void everySourceOfAnOverviewIsVerifiedInNeo4jLikeAnyOther() throws Exception {
		Ready shop = docsShop();
		GEMINI.answerWith(OverviewChatIntegrationTest::citingAnswer);
		ask(shop.user(), shop.analysisId(), OVERVIEW, null); // builds and caches the pack, which names the web module
		removeNode(shop.analysisId(), "module:com.example.shop.web");

		String body = ask(shop.user(), shop.analysisId(), "Give me an overview of this repository", null);

		List<String> ids = JsonPath.read(body, "$.sources[*].id");
		assertThat(ids).doesNotContain("module:com.example.shop.web").anyMatch(id -> id.startsWith("repository:"));
		assertThat((String) read(body, "$.answer")).doesNotContain("[2]").contains("takes orders over HTTP [1]"); // the marker of a vanished node is dropped too
	}

	@Test
	void aSpecificQuestionIsUnaffectedExceptForTheUnnumberedPreamble() throws Exception {
		Ready shop = docsShop();

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		String prompt = unescape(GEMINI.lastRequest().getBodyAsString());
		assertThat(prompt).contains("Repository summary (background only, not a numbered source): This repository is sample-shop-docs: Java")
				.doesNotContain("FACT: This repository is").doesNotContain("Module com.example.shop.web holds"); // no project pack for a specific question
		assertThat(prompt).contains("DefaultOrderService"); // ordinary retrieval, with its name matches
		assertThat((List<?>) read(body, "$.sources")).isEmpty(); // the default fake answer cites nothing
	}

	@Test
	void anOverviewAnswerIsNeverReusedForAnotherQuestionAndTheSameOverviewIsFree() throws Exception {
		Ready shop = docsShop();
		GEMINI.answerWith(OverviewChatIntegrationTest::citingAnswer);

		ask(shop.user(), shop.analysisId(), OVERVIEW, null);
		int calls = GEMINI.requestCount();
		String again = ask(shop.user(), shop.analysisId(), "  what is this PROJECT about?  ", null);

		assertThat((boolean) read(again, "$.cached")).isTrue();
		assertThat(GEMINI.requestCount()).isEqualTo(calls);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(1);
		String specific = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);
		assertThat((boolean) read(specific, "$.cached")).isFalse();
	}
}
