package com.abhiraj.archyra.parsing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.CallIR;
import com.abhiraj.archyra.ir.Declaration;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.ImportIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;
import com.abhiraj.archyra.ir.TypeRefIR;
import com.abhiraj.archyra.ir.VariableIR;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Tree;

/**
 * Base class of the Tree-sitter extractors. It owns everything that is the same in every language:
 * parsing, walking declarations with their nesting and qualified names, grouping members with their
 * type, and scanning function bodies for calls, locals and instantiations (iteratively, so deeply nested
 * expressions cannot overflow the stack).
 *
 * <p>A language only says which of its node types are imports, types, functions and calls, by
 * implementing the small recognising hooks. A hook that does not apply to a language keeps its empty
 * default, so nothing language-specific is forced where it does not exist: a language without classes
 * never returns a {@link TypeDecl}.
 *
 * <p>Tree-sitter is error tolerant, so a file with syntax errors still yields whatever is recognisable.
 */
public abstract class TreeSitterExtractor implements LanguageExtractor {

	/**
	 * A recognised type-like declaration.
	 *
	 * @param name         simple name (for an {@link Declaration#EXTENSION} the extended type's name)
	 * @param members      nodes inside the declaration that are walked for methods, fields and nested types
	 * @param fields       fields that are not declared as members (record components, struct fields in a
	 *                     header)
	 */
	public record TypeDecl(String name, TypeKind kind, Declaration declaration, List<TypeRefIR> extendsTypes,
			List<TypeRefIR> implementsTypes, List<Node> members, List<VariableIR> fields) {

		public TypeDecl(String name, TypeKind kind, List<TypeRefIR> extendsTypes, List<TypeRefIR> implementsTypes,
				List<Node> members) {
			this(name, kind, Declaration.FULL, extendsTypes, implementsTypes, members, List.of());
		}
	}

	/**
	 * A recognised function or method.
	 *
	 * @param body              the node scanned for calls (a block, or an expression for expression bodies)
	 * @param receiverType      Go-style receiver: the method belongs to this type declared elsewhere; null
	 *                          otherwise
	 * @param typedVariables    variables in scope from the signature that are not parameters (a Go receiver)
	 * @param propertyFields    fields declared by the signature (TypeScript constructor parameter properties)
	 */
	public record FunctionDecl(String name, boolean constructor, List<VariableIR> parameters,
			TypeRefIR returnType, Node body, String receiverType, List<VariableIR> typedVariables,
			List<VariableIR> propertyFields) {

		public FunctionDecl(String name, boolean constructor, List<VariableIR> parameters, TypeRefIR returnType,
				Node body) {
			this(name, constructor, parameters, returnType, body, null, List.of(), List.of());
		}
	}

	// ---- language hooks ----------------------------------------------------------------------

	/** IR language of the files this extractor produces by default. */
	protected abstract Language language();

	/** Name of the native grammar library ({@code libtree-sitter-<name>}). */
	protected abstract String grammar();

	/** The grammar for one file; overridden where a language has dialects (TypeScript and TSX). */
	protected String grammarFor(SourceFile file) {
		return grammar();
	}

	/** What qualifies symbols declared in this file and what imports refer to. */
	protected abstract String moduleName(SourceFile file, Node root);

	/** The grouping used for the Module node; defaults to the module name. */
	protected String groupName(SourceFile file, String moduleName) {
		return moduleName;
	}

	/** Declaration-level nodes of the file. */
	protected List<Node> topLevel(Node root) {
		return root.getNamedChildren();
	}

	/** If {@code node} is a wrapper (an export statement, a decorated definition), the nodes it stands for. */
	protected List<Node> unwrap(Node node) {
		return List.of();
	}

	/** If {@code node} is a namespace-like block whose children are more declarations. */
	protected boolean isContainer(Node node) {
		return false;
	}

	protected List<ImportIR> importsOf(Node node) {
		return List.of();
	}

	protected Optional<TypeDecl> typeOf(Node node) {
		return Optional.empty();
	}

	protected Optional<FunctionDecl> functionOf(Node node) {
		return Optional.empty();
	}

	/** Fields declared by a member node (only called inside a type). */
	protected List<VariableIR> fieldsOf(Node node) {
		return List.of();
	}

