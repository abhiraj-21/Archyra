package com.abhiraj.archyra.ai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.NodeType;

class RetrievedContextTest {

	private static CodeItem code(String name, int chars, Priority priority) {
		return new CodeItem(priority, "method:" + name, NodeType.METHOD, name, "A.java", 1, 2, "x".repeat(chars), 0);
	}

	private static FactItem fact(String text) {
		return new FactItem(text, "class:A", null, null);
	}

	@Test
	void fillsInRankOrderUntilTheBudgetIsUsed() {
		CodeItem first = code("first", 400, Priority.NAME_MATCH);
		CodeItem second = code("second", 400, Priority.VECTOR);
		CodeItem third = code("third", 400, Priority.NEIGHBOR);
		RetrievedContext context = new RetrievedContext(List.of(first, second, third));
		int budget = first.estimatedTokens() + second.estimatedTokens();

		assertThat(context.within(budget)).containsExactly(first, second);
		assertThat(RetrievedContext.tokensOf(context.within(budget))).isLessThanOrEqualTo(budget);
	}

	@Test
	void anItemThatDoesNotFitIsSkippedButSmallerLaterOnesAreStillTaken() {
		CodeItem big = code("big", 4000, Priority.NAME_MATCH);
		FactItem small = fact("A DEPENDS_ON B");
		RetrievedContext context = new RetrievedContext(List.of(big, small));

		assertThat(context.within(big.estimatedTokens() - 1)).containsExactly(small);
	}

	@Test
	void aSmallerBudgetNeverYieldsMoreThanALargerOne() {
		RetrievedContext context = new RetrievedContext(List.of(code("a", 2000, Priority.NAME_MATCH),
				fact("A DEPENDS_ON B"), code("b", 2000, Priority.VECTOR), code("c", 2000, Priority.NEIGHBOR),
				code("d", 2000, Priority.STICKY)));

		int small = RetrievedContext.tokensOf(context.within(900));
		int large = RetrievedContext.tokensOf(context.within(2600));

		assertThat(small).isLessThanOrEqualTo(900);
		assertThat(large).isLessThanOrEqualTo(2600);
		assertThat(small).isLessThan(large);
		assertThat(context.within(0)).isEmpty();
	}

	@Test
	void theEstimateErrsOnTheHighSideOfOneTokenPerFourCharacters() {
		assertThat(ContextItem.estimateTokens("x".repeat(400))).isGreaterThan(100);
	}

	@Test
	void aFactStandsForBothEndsAndASummaryForOne() {
		FactItem edge = FactItem.relationship("class:A", "A", com.abhiraj.archyra.ir.RelationshipType.DEPENDS_ON, "class:B", "B");

		assertThat(edge.text()).isEqualTo("A DEPENDS_ON B");
		assertThat(edge.nodeIds()).containsExactly("class:A", "class:B");
		assertThat(edge.isSummary()).isFalse();
		FactItem summary = FactItem.partialList("class:A", "A");
		assertThat(summary.nodeIds()).containsExactly("class:A");
		assertThat(summary.isSummary()).isTrue();
	}

	// ---- an overview context: the project pack, the README and the manifest ----------------------

	private static ProjectItem project(ProjectItem.Role role, String text) {
		return new ProjectItem(role, text, List.of("repository:demo"));
	}

	private static CodeItem readme(int chars) {
		return new CodeItem(Priority.PROJECT_DOCS, "repository:demo", NodeType.REPOSITORY, "demo", "README.md", 1, 40, ("A line about the project.\n").repeat(chars / 26), 0);
	}

	private static RetrievedContext overview(CodeItem readme) {
		java.util.ArrayList<ContextItem> items = new java.util.ArrayList<>();
		items.add(project(ProjectItem.Role.IDENTITY, "This repository is demo. Languages by number of files: Java 12."));
		items.add(readme);
		items.add(project(ProjectItem.Role.TECHNOLOGIES, "Detected technologies: Backend framework: Spring Boot."));
		items.add(project(ProjectItem.Role.COUNTS, "The repository has 12 files in all, 9 classes and 30 methods or functions of its own code."));
		for (int i = 1; i <= 3; i++) {
			items.add(new ProjectItem(ProjectItem.Role.CLASS, "Class" + i + " is depended on by " + i + " other classes and interfaces of the repository.", List.of("class:Class" + i)));
		}
		for (int i = 1; i <= 4; i++) {
			items.add(new ProjectItem(ProjectItem.Role.MODULE, "Module m" + i + " holds " + i + " files and " + i + " classes and interfaces.", List.of("module:m" + i)));
		}
		items.add(code("vector", 200, Priority.VECTOR));
		return new RetrievedContext(items, true);
	}

