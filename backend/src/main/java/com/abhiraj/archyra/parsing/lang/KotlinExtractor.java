package com.abhiraj.archyra.parsing.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.CallIR;
import com.abhiraj.archyra.ir.Declaration;
import com.abhiraj.archyra.ir.ImportIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.TypeKind;
import com.abhiraj.archyra.ir.TypeRefIR;
import com.abhiraj.archyra.ir.VariableIR;
import com.abhiraj.archyra.parsing.TreeSitterExtractor;

import io.github.treesitter.jtreesitter.Node;

/**
 * Kotlin: classes, interfaces, objects, data and enum classes; functions and methods; imports. Package
 * semantics are Java's, so Kotlin and Java files in one repository resolve against each other.
 *
 * <p>The grammar names few fields, so declarations are read by node type and position. A delegation
 * specifier with arguments ({@code Base()}) is the superclass; one without is an interface. Companion
 * object members are folded into the class. {@code val}/{@code var} constructor parameters are fields.
 */
@Component
public class KotlinExtractor extends TreeSitterExtractor {

	@Override
	public boolean supports(Language language) {
		return language == Language.KOTLIN;
	}

	@Override
	protected Language language() {
		return Language.KOTLIN;
	}

	@Override
	protected String grammar() {
		return "kotlin";
	}

	@Override
	protected String moduleName(SourceFile file, Node root) {
		return childOfType(root, "package_header").flatMap(header -> childOfType(header, "identifier"))
				.map(Node::getText).orElse("");
	}

	@Override
	protected boolean isContainer(Node node) {
		return isType(node, "companion_object", "class_body", "import_list");
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		if (!node.getType().equals("import_header")) {
			return List.of();
		}
		Optional<Node> identifier = childOfType(node, "identifier");
		if (identifier.isEmpty()) {
			return List.of();
		}
		String qualified = identifier.get().getText();
		if (childOfType(node, "wildcard_import").isPresent()) {
			return List.of(ImportIR.wildcard(qualified));
		}
		String alias = childOfType(node, "import_alias").flatMap(a -> childOfType(a, "type_identifier"))
				.map(Node::getText).orElse(null);
		int dot = qualified.lastIndexOf('.');
		return List.of(ImportIR.symbol(dot < 0 ? "" : qualified.substring(0, dot), qualified.substring(dot + 1), alias));
	}

	// ---- types ---------------------------------------------------------------------------------

	@Override
	protected Optional<TypeDecl> typeOf(Node node) {
		if (!isType(node, "class_declaration", "object_declaration")) {
			return Optional.empty();
		}
		Optional<Node> name = childOfType(node, "type_identifier");
		if (name.isEmpty()) {
			return Optional.empty();
		}
		boolean isInterface = node.getChildren().stream().anyMatch(child -> child.getType().equals("interface"));
		boolean isEnum = childOfType(node, "modifiers").map(modifiers -> modifiers.getText().contains("enum")).orElse(false);
		TypeKind kind = node.getType().equals("object_declaration") ? TypeKind.OBJECT
				: isInterface ? TypeKind.INTERFACE : isEnum ? TypeKind.ENUM : TypeKind.CLASS;

		List<TypeRefIR> extendsTypes = new ArrayList<>();
		List<TypeRefIR> implementsTypes = new ArrayList<>();
		for (Node specifier : childrenOfType(node, "delegation_specifier")) {
			Optional<Node> invocation = childOfType(specifier, "constructor_invocation");
			Optional<TypeRefIR> type = invocation.or(() -> Optional.of(specifier))
					.flatMap(source -> childOfType(source, "user_type")).flatMap(KotlinExtractor::typeRefOf);
			if (type.isEmpty()) {
				continue;
			}
			if (kind == TypeKind.INTERFACE || invocation.isPresent()) {
				extendsTypes.add(type.get());
			}
			else {
				implementsTypes.add(type.get());
			}
		}

		List<VariableIR> fields = new ArrayList<>();
		List<Node> members = new ArrayList<>();
		childOfType(node, "primary_constructor").ifPresent(constructor -> {
			members.add(constructor);
			for (Node parameter : childrenOfType(constructor, "class_parameter")) {
				if (childOfType(parameter, "binding_pattern_kind").isPresent()) {
					childOfType(parameter, "simple_identifier").ifPresent(parameterName -> fields.add(
							new VariableIR(parameterName.getText(), typeOfParameter(parameter))));
				}
			}
		});
		childOfType(node, "class_body").ifPresent(body -> members.addAll(body.getNamedChildren()));
		childOfType(node, "enum_class_body").ifPresent(body -> members.addAll(body.getNamedChildren()));
		return Optional.of(new TypeDecl(name.get().getText(), kind, Declaration.FULL, extendsTypes, implementsTypes,
				members, fields));
	}

