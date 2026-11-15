package com.abhiraj.archyra.parsing.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.CallIR;
import com.abhiraj.archyra.ir.ImportIR;
import com.abhiraj.archyra.ir.TypeKind;
import com.abhiraj.archyra.ir.TypeRefIR;
import com.abhiraj.archyra.ir.VariableIR;
import com.abhiraj.archyra.parsing.TreeSitterExtractor;

import io.github.treesitter.jtreesitter.Node;

/**
 * What JavaScript and TypeScript share: ES modules and CommonJS, classes, functions (declarations,
 * arrow functions and function expressions bound to a name, methods), calls, JSX components.
 *
 * <p>TypeScript adds interfaces, enums, namespaces, type annotations and constructor parameter
 * properties ({@code constructor(private repo: Repo)}, the Angular injection style), which declare
 * fields. In plain JavaScript values are typed from {@code new Foo()} initialisers and
 * {@code this.x = ...} assignments in constructors.
 */
abstract class EcmaScriptExtractor extends TreeSitterExtractor {

	private static final Set<String> TYPE_NAMES = Set.of("type_identifier", "nested_type_identifier", "predefined_type");
	private static final Set<String> TYPE_WRAPPERS = Set.of("type_annotation", "generic_type", "type_arguments",
			"array_type", "union_type", "intersection_type", "parenthesized_type", "readonly_type", "optional_type",
			"tuple_type", "type_predicate_annotation", "asserts_annotation");

	/** {@code src/a/user.service.ts} is {@code src.a.user.service}. */
	@Override
	protected String moduleName(SourceFile file, Node root) {
		String path = file.relativePath();
		int dot = path.lastIndexOf('.');
		int slash = path.lastIndexOf('/');
		String withoutExtension = dot > slash ? path.substring(0, dot) : path;
		return withoutExtension.replace('/', '.');
	}

	/** The directory. */
	@Override
	protected String groupName(SourceFile file, String moduleName) {
		int slash = file.relativePath().lastIndexOf('/');
		return slash < 0 ? "" : file.relativePath().substring(0, slash);
	}