	private static boolean has(List<ContextItem> items, ProjectItem.Role role) {
		return items.stream().anyMatch(item -> item instanceof ProjectItem project && project.role() == role);
	}

	@Test
	void anOverviewComesBackAsIdentityThenTheReadmeThenTheFactsThenTheHits() {
		List<ContextItem> items = overview(readme(1000)).within(10_000);

		assertThat(items.getFirst()).isInstanceOfSatisfying(ProjectItem.class, item -> assertThat(item.role()).isEqualTo(ProjectItem.Role.IDENTITY));
		assertThat(items.get(1)).isInstanceOf(CodeItem.class);
		assertThat(((CodeItem) items.get(1)).priority()).isEqualTo(Priority.PROJECT_DOCS);
		assertThat(items.getLast()).isInstanceOf(CodeItem.class);
		assertThat(((CodeItem) items.getLast()).priority()).isEqualTo(Priority.VECTOR);
		assertThat(items).hasSize(12);
	}

	@Test
	void aSmallerBudgetTruncatesTheReadmeFirstThenDropsModulesThenClassesAndNeverTheIdentity() {
		RetrievedContext context = overview(readme(5000));
		int everything = RetrievedContext.tokensOf(context.within(100_000));

		// 1. the README shrinks (cut, with a marker) while every fact stays
		List<ContextItem> readmeCut = context.within(everything - 700);
		CodeItem cut = (CodeItem) readmeCut.stream().filter(item -> item.priority() == Priority.PROJECT_DOCS).findFirst().orElseThrow();
		assertThat(cut.text()).endsWith("... [truncated]").hasSizeLessThan(5000);
		assertThat(readmeCut.stream().filter(ProjectItem.class::isInstance)).hasSize(10);
		assertThat(RetrievedContext.tokensOf(readmeCut)).isLessThanOrEqualTo(everything - 700);

		// 2. then it is gone and the modules go, the last of them first
		int facts = RetrievedContext.tokensOf(context.within(100_000).stream().filter(ProjectItem.class::isInstance).toList());
		List<ContextItem> noReadme = context.within(facts - 30);
		assertThat(noReadme.stream().anyMatch(item -> item.priority() == Priority.PROJECT_DOCS)).isFalse();
		assertThat(noReadme.stream().filter(item -> item instanceof ProjectItem p && p.role() == ProjectItem.Role.MODULE).count()).isLessThan(4);
		assertThat(has(noReadme, ProjectItem.Role.CLASS)).isTrue();

		// 3. the identity fact survives any budget, even zero
		for (int budget : new int[] { 0, 1, 20, 60 }) {
			List<ContextItem> tiny = context.within(budget);
			assertThat(tiny).isNotEmpty();
			assertThat(tiny.getFirst()).isInstanceOfSatisfying(ProjectItem.class, item -> assertThat(item.role()).isEqualTo(ProjectItem.Role.IDENTITY));
		}
	}

	@Test
	void aSmallerBudgetNeverKeepsMoreOfAnOverviewThanALargerOne() {
		RetrievedContext context = overview(readme(5000));
		int previous = 0;
		for (int budget = 0; budget <= 3000; budget += 150) {
			int tokens = RetrievedContext.tokensOf(context.within(budget));
			assertThat(tokens).as("budget " + budget).isGreaterThanOrEqualTo(previous - 5);
			previous = tokens;
		}
	}

	@Test
	void aReadmeThatFitsIsNotCutAndOneWithNoRoomLeftIsLeftOut() {
		CodeItem small = readme(300);
		assertThat(small.truncatedTo(small.estimatedTokens())).isSameAs(small);
		assertThat(readme(5000).truncatedTo(40)).isNull();
	}

	@Test
	void anOrdinaryContextIsFittedAsBefore() {
		RetrievedContext context = new RetrievedContext(List.of(code("a", 400, Priority.NAME_MATCH), fact("A DEPENDS_ON B")));

		assertThat(context.usable()).isTrue();
		assertThat(new RetrievedContext(List.of()).usable()).isFalse();
		assertThat(new RetrievedContext(List.of(fact("x")), false).usable()).isFalse();
		assertThat(context.within(100_000)).hasSize(2);
	}
}