	// ---- functions -----------------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		switch (node.getType()) {
			case "function_declaration" -> {
				Optional<Node> name = childOfType(node, "simple_identifier");
				if (name.isEmpty()) {
					return Optional.empty();
				}
				List<VariableIR> parameters = childOfType(node, "function_value_parameters")
						.map(KotlinExtractor::parameters).orElse(List.of());
				TypeRefIR returnType = node.getNamedChildren().stream()
						.filter(child -> isType(child, "user_type", "nullable_type"))
						.findFirst().flatMap(KotlinExtractor::typeRefOf).orElse(null);
				return Optional.of(new FunctionDecl(name.get().getText(), false, parameters, returnType,
						childOfType(node, "function_body").orElse(null)));
			}
			case "primary_constructor" -> {
				List<VariableIR> parameters = new ArrayList<>();
				for (Node parameter : childrenOfType(node, "class_parameter")) {
					childOfType(parameter, "simple_identifier").ifPresent(name ->
							parameters.add(new VariableIR(name.getText(), typeOfParameter(parameter))));
				}
				return enclosingClassName(node).map(name -> new FunctionDecl(name, true, parameters, null, null));
			}
			case "secondary_constructor" -> {
				List<VariableIR> parameters = childOfType(node, "function_value_parameters")
						.map(KotlinExtractor::parameters).orElse(List.of());
				return enclosingClassName(node).map(name -> new FunctionDecl(name, true, parameters, null,
						childOfType(node, "constructor_delegation_call").orElse(null)));
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	private static Optional<String> enclosingClassName(Node constructor) {
		Optional<Node> parent = constructor.getParent();
		while (parent.isPresent() && !isType(parent.get(), "class_declaration", "object_declaration")) {
			parent = parent.get().getParent();
		}
		return parent.flatMap(clazz -> childOfType(clazz, "type_identifier")).map(Node::getText);
	}

	private static List<VariableIR> parameters(Node list) {
		List<VariableIR> result = new ArrayList<>();
		for (Node parameter : childrenOfType(list, "parameter")) {
			childOfType(parameter, "simple_identifier")
					.ifPresent(name -> result.add(new VariableIR(name.getText(), typeOfParameter(parameter))));
		}
		return result;
	}

	private static TypeRefIR typeOfParameter(Node parameter) {
		return parameter.getNamedChildren().stream().filter(child -> isType(child, "user_type", "nullable_type"))
				.findFirst().flatMap(KotlinExtractor::typeRefOf).orElse(null);
	}

	// ---- members and bodies -------------------------------------------------------------------

	@Override
	protected List<VariableIR> fieldsOf(Node node) {
		return node.getType().equals("property_declaration") ? properties(node) : List.of();
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		return node.getType().equals("property_declaration") ? properties(node) : List.of();
	}

	/** {@code val x: Foo = ...} or {@code val x = Foo(...)}. */
	private static List<VariableIR> properties(Node node) {
		Optional<Node> declaration = childOfType(node, "variable_declaration");
		if (declaration.isEmpty()) {
			return List.of();
		}
		Optional<Node> name = childOfType(declaration.get(), "simple_identifier");
		if (name.isEmpty()) {
			return List.of();
		}
		TypeRefIR type = declaration.get().getNamedChildren().stream()
				.filter(child -> isType(child, "user_type", "nullable_type")).findFirst()
				.flatMap(KotlinExtractor::typeRefOf)
				.or(() -> childOfType(node, "call_expression").flatMap(KotlinExtractor::calleeName)
						.map(TypeRefIR::of))
				.orElse(null);
		return List.of(new VariableIR(name.get().getText(), type));
	}

	/** The name of an unqualified callee: {@code HashMap()}, {@code createService()}. */
	private static Optional<String> calleeName(Node call) {
		return call.getNamedChildren().stream().findFirst()
				.filter(callee -> callee.getType().equals("simple_identifier")).map(Node::getText);
	}

	@Override
	protected Optional<CallIR> callOf(Node node) {
		if (!node.getType().equals("call_expression")) {
			return Optional.empty();
		}
		List<Node> children = node.getNamedChildren();
		if (children.isEmpty()) {
			return Optional.empty();
		}
		int arguments = childOfType(node, "call_suffix").flatMap(suffix -> childOfType(suffix, "value_arguments"))
				.map(Node::getNamedChildCount).orElse(0);
		Node callee = children.getFirst();
		if (callee.getType().equals("simple_identifier")) {
			return Optional.of(new CallIR(callee.getText(), null, arguments, lineOf(node)));
		}
		if (callee.getType().equals("navigation_expression")) {
			List<Node> parts = callee.getNamedChildren();
			Optional<Node> suffix = parts.stream().filter(part -> part.getType().equals("navigation_suffix")).reduce((a, b) -> b);
			if (suffix.isPresent() && parts.size() >= 2) {
				String name = childOfType(suffix.get(), "simple_identifier").map(Node::getText).orElse(null);
				String receiver = truncate(parts.getFirst().getText().replace("?.", "."), 200);
				if (name != null) {
					return Optional.of(new CallIR(name, receiver.equals("super") ? "super" : receiver, arguments,
							lineOf(node)));
				}
			}
		}
		return Optional.empty();
	}

	/** {@code a.b.C<T>?} to base name and type arguments. */
	private static Optional<TypeRefIR> typeRefOf(Node node) {
		List<String> names = new ArrayList<>();
		collect(node, names);
		return names.isEmpty() ? Optional.empty() : Optional.of(new TypeRefIR(names.getFirst(), names.subList(1, names.size())));
	}

	private static void collect(Node node, List<String> names) {
		switch (node.getType()) {
			case "user_type" -> {
				List<String> parts = childrenOfType(node, "type_identifier").stream().map(Node::getText).toList();
				if (!parts.isEmpty()) {
					names.add(String.join(".", parts));
				}
				childOfType(node, "type_arguments").ifPresent(arguments -> collect(arguments, names));
			}
			case "nullable_type", "type_arguments", "type_projection" -> node.getNamedChildren()
					.forEach(child -> collect(child, names));
			default -> {
			}
		}
	}
}
