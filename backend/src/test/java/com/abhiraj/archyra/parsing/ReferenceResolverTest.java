package com.abhiraj.archyra.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.CallIR;
import com.abhiraj.archyra.ir.CodeGraphIR;
import com.abhiraj.archyra.ir.EdgeIR;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.ImportIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.NodeIR;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.RepositoryIR;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;
import com.abhiraj.archyra.ir.TypeRefIR;
import com.abhiraj.archyra.parsing.lang.NamespaceModuleResolver;
import com.abhiraj.archyra.ir.VariableIR;

/** Pure IR in, graph out: no parser and no Spring needed. */
class ReferenceResolverTest {

	private final ReferenceResolver resolver = new ReferenceResolver(List.of(new NamespaceModuleResolver()));

	// ---- IR builders ---------------------------------------------------------------------------

	private static TypeRefIR ref(String name, String... arguments) {
		return new TypeRefIR(name, List.of(arguments));
	}

	private static VariableIR var(String name, String type) {
		return new VariableIR(name, ref(type));
	}

	private static CallIR call(String receiver, String name, int arguments) {
		return new CallIR(name, receiver, arguments, 1);
	}

	private static MethodIR method(String name, List<VariableIR> parameters, List<VariableIR> locals, CallIR... calls) {
		return new MethodIR(name, false, 1, 1, parameters, null, locals, List.of(calls), List.of());
	}

	private static MethodIR method(String name, CallIR... calls) {
		return method(name, List.of(), List.of(), calls);
	}