	@Override
	protected List<Node> unwrap(Node node) {
		switch (node.getType()) {
			case "export_statement" -> {
				// export class X {} / export default function f() {}; re-exports (`from`) are imports instead
				Optional<Node> declaration = fieldOf(node, "declaration");
				if (declaration.isPresent()) {
					return List.of(declaration.get());
				}
				return fieldOf(node, "value")
						.filter(value -> isType(value, "class", "function_expression", "arrow_function", "identifier",
								"object"))
						.stream().toList();
			}
			case "ambient_declaration" -> {
				return node.getNamedChildren();
			}
			case "expression_statement" -> {
				// a namespace is parsed as an expression statement around an internal module
				return node.getNamedChildren().stream().filter(child -> isType(child, "internal_module", "module"))
						.toList();
			}
			case "internal_module", "module" -> {
				return fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of());
			}
			case "object" -> {
				// export default { methods: {...} }: the members of an object component
				return node.getNamedChildren();
			}
			case "pair" -> {
				// methods: { load() {...} }
				Optional<Node> value = fieldOf(node, "value");
				return value.filter(v -> v.getType().equals("object")).map(Node::getNamedChildren).orElse(List.of());
			}
			default -> {
				return List.of();
			}
		}
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		return switch (node.getType()) {
			case "import_statement" -> importStatement(node);
			case "export_statement" -> reExport(node);
			case "lexical_declaration", "variable_declaration" -> requireCalls(node);
			default -> List.of();
		};
	}

	private static List<ImportIR> importStatement(Node node) {
		String module = sourceOf(node);
		if (module == null) {
			return List.of();
		}
		Optional<Node> clause = childOfType(node, "import_clause");
		if (clause.isEmpty()) {
			return List.of(ImportIR.module(module, null)); // import './side-effect'
		}
		List<ImportIR> result = new ArrayList<>();
		for (Node part : clause.get().getNamedChildren()) {
			switch (part.getType()) {
				case "identifier" -> result.add(ImportIR.symbol(module, "default", part.getText()));
				case "namespace_import" -> childOfType(part, "identifier")
						.ifPresent(name -> result.add(ImportIR.module(module, name.getText())));
				case "named_imports" -> {
					for (Node specifier : childrenOfType(part, "import_specifier")) {
						result.add(ImportIR.symbol(module, fieldText(specifier, "name"), fieldText(specifier, "alias")));
					}
				}
				default -> {
				}
			}
		}
		return result;
	}

	/** {@code export { A } from './a'} and {@code export * from './a'}: the barrel file imports what it re-exports. */
	private static List<ImportIR> reExport(Node node) {
		String module = sourceOf(node);
		if (module == null) {
			return List.of();
		}
		Optional<Node> clause = childOfType(node, "export_clause");
		if (clause.isPresent()) {
			List<ImportIR> result = new ArrayList<>();
			for (Node specifier : childrenOfType(clause.get(), "export_specifier")) {
				result.add(ImportIR.symbol(module, fieldText(specifier, "name"), fieldText(specifier, "alias")));
			}
			return result;
		}
		Optional<Node> namespace = childOfType(node, "namespace_export");
		if (namespace.isPresent()) {
			return childOfType(namespace.get(), "identifier")
					.map(name -> List.of(ImportIR.module(module, name.getText()))).orElse(List.of());
		}
		return List.of(ImportIR.wildcard(module));
	}

	/** {@code const x = require('m')} and {@code const { a, b } = require('m')}. */
	private static List<ImportIR> requireCalls(Node node) {
		List<ImportIR> result = new ArrayList<>();
		for (Node declarator : childrenOfType(node, "variable_declarator")) {
			Optional<Node> value = fieldOf(declarator, "value");
			if (value.isEmpty() || !value.get().getType().equals("call_expression")
					|| !"require".equals(fieldText(value.get(), "function"))) {
				continue;
			}
			String module = fieldOf(value.get(), "arguments").flatMap(arguments -> childOfType(arguments, "string"))
					.map(EcmaScriptExtractor::stringValue).orElse(null);
			Optional<Node> name = fieldOf(declarator, "name");
			if (module == null || name.isEmpty()) {
				continue;
			}
			if (name.get().getType().equals("identifier")) {
				result.add(ImportIR.module(module, name.get().getText()));
			}
			else if (name.get().getType().equals("object_pattern")) {
				for (Node property : name.get().getNamedChildren()) {
					if (property.getType().equals("shorthand_property_identifier_pattern")) {
						result.add(ImportIR.symbol(module, property.getText()));
					}
					else if (property.getType().equals("pair_pattern")) {
						result.add(ImportIR.symbol(module, fieldText(property, "key"), fieldText(property, "value")));
					}
				}
			}
		}
		return result;
	}

	private static String sourceOf(Node node) {
		return fieldOf(node, "source").map(EcmaScriptExtractor::stringValue).orElse(null);
	}

	private static String stringValue(Node string) {
		return childOfType(string, "string_fragment").map(Node::getText).orElse("");
	}

	// ---- types ---------------------------------------------------------------------------------

	@Override
	protected Optional<TypeDecl> typeOf(Node node) {
		switch (node.getType()) {
			case "class_declaration", "abstract_class_declaration" -> {
				Optional<Node> name = fieldOf(node, "name");
				if (name.isEmpty()) {
					return Optional.empty();
				}
				List<TypeRefIR> extended = new ArrayList<>();
				List<TypeRefIR> implemented = new ArrayList<>();
				childOfType(node, "class_heritage").ifPresent(heritage -> heritage(heritage, extended, implemented));
				return Optional.of(new TypeDecl(name.get().getText(), TypeKind.CLASS, extended, implemented,
						fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of())));
			}
			case "interface_declaration" -> {
				Optional<Node> name = fieldOf(node, "name");
				if (name.isEmpty()) {
					return Optional.empty();
				}
				List<TypeRefIR> extended = new ArrayList<>();
				childOfType(node, "extends_type_clause").ifPresent(clause -> {
					for (Node type : clause.getNamedChildren()) {
						typeRefOf(type).ifPresent(extended::add);
					}
				});
				return Optional.of(new TypeDecl(name.get().getText(), TypeKind.INTERFACE, extended, List.of(),
						fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of())));
			}
			case "enum_declaration" -> {
				return fieldOf(node, "name").map(name -> new TypeDecl(name.getText(), TypeKind.ENUM, List.of(),
						List.of(), List.of()));
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	/** JS: {@code extends Expr}. TS: {@code extends_clause} and {@code implements_clause}. */
	private static void heritage(Node heritage, List<TypeRefIR> extended, List<TypeRefIR> implemented) {
		for (Node part : heritage.getNamedChildren()) {
			switch (part.getType()) {
				case "extends_clause" -> {
					Optional<Node> value = fieldOf(part, "value");
					if (value.isPresent() && isType(value.get(), "identifier", "member_expression")) {
						List<String> arguments = new ArrayList<>();
						fieldOf(part, "type_arguments").ifPresent(args -> typeRefOf(args).ifPresent(ref -> {
							arguments.add(ref.name());
							arguments.addAll(ref.typeArguments());
						}));
						extended.add(new TypeRefIR(value.get().getText(), arguments));
					}
				}
				case "implements_clause" -> {
					for (Node type : part.getNamedChildren()) {
						typeRefOf(type).ifPresent(implemented::add);
					}
				}
				case "identifier", "member_expression" -> extended.add(TypeRefIR.of(part.getText()));
				default -> {
				}
			}
		}
	}

	// ---- functions -----------------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		switch (node.getType()) {
			case "function_declaration", "generator_function_declaration", "function_signature" -> {
				return fieldOf(node, "name").map(name -> function(name.getText(), false, node, node));
			}
			case "method_definition", "method_signature", "abstract_method_signature" -> {
				Optional<Node> name = fieldOf(node, "name");
				if (name.isEmpty()) {
					return Optional.empty();
				}
				boolean constructor = node.getType().equals("method_definition") && name.get().getText().equals("constructor");
				return Optional.of(function(name.get().getText(), constructor, node, node));
			}
			case "field_definition", "public_field_definition" -> {
				// handler = () => {...} is a method
				Optional<Node> value = fieldOf(node, "value");
				Optional<Node> name = fieldOf(node, "name").or(() -> fieldOf(node, "property"));
				if (value.isPresent() && name.isPresent() && isType(value.get(), "arrow_function", "function_expression")) {
					return Optional.of(function(name.get().getText(), false, value.get(), value.get()));
				}
				return Optional.empty();
			}
			case "expression_statement" -> {
				// CommonJS and prototypes: exports.load = function () {...}; Store.prototype.save = () => {...}
				Optional<Node> assignment = childOfType(node, "assignment_expression");
				Optional<Node> left = assignment.flatMap(a -> fieldOf(a, "left"));
				Optional<Node> right = assignment.flatMap(a -> fieldOf(a, "right"));
				if (left.isPresent() && right.isPresent() && left.get().getType().equals("member_expression")
						&& isType(right.get(), "function_expression", "arrow_function")) {
					String name = fieldOf(right.get(), "name").map(Node::getText)
							.orElse(fieldText(left.get(), "property"));
					return Optional.of(function(name, false, right.get(), right.get()));
				}
				return Optional.empty();
			}
			case "pair" -> {
				// load: () => {...} or load: function () {...}
				Optional<Node> key = fieldOf(node, "key");
				Optional<Node> value = fieldOf(node, "value");
				if (key.isPresent() && value.isPresent() && isType(value.get(), "arrow_function", "function_expression")) {
					return Optional.of(function(key.get().getText(), false, value.get(), value.get()));
				}
				return Optional.empty();
			}
			case "lexical_declaration", "variable_declaration" -> {
				for (Node declarator : childrenOfType(node, "variable_declarator")) {
					Optional<Node> value = fieldOf(declarator, "value");
					Optional<Node> name = fieldOf(declarator, "name");
					if (value.isPresent() && name.isPresent() && name.get().getType().equals("identifier")
							&& isType(value.get(), "arrow_function", "function_expression")) {
						return Optional.of(function(name.get().getText(), false, value.get(), value.get()));
					}
				}
				return Optional.empty();
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	/** {@code source} carries parameters, return type and body (a method node, or the arrow function). */
	private FunctionDecl function(String name, boolean constructor, Node declaration, Node source) {
		List<VariableIR> parameters = new ArrayList<>();
		List<VariableIR> propertyFields = new ArrayList<>();
		fieldOf(source, "parameters").ifPresent(list -> parameters(list, parameters, propertyFields));
		// an arrow function with a single bare parameter: x => ...
		fieldOf(source, "parameter").ifPresent(single -> parameters.add(new VariableIR(single.getText(), null)));

		TypeRefIR returnType = fieldOf(source, "return_type").flatMap(EcmaScriptExtractor::typeRefOf).orElse(null);
		if (returnType != null && returnType.name().equals("void")) {
			returnType = null;
		}
		Node body = fieldOf(source, "body").orElse(null);
		if (constructor && body != null) {
			propertyFields.addAll(assignedFields(body, parameters));
		}
		return new FunctionDecl(name, constructor, parameters, returnType, body, null, List.of(), propertyFields);
	}

	private static void parameters(Node list, List<VariableIR> parameters, List<VariableIR> propertyFields) {
		for (Node parameter : list.getNamedChildren()) {
			switch (parameter.getType()) {
				case "identifier" -> parameters.add(new VariableIR(parameter.getText(), null));
				case "required_parameter", "optional_parameter" -> {
					Optional<Node> pattern = fieldOf(parameter, "pattern");
					if (pattern.isEmpty() || pattern.get().getType().equals("this")) {
						break;
					}
					TypeRefIR type = fieldOf(parameter, "type").flatMap(EcmaScriptExtractor::typeRefOf).orElse(null);
					String name = pattern.get().getType().equals("identifier") ? pattern.get().getText() : "_";
					parameters.add(new VariableIR(name, type));
					boolean property = childOfType(parameter, "accessibility_modifier").isPresent()
							|| parameter.getChildren().stream().anyMatch(child -> child.getType().equals("readonly"));
					if (property && pattern.get().getType().equals("identifier")) {
						propertyFields.add(new VariableIR(name, type));
					}
				}
				case "assignment_pattern" -> parameters.add(new VariableIR(
						fieldOf(parameter, "left").filter(left -> left.getType().equals("identifier"))
								.map(Node::getText).orElse("_"), null));
				case "object_pattern", "array_pattern" -> parameters.add(new VariableIR("_", null));
				default -> {
				}
			}
		}
	}

	/** {@code this.x = ...} in a JavaScript constructor, typed by the parameter or the {@code new} on the right. */
	private static List<VariableIR> assignedFields(Node body, List<VariableIR> parameters) {
		List<VariableIR> fields = new ArrayList<>();
		Deque<Node> stack = new ArrayDeque<>();
		stack.push(body);
		while (!stack.isEmpty()) {
			Node node = stack.pop();
			if (node.getType().equals("assignment_expression")) {
				Optional<Node> left = fieldOf(node, "left");
				if (left.isPresent() && left.get().getType().equals("member_expression")
						&& "this".equals(fieldText(left.get(), "object"))) {
					TypeRefIR type = fieldOf(node, "right").flatMap(right -> valueType(right, parameters)).orElse(null);
					fields.add(new VariableIR(fieldText(left.get(), "property"), type));
				}
			}
			List<Node> children = node.getNamedChildren();
			for (int i = children.size() - 1; i >= 0; i--) {
				stack.push(children.get(i));
			}
		}
		return fields;
	}

	/** The type visible at a value: a typed parameter, a {@code new Foo()}, or a call to a function. */
	private static Optional<TypeRefIR> valueType(Node value, List<VariableIR> parameters) {
		switch (value.getType()) {
			case "identifier" -> {
				return parameters.stream().filter(parameter -> parameter.name().equals(value.getText()))
						.map(VariableIR::type).filter(java.util.Objects::nonNull).findFirst();
			}
			case "new_expression" -> {
				return fieldOf(value, "constructor").filter(c -> isType(c, "identifier", "member_expression"))
						.map(constructor -> TypeRefIR.of(constructor.getText()));
			}
			case "await_expression" -> {
				return value.getNamedChildren().stream().findFirst().flatMap(inner -> valueType(inner, parameters));
			}
			case "call_expression" -> {
				return fieldOf(value, "function").filter(function -> function.getType().equals("identifier"))
						.filter(function -> !function.getText().equals("require"))
						.map(function -> TypeRefIR.of(function.getText()));
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	@Override
	protected List<VariableIR> fieldsOf(Node node) {
		switch (node.getType()) {
			case "field_definition", "public_field_definition", "property_signature" -> {
				Optional<Node> name = fieldOf(node, "name").or(() -> fieldOf(node, "property"));
				if (name.isEmpty()) {
					return List.of();
				}
				Optional<Node> value = fieldOf(node, "value");
				TypeRefIR type = fieldOf(node, "type").flatMap(EcmaScriptExtractor::typeRefOf)
						.or(() -> value.flatMap(v -> valueType(v, List.of())))
						.orElse(null);
				return List.of(new VariableIR(name.get().getText(), type));
			}
			default -> {
				return List.of();
			}
		}
	}

	// ---- bodies --------------------------------------------------------------------------------

	@Override
	protected Optional<CallIR> callOf(Node node) {
		switch (node.getType()) {
			case "call_expression" -> {
				Optional<Node> function = fieldOf(node, "function");
				if (function.isEmpty()) {
					return Optional.empty();
				}
				int arguments = fieldOf(node, "arguments").map(Node::getNamedChildCount).orElse(0);
				Node target = function.get();
				if (target.getType().equals("identifier")) {
					return target.getText().equals("require") ? Optional.empty()
							: Optional.of(new CallIR(target.getText(), null, arguments, lineOf(node)));
				}
				if (target.getType().equals("member_expression")) {
					Optional<Node> object = fieldOf(target, "object");
					Optional<Node> property = fieldOf(target, "property");
					if (object.isPresent() && property.isPresent()) {
						return Optional.of(new CallIR(property.get().getText(), receiver(object.get()), arguments,
								lineOf(node)));
					}
				}
				return Optional.empty();
			}
			case "jsx_self_closing_element", "jsx_opening_element" -> {
				// <Header /> uses the component Header
				Optional<Node> name = fieldOf(node, "name");
				if (name.isEmpty()) {
					return Optional.empty();
				}
				String text = name.get().getText();
				if (name.get().getType().equals("identifier") && Character.isUpperCase(text.charAt(0))) {
					return Optional.of(new CallIR(text, null, 0, lineOf(node)));
				}
				if (name.get().getType().equals("member_expression") && Character.isUpperCase(text.charAt(0))) {
					return Optional.of(new CallIR(fieldText(name.get(), "property"),
							fieldText(name.get(), "object"), 0, lineOf(node)));
				}
				return Optional.empty();
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	/** Optional chaining and non-null assertions do not change what the receiver is. */
	private static String receiver(Node object) {
		String text = truncate(object.getText(), 200).replace("?.", ".").replace("!.", ".");
		return text.endsWith("!") ? text.substring(0, text.length() - 1) : text;
	}

	@Override
	protected Optional<TypeRefIR> instantiationOf(Node node) {
		return node.getType().equals("new_expression")
				? fieldOf(node, "constructor").filter(c -> isType(c, "identifier", "member_expression"))
						.map(constructor -> TypeRefIR.of(constructor.getText()))
				: Optional.empty();
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		if (!node.getType().equals("variable_declarator")) {
			return List.of();
		}
		Optional<Node> name = fieldOf(node, "name");
		if (name.isEmpty() || !name.get().getType().equals("identifier")) {
			return List.of();
		}
		TypeRefIR type = fieldOf(node, "type").flatMap(EcmaScriptExtractor::typeRefOf)
				.or(() -> fieldOf(node, "value").flatMap(value -> valueType(value, List.of())))
				.orElse(null);
		return List.of(new VariableIR(name.get().getText(), type));
	}

	private static Optional<TypeRefIR> typeRefOf(Node node) {
		return typeRef(node, TYPE_NAMES, TYPE_WRAPPERS);
	}
}
