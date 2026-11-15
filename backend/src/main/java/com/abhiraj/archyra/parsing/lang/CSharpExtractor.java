package com.abhiraj.archyra.parsing.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * C#: classes, structs, interfaces, enums, records; methods, constructors and properties; {@code using}
 * directives. A {@code using Namespace;} brings a whole namespace into scope, so it is a wildcard import.
 *
 * <p>A base list does not say which entry is the base class. By the .NET naming convention an entry
 * named {@code IFoo} is an interface; the first other entry of a class is its base class.
 */
@Component
public class CSharpExtractor extends TreeSitterExtractor {

	private static final Set<String> TYPE_NAMES = Set.of("identifier", "qualified_name", "predefined_type");
	private static final Set<String> TYPE_WRAPPERS = Set.of("generic_name", "type_argument_list", "nullable_type",
			"array_type", "tuple_type", "tuple_element", "ref_type");

	@Override
	public boolean supports(Language language) {
		return language == Language.CSHARP;
	}

	@Override
	protected Language language() {
		return Language.CSHARP;
	}

	@Override
	protected String grammar() {
		return "c-sharp";
	}

	/** The first namespace declared in the file (block or file-scoped). */
	@Override
	protected String moduleName(SourceFile file, Node root) {
		for (Node child : root.getNamedChildren()) {
			if (isType(child, "namespace_declaration", "file_scoped_namespace_declaration")) {
				return fieldText(child, "name") != null ? fieldText(child, "name") : "";
			}
		}
		return "";
	}

	@Override
	protected List<Node> unwrap(Node node) {
		return node.getType().equals("namespace_declaration")
				? fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of()) : List.of();
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		if (!node.getType().equals("using_directive")) {
			return List.of();
		}
		Optional<Node> alias = fieldOf(node, "name");
		Optional<Node> target = node.getNamedChildren().stream()
				.filter(child -> isType(child, "identifier", "qualified_name") && !child.equals(alias.orElse(null)))
				.reduce((first, second) -> second);
		if (target.isEmpty()) {
			return List.of();
		}
		String qualified = target.get().getText();
		boolean isStatic = node.getChildren().stream().anyMatch(child -> child.getType().equals("static"));

