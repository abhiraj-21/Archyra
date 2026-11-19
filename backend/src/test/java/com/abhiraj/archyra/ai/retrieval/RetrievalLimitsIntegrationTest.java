package com.abhiraj.archyra.ai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.abhiraj.archyra.ai.AiIntegrationSupport;
import com.abhiraj.archyra.user.User;

/** The retrieval limits are configuration, and a cut relationship list says that it was cut. */
@TestPropertySource(properties = { "archyra.ai.retrieval.neighbors-per-node=1", "archyra.ai.retrieval.name-lookup-max-nodes=1" })
class RetrievalLimitsIntegrationTest extends AiIntegrationSupport {

	@Autowired
	RetrievalService retrieval;

	@Test
	void aNodeWithMoreRelationshipsThanTheLimitListsThemAndSaysThereAreMore() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, id, "READY");

		List<ContextItem> items = retrieval.retrieve(RetrievalRequest.of(id, "What classes depend on OrderService?")).ranked();

		List<FactItem> facts = items.stream().filter(FactItem.class::isInstance).map(FactItem.class::cast).toList();
		assertThat(facts).filteredOn(fact -> !fact.isSummary()).filteredOn(fact -> fact.nodeIds().contains("interface:com.example.shop.service.OrderService"))
				.hasSize(1);
		assertThat(facts).filteredOn(FactItem::isSummary).extracting(FactItem::text)
				.containsExactly("OrderService has more relationships than are listed here");
	}

	@Test
	void onlyTheConfiguredNumberOfNamesIsLookedUp() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, id, "READY");

		List<ContextItem> items = retrieval.retrieve(RetrievalRequest.of(id, "Compare OrderController with OrderService and BaseEntity")).ranked();

		assertThat(items.stream().filter(CodeItem.class::isInstance).map(CodeItem.class::cast)
				.filter(item -> item.priority() == Priority.NAME_MATCH).map(CodeItem::nodeId)
				.filter(node -> node.startsWith("class:") || node.startsWith("interface:")).distinct()).hasSize(1);
	}
}