	protected Optional<CallIR> callOf(Node node) {
		return Optional.empty();
	}

	protected Optional<TypeRefIR> instantiationOf(Node node) {
		return Optional.empty();
	}

	protected List<VariableIR> localsOf(Node node) {
		return List.of();
	}

	// ---- extraction ---------------------------------------------------------------------------

	@Override
	public FileIR extract(SourceFile file) {
		String source;
		try {
			source = new String(Files.readAllBytes(file.absolutePath()), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("File could not be read", ex);
		}
		return extractSource(file, source, language());
	}

	/** Extracts from already prepared source text, e.g. the script block of a single-file component. */
	public final FileIR extractSource(SourceFile file, String source, Language fileLanguage) {
		try (Parser parser = new Parser(NativeLibraries.language(grammarFor(file)));
				Tree tree = parser.parse(source).orElseThrow(() -> new IllegalStateException("No syntax tree produced"))) {
			Node root = tree.getRootNode();
			String moduleName = moduleName(file, root);
			Walk walk = new Walk(moduleName);
			walk.visit(topLevel(root), null, moduleName, walk.types);

			List<TypeIR> types = new ArrayList<>();
			walk.types.forEach(type -> types.add(type.build()));
			return new FileIR(file.relativePath(), fileLanguage, moduleName, groupName(file, moduleName),
					walk.imports, types, walk.functions);
		}
	}

	/** Mutable counterpart of {@link TypeIR} while the walk is running. */
	private static final class TypeBuilder {
		final String name;
		final String qualifiedName;
		final TypeKind kind;
		final Declaration declaration;
		final int line;
		final List<TypeRefIR> extendsTypes;
		final List<TypeRefIR> implementsTypes;
		final List<VariableIR> fields = new ArrayList<>();
		final List<MethodIR> methods = new ArrayList<>();
		final List<TypeBuilder> nested = new ArrayList<>();

		TypeBuilder(String name, String qualifiedName, TypeKind kind, Declaration declaration, int line,
				List<TypeRefIR> extendsTypes, List<TypeRefIR> implementsTypes) {
			this.name = name;
			this.qualifiedName = qualifiedName;
			this.kind = kind;
			this.declaration = declaration;
			this.line = line;
			this.extendsTypes = extendsTypes;
			this.implementsTypes = implementsTypes;
		}

		TypeIR build() {
			return new TypeIR(kind, name, qualifiedName, line, declaration, extendsTypes, implementsTypes, fields,
					methods, nested.stream().map(TypeBuilder::build).toList());
		}
	}

	private final class Walk {
		final String moduleName;
		final List<ImportIR> imports = new ArrayList<>();
		final List<TypeBuilder> types = new ArrayList<>();
		final List<MethodIR> functions = new ArrayList<>();
		/** Types that receive methods declared with a receiver, by qualified name. */
		final Map<String, TypeBuilder> receiverTypes = new LinkedHashMap<>();

		Walk(String moduleName) {
			this.moduleName = moduleName;
		}

		void visit(List<Node> nodes, TypeBuilder owner, String qualifier, List<TypeBuilder> typeSink) {
			Deque<Node> work = new ArrayDeque<>(nodes);
			while (!work.isEmpty()) {
				Node node = work.pollFirst();

				List<Node> unwrapped = unwrap(node);
				if (!unwrapped.isEmpty()) {
					for (int i = unwrapped.size() - 1; i >= 0; i--) {
						work.addFirst(unwrapped.get(i));
					}
					continue;
				}

				imports.addAll(importsOf(node));

				Optional<TypeDecl> type = typeOf(node);
				if (type.isPresent()) {
					declareType(node, type.get(), qualifier, typeSink);
					continue;
				}
				Optional<FunctionDecl> function = functionOf(node);
				if (function.isPresent()) {
					declareFunction(node, function.get(), owner, qualifier);
					continue;
				}
				if (owner != null) {
					owner.fields.addAll(fieldsOf(node));
				}
				if (isContainer(node)) {
					visit(node.getNamedChildren(), owner, qualifier, typeSink);
				}
			}
		}

		private void declareType(Node node, TypeDecl decl, String qualifier, List<TypeBuilder> typeSink) {
			String qualifiedName = qualifier.isEmpty() ? decl.name() : qualifier + "." + decl.name();
			TypeBuilder builder = new TypeBuilder(decl.name(), qualifiedName, decl.kind(), decl.declaration(),
					lineOf(node), decl.extendsTypes(), decl.implementsTypes());
			builder.fields.addAll(decl.fields());
			visit(decl.members(), builder, qualifiedName, builder.nested);
			typeSink.add(builder);
		}

		private void declareFunction(Node node, FunctionDecl decl, TypeBuilder owner, String qualifier) {
			MethodIR method = buildMethod(node, decl);
			if (decl.receiverType() != null) {
				String qualifiedName = moduleName.isEmpty() ? decl.receiverType() : moduleName + "." + decl.receiverType();
				TypeBuilder receiver = receiverTypes.computeIfAbsent(qualifiedName, key -> {
					TypeBuilder created = new TypeBuilder(decl.receiverType(), key, TypeKind.STRUCT,
							Declaration.EXTENSION, lineOf(node), List.of(), List.of());
					types.add(created);
					return created;
				});
				receiver.methods.add(method);
			}
			else if (owner != null) {
				owner.methods.add(method);
				owner.fields.addAll(decl.propertyFields());
			}
			else {
				functions.add(method);
			}
		}

		private MethodIR buildMethod(Node node, FunctionDecl decl) {
			List<VariableIR> locals = new ArrayList<>(decl.typedVariables());
			List<CallIR> calls = new ArrayList<>();
			List<TypeRefIR> instantiated = new ArrayList<>();

			if (decl.body() != null) {
				Deque<Node> stack = new ArrayDeque<>();
				stack.push(decl.body());
				while (!stack.isEmpty()) {
					Node current = stack.pop();
					callOf(current).ifPresent(calls::add);
					instantiationOf(current).ifPresent(instantiated::add);
					locals.addAll(localsOf(current));
					List<Node> children = current.getNamedChildren();
					for (int i = children.size() - 1; i >= 0; i--) {
						stack.push(children.get(i));
					}
				}
			}
			return new MethodIR(decl.name(), decl.constructor(), lineOf(node), decl.parameters(), decl.returnType(),
					locals, calls, instantiated);
		}
	}

	// ---- helpers for subclasses -------------------------------------------------------------

	protected static int lineOf(Node node) {
		return node.getStartPoint().row() + 1;
	}

	protected static String textOf(Node node) {
		return node.getText();
	}

	protected static Optional<Node> fieldOf(Node node, String field) {
		return node.getChildByFieldName(field);
	}

	protected static String fieldText(Node node, String field) {
		return node.getChildByFieldName(field).map(Node::getText).orElse(null);
	}

	protected static Optional<Node> childOfType(Node node, String type) {
		for (Node child : node.getNamedChildren()) {
			if (child.getType().equals(type)) {
				return Optional.of(child);
			}
		}
		return Optional.empty();
	}

	protected static List<Node> childrenOfType(Node node, String type) {
		return node.getNamedChildren().stream().filter(child -> child.getType().equals(type)).toList();
	}

	protected static boolean isType(Node node, String... types) {
		String actual = node.getType();
		for (String type : types) {
			if (actual.equals(type)) {
				return true;
			}
		}
		return false;
	}

	protected static String truncate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}

	/**
	 * Reduces a type expression to its base name followed by every type named in its arguments, depth
	 * first. {@code nameKinds} are the node types that carry a type name; {@code descendKinds} are the
	 * wrapper types (generic, array, optional, ...) searched for more names.
	 */
	protected static Optional<TypeRefIR> typeRef(Node node, Set<String> nameKinds, Set<String> descendKinds) {
		List<String> names = new ArrayList<>();
		collectTypeNames(node, nameKinds, descendKinds, names);
		if (names.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new TypeRefIR(names.getFirst(), names.subList(1, names.size())));
	}

	private static void collectTypeNames(Node node, Set<String> nameKinds, Set<String> descendKinds,
			List<String> out) {
		String type = node.getType();
		if (nameKinds.contains(type)) {
			out.add(node.getText());
		}
		else if (descendKinds.contains(type)) {
			for (Node child : node.getNamedChildren()) {
				collectTypeNames(child, nameKinds, descendKinds, out);
			}
		}
	}
}
