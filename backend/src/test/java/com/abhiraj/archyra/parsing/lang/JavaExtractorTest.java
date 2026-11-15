package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.CallIR;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;
import com.abhiraj.archyra.parsing.NativeLibraries;

/**
 * Runs the real Tree-sitter parser, so it needs the native libraries from {@code scripts/build-native.sh}.
 */
class JavaExtractorTest {

	private static final Path SAMPLE = Path.of("src/test/resources/fixtures/sample-repo/src/main/java/com/example/shop");

	private final JavaExtractor extractor = new JavaExtractor();

	@BeforeAll
	static void configureNativeLibraries() {
		NativeLibraries.configure(Path.of("native"));
	}

	private FileIR extract(String relativePath) {
		return extractor.extract(new SourceFile(relativePath, SAMPLE.resolve(relativePath), Language.JAVA));
	}

	@Test
	void supportsOnlyJava() {
		assertThat(extractor.supports(Language.JAVA)).isTrue();
		assertThat(extractor.supports(Language.PYTHON)).isFalse();
	}

	@Test
	void extractsPackageImportsAndClassWithHeritage() {
		FileIR file = extract("model/Order.java");

		assertThat(file.packageName()).isEqualTo("com.example.shop.model");
		assertThat(file.moduleName()).isEqualTo("com.example.shop.model");
		assertThat(file.imports()).extracting("module", "name")
				.containsExactly(org.assertj.core.groups.Tuple.tuple("java.util", "ArrayList"),
						org.assertj.core.groups.Tuple.tuple("java.util", "List"));

		TypeIR order = file.types().getFirst();
		assertThat(order.kind()).isEqualTo(TypeKind.CLASS);
		assertThat(order.qualifiedName()).isEqualTo("com.example.shop.model.Order");
		assertThat(order.extendsTypes()).extracting("name").containsExactly("BaseEntity");
		assertThat(order.implementsTypes()).isEmpty();
	}

	@Test
	void extractsFieldsWithGenericTypeArguments() {
		TypeIR order = extract("model/Order.java").types().getFirst();

		assertThat(order.fields()).extracting("name").containsExactly("items", "status");
		assertThat(order.fields().getFirst().type().name()).isEqualTo("List");
		assertThat(order.fields().getFirst().type().typeArguments()).containsExactly("String");
	}

	@Test
	void extractsMethodsConstructorsAndNestedEnum() {
		TypeIR order = extract("model/Order.java").types().getFirst();

		assertThat(order.methods()).extracting("name").containsExactly("Order", "addItem", "getStatus");
		MethodIR constructor = order.methods().getFirst();
		assertThat(constructor.constructor()).isTrue();
		assertThat(constructor.parameters()).extracting("name").containsExactly("id");

		assertThat(order.nestedTypes()).hasSize(1);
		assertThat(order.nestedTypes().getFirst().kind()).isEqualTo(TypeKind.ENUM);
		assertThat(order.nestedTypes().getFirst().qualifiedName()).isEqualTo("com.example.shop.model.Order.Status");
	}

	@Test
	void extractsInterfaceMethodsAndImplementsClause() {
		TypeIR repository = extract("repository/OrderRepository.java").types().getFirst();
		assertThat(repository.kind()).isEqualTo(TypeKind.INTERFACE);
		assertThat(repository.methods()).extracting("name").containsExactly("save", "find");
		assertThat(repository.methods().getFirst().returnType().name()).isEqualTo("Order");

		TypeIR inMemory = extract("repository/InMemoryOrderRepository.java").types().getFirst();
		assertThat(inMemory.implementsTypes()).extracting("name").containsExactly("OrderRepository");
	}

	@Test
	void extractsRecordComponentsAsFields() {
		TypeIR request = extract("model/OrderRequest.java").types().getFirst();

		assertThat(request.kind()).isEqualTo(TypeKind.RECORD);
		assertThat(request.fields()).extracting("name").containsExactly("id", "item");
	}

	@Test
	void extractsCallsLocalsAndInstantiations() {
		TypeIR service = extract("service/DefaultOrderService.java").types().getFirst();
		MethodIR place = service.methods().stream().filter(m -> m.name().equals("place")).findFirst().orElseThrow();

		assertThat(place.locals()).extracting("name").containsExactly("order");
		assertThat(place.instantiatedTypes()).extracting("name").containsExactly("Order");
		assertThat(place.calls()).extracting(CallIR::name, CallIR::receiver, CallIR::argumentCount)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("id", "request", 0),
						org.assertj.core.groups.Tuple.tuple("addItem", "order", 1),
						org.assertj.core.groups.Tuple.tuple("item", "request", 0),
						org.assertj.core.groups.Tuple.tuple("save", "repository", 1));
	}

	@Test
	void recordsReceiverForThisFieldAndUnqualifiedCalls() {
		TypeIR controller = extract("web/OrderController.java").types().getFirst();
		MethodIR log = controller.methods().stream().filter(m -> m.name().equals("log")).findFirst().orElseThrow();
		MethodIR createDefault = controller.methods().stream()
				.filter(m -> m.name().equals("createDefault")).findFirst().orElseThrow();

		assertThat(log.calls()).extracting(CallIR::name, CallIR::receiver)
				.contains(org.assertj.core.groups.Tuple.tuple("print", null));
		assertThat(createDefault.calls()).extracting(CallIR::receiver).contains("this.orderService");
	}

	@Test
	void toleratesSyntaxErrorsAndStillExtractsWhatIsRecognisable() {
		FileIR file = extract("Broken.java");

		assertThat(file.types()).extracting(TypeIR::name).contains("Survivor");
	}

	@Test
	void reportsLineNumbersStartingAtOne() {
		TypeIR service = extract("service/OrderService.java").types().getFirst();

		assertThat(service.line()).isEqualTo(6);
	}
}