		if (alias.isPresent()) { // using Alias = Acme.Models.User;
			return List.of(ImportIR.symbol(parentOf(qualified), lastOf(qualified), alias.get().getText()));
		}
		if (isStatic) { // using static System.Math;
			return List.of(new ImportIR(parentOf(qualified), lastOf(qualified), "*", null, false, 0));
		}
		return List.of(ImportIR.wildcard(qualified));
	}

	private static String parentOf(String qualified) {
		int dot = qualified.lastIndexOf('.');
		return dot < 0 ? "" : qualified.substring(0, dot);
	}

	private static String lastOf(String qualified) {
		return qualified.substring(qualified.lastIndexOf('.') + 1);
	}

	// ---- types ---------------------------------------------------------------------------------

	@Override
	protected Optional<TypeDecl> typeOf(Node node) {
		TypeKind kind = switch (node.getType()) {
			case "class_declaration" -> TypeKind.CLASS;
			case "struct_declaration" -> TypeKind.STRUCT;
			case "interface_declaration" -> TypeKind.INTERFACE;
			case "enum_declaration" -> TypeKind.ENUM;
			case "record_declaration", "record_struct_declaration" -> TypeKind.RECORD;
			default -> null;
		};
		Optional<Node> name = fieldOf(node, "name");
		if (kind == null || name.isEmpty()) {
			return Optional.empty();
		}

		boolean partial = node.getNamedChildren().stream()
				.anyMatch(child -> child.getType().equals("modifier") && child.getText().equals("partial"));
		List<TypeRefIR> extendsTypes = new ArrayList<>();
		List<TypeRefIR> implementsTypes = new ArrayList<>();
		childOfType(node, "base_list").ifPresent(list -> {
			boolean baseClassTaken = false;
			for (Node base : list.getNamedChildren()) {
				Optional<TypeRefIR> ref = typeRefOf(base);
				if (ref.isEmpty()) {
					continue;
				}
				if (kind == TypeKind.INTERFACE) {
					extendsTypes.add(ref.get());
				}
				else if (!baseClassTaken && (kind == TypeKind.CLASS || kind == TypeKind.RECORD)
						&& !looksLikeInterface(ref.get().name())) {
					extendsTypes.add(ref.get());
					baseClassTaken = true;
				}
				else {
					implementsTypes.add(ref.get());
				}
			}
		});

		List<VariableIR> fields = new ArrayList<>();
		if (kind == TypeKind.RECORD) {
			childOfType(node, "parameter_list").ifPresent(parameters -> fields.addAll(parameters(parameters)));
		}
		List<Node> members = fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of());
		return Optional.of(new TypeDecl(name.get().getText(), kind, partial ? Declaration.PARTIAL : Declaration.FULL,
				extendsTypes, implementsTypes, members, fields));
	}

	/** {@code IRepository}: an I followed by an upper-case letter. */
	private static boolean looksLikeInterface(String name) {
		String simple = name.substring(name.lastIndexOf('.') + 1);
		return simple.length() > 1 && simple.charAt(0) == 'I' && Character.isUpperCase(simple.charAt(1));
	}

	// ---- members -------------------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		if (!isType(node, "method_declaration", "constructor_declaration")) {
			return Optional.empty();
		}
		Optional<Node> name = fieldOf(node, "name");
		if (name.isEmpty()) {
			return Optional.empty();
		}
		boolean constructor = node.getType().equals("constructor_declaration");
		List<VariableIR> parameters = fieldOf(node, "parameters").map(CSharpExtractor::parameters).orElse(List.of());
		TypeRefIR returnType = constructor ? null
				: fieldOf(node, "returns").flatMap(CSharpExtractor::typeRefOf).orElse(null);
		if (returnType != null && returnType.name().equals("void")) {
			returnType = null;
		}
		return Optional.of(new FunctionDecl(name.get().getText(), constructor, parameters, returnType,
				fieldOf(node, "body").orElse(null)));
	}

	private static List<VariableIR> parameters(Node list) {
		List<VariableIR> result = new ArrayList<>();
		for (Node parameter : childrenOfType(list, "parameter")) {
			fieldOf(parameter, "name").ifPresent(name -> result.add(new VariableIR(name.getText(),
					fieldOf(parameter, "type").flatMap(CSharpExtractor::typeRefOf).orElse(null))));
		}
		return result;
	}

	@Override
	protected List<VariableIR> fieldsOf(Node node) {
		switch (node.getType()) {
			case "field_declaration" -> {
				return childOfType(node, "variable_declaration").map(CSharpExtractor::variables).orElse(List.of());
			}
			case "property_declaration" -> {
				return fieldOf(node, "name").map(name -> List.of(new VariableIR(name.getText(),
						fieldOf(node, "type").flatMap(CSharpExtractor::typeRefOf).orElse(null)))).orElse(List.of());
			}
			default -> {
				return List.of();
			}
		}
	}

	// ---- bodies --------------------------------------------------------------------------------

	@Override
	protected Optional<CallIR> callOf(Node node) {
		if (!node.getType().equals("invocation_expression")) {
			return Optional.empty();
		}
		Optional<Node> function = fieldOf(node, "function");
		if (function.isEmpty()) {
			return Optional.empty();
		}
		int arguments = fieldOf(node, "arguments").map(Node::getNamedChildCount).orElse(0);
		Node target = function.get();
		if (target.getType().equals("identifier")) {
			return Optional.of(new CallIR(target.getText(), null, arguments, lineOf(node)));
		}
		if (target.getType().equals("generic_name")) {
			return childOfType(target, "identifier")
					.map(name -> new CallIR(name.getText(), null, arguments, lineOf(node)));
		}
		if (target.getType().equals("member_access_expression")) {
			Optional<Node> name = fieldOf(target, "name");
			Optional<Node> receiver = fieldOf(target, "expression");
			if (name.isPresent()) {
				String methodName = name.get().getType().equals("generic_name")
						? childOfType(name.get(), "identifier").map(Node::getText).orElse(name.get().getText())
						: name.get().getText();
				return Optional.of(new CallIR(methodName, receiver.map(CSharpExtractor::receiver).orElse(null),
						arguments, lineOf(node)));
			}
		}
		return Optional.empty();
	}

	/** {@code base} is the superclass. */
	private static String receiver(Node expression) {
		String text = truncate(expression.getText(), 200);
		return text.equals("base") ? "super" : text.startsWith("base.") ? "super" + text.substring(4) : text;
	}

	@Override
	protected Optional<TypeRefIR> instantiationOf(Node node) {
		return node.getType().equals("object_creation_expression")
				? fieldOf(node, "type").flatMap(CSharpExtractor::typeRefOf) : Optional.empty();
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		return node.getType().equals("variable_declaration") ? variables(node) : List.of();
	}

	/** One variable per declarator; {@code var x = new Foo()} is typed by the initialiser. */
	private static List<VariableIR> variables(Node declaration) {
		TypeRefIR declared = fieldOf(declaration, "type").flatMap(CSharpExtractor::typeRefOf).orElse(null);
		List<VariableIR> result = new ArrayList<>();
		for (Node declarator : childrenOfType(declaration, "variable_declarator")) {
			Optional<Node> name = fieldOf(declarator, "name");
			if (name.isEmpty()) {
				continue;
			}
			TypeRefIR type = declared != null ? declared
					: childOfType(declarator, "object_creation_expression")
							.flatMap(creation -> fieldOf(creation, "type")).flatMap(CSharpExtractor::typeRefOf)
							.orElse(null);
			result.add(new VariableIR(name.get().getText(), type));
		}
		return result;
	}

	private static Optional<TypeRefIR> typeRefOf(Node node) {
		return typeRef(node, TYPE_NAMES, TYPE_WRAPPERS);
	}
}