	private static TypeIR type(TypeKind kind, String qualifiedName, List<TypeRefIR> extendsTypes,
			List<TypeRefIR> implementsTypes, List<VariableIR> fields, List<MethodIR> methods) {
		return new TypeIR(kind, qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1), qualifiedName, 1, 1,
				extendsTypes, implementsTypes, fields, methods, List.of());
	}

	private static TypeIR clazz(String qualifiedName, List<VariableIR> fields, MethodIR... methods) {
		return type(TypeKind.CLASS, qualifiedName, List.of(), List.of(), fields, List.of(methods));
	}

	private static FileIR file(String path, String packageName, List<ImportIR> imports, TypeIR... types) {
		return new FileIR(path, Language.JAVA, packageName, imports, List.of(types), List.of());
	}

	/** {@code import a.b.C;} */
	private static ImportIR imp(String name) {
		int dot = name.lastIndexOf('.');
		return ImportIR.symbol(dot < 0 ? "" : name.substring(0, dot), name.substring(dot + 1));
	}

	private CodeGraphIR resolve(FileIR... files) {
		return resolver.resolve(new RepositoryIR("demo", "https://github.com/o/demo", List.of(files)));
	}

	private static boolean hasEdge(CodeGraphIR graph, String source, RelationshipType type, String target) {
		return graph.edges().contains(new EdgeIR(source, target, type));
	}

	private static NodeIR node(CodeGraphIR graph, String id) {
		return graph.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
	}

	private static long count(CodeGraphIR graph, RelationshipType type) {
		return graph.edges().stream().filter(e -> e.type() == type).count();
	}

	// ---- containment ---------------------------------------------------------------------------

	@Test
	void buildsContainmentFromRepositoryDownToMethods() {
		CodeGraphIR graph = resolve(file("a/A.java", "p", List.of(), clazz("p.A", List.of(), method("run"))));

		assertThat(node(graph, "repository:demo").type()).isEqualTo(NodeType.REPOSITORY);
		assertThat(hasEdge(graph, "repository:demo", RelationshipType.CONTAINS, "module:p")).isTrue();
		assertThat(hasEdge(graph, "module:p", RelationshipType.CONTAINS, "file:a/A.java")).isTrue();
		assertThat(hasEdge(graph, "file:a/A.java", RelationshipType.CONTAINS, "class:p.A")).isTrue();
		assertThat(hasEdge(graph, "class:p.A", RelationshipType.CONTAINS, "method:p.A#run()")).isTrue();

		NodeIR classNode = node(graph, "class:p.A");
		assertThat(classNode.file()).isEqualTo("a/A.java");
		assertThat(classNode.external()).isFalse();
	}

	@Test
	void filesWithoutAPackageGoIntoTheDefaultModule() {
		CodeGraphIR graph = resolve(file("A.java", "", List.of(), clazz("A", List.of())));

		assertThat(node(graph, "module:(default)")).isNotNull();
	}

	@Test
	void interfacesBecomeInterfaceNodesAndEnumsAndRecordsBecomeClassNodes() {
		CodeGraphIR graph = resolve(file("p/X.java", "p", List.of(),
				type(TypeKind.INTERFACE, "p.I", List.of(), List.of(), List.of(), List.of()),
				type(TypeKind.ENUM, "p.E", List.of(), List.of(), List.of(), List.of()),
				type(TypeKind.RECORD, "p.R", List.of(), List.of(), List.of(), List.of())));

		assertThat(node(graph, "interface:p.I").type()).isEqualTo(NodeType.INTERFACE);
		assertThat(node(graph, "class:p.E").kind()).isEqualTo("ENUM");
		assertThat(node(graph, "class:p.R").kind()).isEqualTo("RECORD");
	}

	@Test
	void nestedTypesAreContainedInTheirEnclosingType() {
		TypeIR inner = clazz("p.Outer.Inner", List.of());
		TypeIR outer = new TypeIR(TypeKind.CLASS, "Outer", "p.Outer", 1, 1, List.of(), List.of(), List.of(), List.of(),
				List.of(inner));

		CodeGraphIR graph = resolve(file("p/Outer.java", "p", List.of(), outer));

		assertThat(hasEdge(graph, "class:p.Outer", RelationshipType.CONTAINS, "class:p.Outer.Inner")).isTrue();
	}

	@Test
	void overloadsGetDistinctMethodNodes() {
		MethodIR one = method("go", List.of(var("a", "String")), List.of());
		MethodIR two = method("go", List.of(var("a", "int")), List.of());

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), clazz("p.A", List.of(), one, two)));

		assertThat(node(graph, "method:p.A#go(String)")).isNotNull();
		assertThat(node(graph, "method:p.A#go(int)")).isNotNull();
	}

	@Test
	void duplicateTypeDeclarationsKeepTheFirstOne() {
		CodeGraphIR graph = resolve(
				file("one/A.java", "p", List.of(), clazz("p.A", List.of(), method("first"))),
				file("two/A.java", "p", List.of(), clazz("p.A", List.of(), method("second"))));

		assertThat(node(graph, "method:p.A#first()")).isNotNull();
		assertThat(node(graph, "method:p.A#second()")).isNull();
	}

	// ---- inheritance ---------------------------------------------------------------------------

	@Test
	void resolvesExtendsAndImplementsWithinTheRepository() {
		TypeIR base = clazz("p.Base", List.of());
		TypeIR contract = type(TypeKind.INTERFACE, "p.Contract", List.of(), List.of(), List.of(), List.of());
		TypeIR child = type(TypeKind.CLASS, "p.Child", List.of(ref("Base")), List.of(ref("Contract")),
				List.of(), List.of());
		TypeIR subContract = type(TypeKind.INTERFACE, "p.SubContract", List.of(ref("Contract")), List.of(),
				List.of(), List.of());

		CodeGraphIR graph = resolve(file("p/All.java", "p", List.of(), base, contract, child, subContract));

		assertThat(hasEdge(graph, "class:p.Child", RelationshipType.EXTENDS, "class:p.Base")).isTrue();
		assertThat(hasEdge(graph, "class:p.Child", RelationshipType.IMPLEMENTS, "interface:p.Contract")).isTrue();
		assertThat(hasEdge(graph, "interface:p.SubContract", RelationshipType.EXTENDS, "interface:p.Contract")).isTrue();
	}

	@Test
	void keepsExternalAndUnresolvedSuperTypesAsExternalNodes() {
		TypeIR child = type(TypeKind.CLASS, "p.Failure", List.of(ref("Exception")), List.of(ref("Serializable")),
				List.of(), List.of());

		CodeGraphIR graph = resolve(file("p/Failure.java", "p", List.of(imp("java.io.Serializable")), child));

		NodeIR serializable = node(graph, "external:interface:java.io.Serializable");
		assertThat(serializable.external()).isTrue();
		assertThat(serializable.name()).isEqualTo("Serializable");
		assertThat(hasEdge(graph, "class:p.Failure", RelationshipType.IMPLEMENTS, serializable.id())).isTrue();

		NodeIR exception = node(graph, "external:class:Exception");
		assertThat(exception.external()).isTrue();
		assertThat(hasEdge(graph, "class:p.Failure", RelationshipType.EXTENDS, exception.id())).isTrue();
	}

	// ---- imports -------------------------------------------------------------------------------

	@Test
	void importsLinkFilesAndMarkTheRestExternal() {
		FileIR a = file("a/A.java", "a", List.of(imp("b.B"), imp("java.util.List")), clazz("a.A", List.of()));
		FileIR b = file("b/B.java", "b", List.of(), clazz("b.B", List.of()));

		CodeGraphIR graph = resolve(a, b);

		assertThat(hasEdge(graph, "file:a/A.java", RelationshipType.IMPORTS, "file:b/B.java")).isTrue();
		assertThat(hasEdge(graph, "file:a/A.java", RelationshipType.IMPORTS, "external:class:java.util.List")).isTrue();
		assertThat(node(graph, "external:class:java.util.List").external()).isTrue();
	}

	@Test
	void staticImportsLinkTheDeclaringType() {
		FileIR a = file("a/A.java", "a", List.of(new ImportIR("b", "Util", "helper", null, false, 0)), clazz("a.A", List.of()));
		FileIR b = file("b/Util.java", "b", List.of(), clazz("b.Util", List.of()));

		CodeGraphIR graph = resolve(a, b);

		assertThat(hasEdge(graph, "file:a/A.java", RelationshipType.IMPORTS, "file:b/Util.java")).isTrue();
	}

	@Test
	void wildcardImportsResolveTypesOfTheRepositoryAndLinkTheModulesFiles() {
		FileIR a = file("a/A.java", "a", List.of(ImportIR.wildcard("b")),
				clazz("a.A", List.of(var("helper", "B"))));
		FileIR b = file("b/B.java", "b", List.of(), clazz("b.B", List.of()));

		CodeGraphIR graph = resolve(a, b);

		assertThat(hasEdge(graph, "class:a.A", RelationshipType.REFERENCES, "class:b.B")).isTrue();
		assertThat(hasEdge(graph, "file:a/A.java", RelationshipType.IMPORTS, "file:b/B.java")).isTrue();
	}

	// ---- references and dependencies -----------------------------------------------------------

	@Test
	void fieldAndSignatureTypesBecomeReferencesAndClassDependencies() {
		TypeIR a = clazz("p.A", List.of(var("b", "B")),
				method("use", List.of(var("c", "C")), List.of(var("d", "D"))));
		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a),
				file("p/B.java", "p", List.of(), clazz("p.B", List.of())),
				file("p/C.java", "p", List.of(), clazz("p.C", List.of())),
				file("p/D.java", "p", List.of(), clazz("p.D", List.of())));

		assertThat(hasEdge(graph, "class:p.A", RelationshipType.REFERENCES, "class:p.B")).isTrue();
		assertThat(hasEdge(graph, "method:p.A#use(C)", RelationshipType.REFERENCES, "class:p.C")).isTrue();
		assertThat(hasEdge(graph, "method:p.A#use(C)", RelationshipType.REFERENCES, "class:p.D")).isTrue();
		assertThat(hasEdge(graph, "class:p.A", RelationshipType.DEPENDS_ON, "class:p.B")).isTrue();
		assertThat(hasEdge(graph, "class:p.A", RelationshipType.DEPENDS_ON, "class:p.C")).isTrue();
		assertThat(hasEdge(graph, "class:p.A", RelationshipType.DEPENDS_ON, "class:p.D")).isTrue();
	}

	@Test
	void typeArgumentsAreReferenced() {
		TypeIR a = clazz("p.A", List.of(new VariableIR("items", ref("List", "B"))));
		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(imp("java.util.List")), a),
				file("p/B.java", "p", List.of(), clazz("p.B", List.of())));

		assertThat(hasEdge(graph, "class:p.A", RelationshipType.REFERENCES, "external:class:java.util.List")).isTrue();
		assertThat(hasEdge(graph, "class:p.A", RelationshipType.REFERENCES, "class:p.B")).isTrue();
	}

	@Test
	void instantiationsAreReferences() {
		MethodIR make = new MethodIR("make", false, 1, 1, List.of(), null, List.of(), List.of(), List.of(ref("B")));
		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), clazz("p.A", List.of(), make)),
				file("p/B.java", "p", List.of(), clazz("p.B", List.of())));

		assertThat(hasEdge(graph, "method:p.A#make()", RelationshipType.REFERENCES, "class:p.B")).isTrue();
		assertThat(hasEdge(graph, "class:p.A", RelationshipType.DEPENDS_ON, "class:p.B")).isTrue();
	}

	@Test
	void externalTypesAreReferencedButNeverDependedOn() {
		TypeIR a = clazz("p.A", List.of(var("list", "List")));
		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(imp("java.util.List")), a));

		assertThat(hasEdge(graph, "class:p.A", RelationshipType.REFERENCES, "external:class:java.util.List")).isTrue();
		assertThat(count(graph, RelationshipType.DEPENDS_ON)).isZero();
	}

	@Test
	void unknownTypeNamesProduceNoNodesOrEdges() {
		// e.g. String or a generic type parameter: not imported, not declared in the repository
		TypeIR a = clazz("p.A", List.of(var("name", "String"), var("value", "T")));
		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a));

		assertThat(count(graph, RelationshipType.REFERENCES)).isZero();
		assertThat(graph.nodes()).noneMatch(NodeIR::external);
	}

	@Test
	void aTypeNeverDependsOnItself() {
		TypeIR a = clazz("p.A", List.of(var("next", "A")), method("loop", call("this", "loop", 0)));
		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a));

		assertThat(count(graph, RelationshipType.DEPENDS_ON)).isZero();
		assertThat(graph.edges()).noneMatch(e -> e.source().equals(e.target()));
	}

	// ---- calls ---------------------------------------------------------------------------------

	private static FileIR callee() {
		return file("p/B.java", "p", List.of(), clazz("p.B", List.of(), method("run"),
				method("two", List.of(var("x", "int"), var("y", "int")), List.of()),
				method("two", List.of(var("x", "int")), List.of())));
	}

	@Test
	void resolvesCallThroughAField() {
		TypeIR a = clazz("p.A", List.of(var("b", "B")), method("go", call("b", "run", 0)));

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a), callee());

		assertThat(hasEdge(graph, "method:p.A#go()", RelationshipType.CALLS, "method:p.B#run()")).isTrue();
		assertThat(hasEdge(graph, "class:p.A", RelationshipType.DEPENDS_ON, "class:p.B")).isTrue();
	}

	@Test
	void resolvesCallThroughThisField() {
		TypeIR a = clazz("p.A", List.of(var("b", "B")), method("go", call("this.b", "run", 0)));

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a), callee());

		assertThat(hasEdge(graph, "method:p.A#go()", RelationshipType.CALLS, "method:p.B#run()")).isTrue();
	}

	@Test
	void resolvesCallThroughParametersAndLocalsAndLocalsShadowFields() {
		MethodIR viaParameter = method("p1", List.of(var("b", "B")), List.of(), call("b", "run", 0));
		MethodIR viaLocal = method("p2", List.of(), List.of(var("b", "B")), call("b", "run", 0));
		TypeIR a = clazz("p.A", List.of(var("b", "Other")), viaParameter, viaLocal);

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a), callee(),
				file("p/Other.java", "p", List.of(), clazz("p.Other", List.of())));

		assertThat(hasEdge(graph, "method:p.A#p1(B)", RelationshipType.CALLS, "method:p.B#run()")).isTrue();
		assertThat(hasEdge(graph, "method:p.A#p2()", RelationshipType.CALLS, "method:p.B#run()")).isTrue();
	}

	@Test
	void resolvesUnqualifiedAndThisCallsWithinTheType() {
		TypeIR a = clazz("p.A", List.of(),
				method("outer", call(null, "inner", 0), call("this", "inner", 0)),
				method("inner"));

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a));

		assertThat(hasEdge(graph, "method:p.A#outer()", RelationshipType.CALLS, "method:p.A#inner()")).isTrue();
	}

	@Test
	void resolvesStaticCallsOnATypeName() {
		TypeIR a = clazz("p.A", List.of(), method("go", call("B", "run", 0)));

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a), callee());

		assertThat(hasEdge(graph, "method:p.A#go()", RelationshipType.CALLS, "method:p.B#run()")).isTrue();
	}

	@Test
	void resolvesInheritedMethodsThroughSuperTypes() {
		TypeIR base = clazz("p.Base", List.of(), method("shared"));
		TypeIR child = type(TypeKind.CLASS, "p.Child", List.of(ref("Base")), List.of(), List.of(),
				List.of(method("go", call(null, "shared", 0), call("super", "shared", 0))));
		TypeIR user = clazz("p.User", List.of(var("child", "Child")), method("use", call("child", "shared", 0)));

		CodeGraphIR graph = resolve(file("p/All.java", "p", List.of(), base, child, user));

		assertThat(hasEdge(graph, "method:p.Child#go()", RelationshipType.CALLS, "method:p.Base#shared()")).isTrue();
		assertThat(hasEdge(graph, "method:p.User#use()", RelationshipType.CALLS, "method:p.Base#shared()")).isTrue();
	}

	@Test
	void callsOnAnInterfaceTypedFieldTargetTheInterfaceMethod() {
		TypeIR contract = type(TypeKind.INTERFACE, "p.Contract", List.of(), List.of(), List.of(),
				List.of(method("work")));
		TypeIR a = clazz("p.A", List.of(var("contract", "Contract")), method("go", call("contract", "work", 0)));

		CodeGraphIR graph = resolve(file("p/All.java", "p", List.of(), contract, a));

		assertThat(hasEdge(graph, "method:p.A#go()", RelationshipType.CALLS, "method:p.Contract#work()")).isTrue();
	}

	@Test
	void picksTheOverloadWithMatchingArgumentCount() {
		TypeIR a = clazz("p.A", List.of(var("b", "B")), method("go", call("b", "two", 2)));

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a), callee());

		assertThat(hasEdge(graph, "method:p.A#go()", RelationshipType.CALLS, "method:p.B#two(int,int)")).isTrue();
		assertThat(hasEdge(graph, "method:p.A#go()", RelationshipType.CALLS, "method:p.B#two(int)")).isFalse();
	}

	@Test
	void callsOnExternalTypesBecomeExternalMethodNodes() {
		TypeIR a = clazz("p.A", List.of(var("items", "List")), method("go", call("items", "add", 1)));

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(imp("java.util.List")), a));

		NodeIR external = node(graph, "external:method:java.util.List#add");
		assertThat(external.external()).isTrue();
		assertThat(hasEdge(graph, "method:p.A#go()", RelationshipType.CALLS, external.id())).isTrue();
		assertThat(hasEdge(graph, "external:class:java.util.List", RelationshipType.CONTAINS, external.id())).isTrue();
	}

	@Test
	void unresolvableCallsAreSkippedWithoutFailing() {
		TypeIR a = clazz("p.A", List.of(var("b", "B")), method("go",
				call("b.run()", "chained", 0),       // chained expression
				call("mystery", "run", 0),           // unknown variable and not a type
				call(null, "missing", 0),            // not declared anywhere
				call("b", "missing", 0)));           // known type without that method

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a), callee());

		assertThat(count(graph, RelationshipType.CALLS)).isZero();
	}

	@Test
	void callsFromFreeStandingFunctionsResolveWithinTheFile() {
		MethodIR helper = method("helper");
		MethodIR main = method("main", call(null, "helper", 0));
		FileIR script = new FileIR("s/script.py", Language.PYTHON, "", List.of(), List.of(), List.of(main, helper));

		CodeGraphIR graph = resolve(script);

		assertThat(node(graph, "function:s/script.py#main()").type()).isEqualTo(NodeType.FUNCTION);
		assertThat(hasEdge(graph, "file:s/script.py", RelationshipType.CONTAINS, "function:s/script.py#main()")).isTrue();
		assertThat(hasEdge(graph, "function:s/script.py#main()", RelationshipType.CALLS,
				"function:s/script.py#helper()")).isTrue();
	}

	@Test
	void producesNoDuplicateNodesOrEdges() {
		TypeIR a = clazz("p.A", List.of(var("b", "B")),
				method("go", call("b", "run", 0), call("b", "run", 0)));

		CodeGraphIR graph = resolve(file("p/A.java", "p", List.of(), a), callee());

		assertThat(graph.nodes()).extracting(NodeIR::id).doesNotHaveDuplicates();
		assertThat(graph.edges()).doesNotHaveDuplicates();
	}
}
