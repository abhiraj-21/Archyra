package com.abhiraj.archyra.parsing.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.CallIR;
import com.abhiraj.archyra.ir.ImportIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.TypeKind;
import com.abhiraj.archyra.ir.TypeRefIR;
import com.abhiraj.archyra.ir.VariableIR;
import com.abhiraj.archyra.parsing.TreeSitterExtractor;

import io.github.treesitter.jtreesitter.Node;

/** Java: classes, interfaces, enums, records and annotation types; methods and constructors; imports. */
@Component
public class JavaExtractor extends TreeSitterExtractor {

	private static final int MAX_RECEIVER_LENGTH = 200;
	private static final Set<String> TYPE_NAMES = Set.of("type_identifier", "scoped_type_identifier",
			"integral_type", "floating_point_type", "boolean_type");
	private static final Set<String> TYPE_WRAPPERS = Set.of("generic_type", "array_type", "annotated_type",
			"type_arguments", "wildcard");

	@Override
	public boolean supports(Language language) {
		return language == Language.JAVA;
	}

	@Override
	protected Language language() {
		return Language.JAVA;
	}

	@Override
	protected String grammar() {
		return "java";
	}

	@Override
	protected String moduleName(SourceFile file, Node root) {
		return root.getNamedChildren().stream()
				.filter(child -> child.getType().equals("package_declaration"))
				.findFirst()
				.flatMap(declaration -> declaration.getNamedChildren().stream()
						.filter(child -> isType(child, "scoped_identifier", "identifier"))
						.findFirst())
				.map(Node::getText)
				.orElse("");
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		if (!node.getType().equals("import_declaration")) {
			return List.of();
		}
		boolean isStatic = node.getChildren().stream().anyMatch(child -> child.getType().equals("static"));
		boolean wildcard = node.getNamedChildren().stream().anyMatch(child -> child.getType().equals("asterisk"));
		Optional<String> name = node.getNamedChildren().stream()
				.filter(child -> isType(child, "scoped_identifier", "identifier"))
				.findFirst()
				.map(Node::getText);
		if (name.isEmpty()) {
			return List.of();
		}
		String qualified = name.get();

		if (!isStatic) {
			if (wildcard) {
				return List.of(ImportIR.wildcard(qualified));
			}
			return List.of(ImportIR.symbol(parentOf(qualified), lastOf(qualified)));
		}
		// import static a.b.Type.member  /  import static a.b.Type.*
		String typeName = wildcard ? qualified : parentOf(qualified);
		String member = wildcard ? "*" : lastOf(qualified);
		return List.of(new ImportIR(parentOf(typeName), lastOf(typeName), member, null, false, 0));
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
			case "interface_declaration" -> TypeKind.INTERFACE;
			case "enum_declaration" -> TypeKind.ENUM;
			case "record_declaration" -> TypeKind.RECORD;
			case "annotation_type_declaration" -> TypeKind.ANNOTATION;
			default -> null;
		};
		Optional<Node> name = fieldOf(node, "name");
		if (kind == null || name.isEmpty()) {
			return Optional.empty();
		}

		List<TypeRefIR> extendsTypes = new ArrayList<>();
		List<TypeRefIR> implementsTypes = new ArrayList<>();
		if (kind == TypeKind.INTERFACE) {
			childOfType(node, "extends_interfaces").ifPresent(extended -> collectTypeList(extended, extendsTypes));
		}
		else {
			fieldOf(node, "superclass").ifPresent(superclass -> collectTypeList(superclass, extendsTypes));
			fieldOf(node, "interfaces").ifPresent(interfaces -> collectTypeList(interfaces, implementsTypes));
		}

		List<VariableIR> fields = new ArrayList<>();
		if (kind == TypeKind.RECORD) {
			fieldOf(node, "parameters").ifPresent(parameters -> fields.addAll(parameters(parameters)));
		}
		List<Node> members = fieldOf(node, "body").map(JavaExtractor::members).orElse(List.of());
		return Optional.of(new TypeDecl(name.get().getText(), kind, com.abhiraj.archyra.ir.Declaration.FULL,
				extendsTypes, implementsTypes, members, fields));
	}

	/** Members of a type body; enum members sit one level deeper, after the constants. */
	private static List<Node> members(Node body) {
		if (body.getType().equals("enum_body")) {
			return childOfType(body, "enum_body_declarations").map(Node::getNamedChildren).orElse(List.of());
		}
		return body.getNamedChildren();
	}

	private void collectTypeList(Node container, List<TypeRefIR> out) {
		for (Node child : container.getNamedChildren()) {
			if (child.getType().equals("type_list")) {
				collectTypeList(child, out);
			}
			else {
				typeRefOf(child).ifPresent(out::add);
			}
		}
	}

	// ---- functions -----------------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		if (!isType(node, "method_declaration", "constructor_declaration", "compact_constructor_declaration")) {
			return Optional.empty();
		}
		boolean constructor = !node.getType().equals("method_declaration");
		String name = fieldOf(node, "name").map(Node::getText).orElse("");
		List<VariableIR> parameters = fieldOf(node, "parameters").map(JavaExtractor::parameters).orElse(List.of());
		TypeRefIR returnType = constructor ? null : fieldOf(node, "type").flatMap(JavaExtractor::typeRefOf).orElse(null);
		if (returnType != null && returnType.name().equals("void")) {
			returnType = null;
		}
		return Optional.of(new FunctionDecl(name, constructor, parameters, returnType,
				fieldOf(node, "body").orElse(null)));
	}

	private static List<VariableIR> parameters(Node formalParameters) {
		List<VariableIR> result = new ArrayList<>();
		for (Node parameter : formalParameters.getNamedChildren()) {
			switch (parameter.getType()) {
				case "formal_parameter" -> fieldOf(parameter, "name").ifPresent(name -> result.add(new VariableIR(
						name.getText(), fieldOf(parameter, "type").flatMap(JavaExtractor::typeRefOf).orElse(null))));
				case "spread_parameter" -> {
					Optional<TypeRefIR> type = parameter.getNamedChildren().stream()
							.filter(child -> !isType(child, "variable_declarator", "modifiers"))
							.map(JavaExtractor::typeRefOf)
							.flatMap(Optional::stream)
							.findFirst();
					childOfType(parameter, "variable_declarator").flatMap(declarator -> fieldOf(declarator, "name"))
							.ifPresent(name -> result.add(new VariableIR(name.getText(), type.orElse(null))));
				}
				default -> {
				}
			}
		}
		return result;
	}

	@Override
	protected List<VariableIR> fieldsOf(Node node) {
		return isType(node, "field_declaration", "constant_declaration") ? variables(node) : List.of();
	}

	// ---- bodies --------------------------------------------------------------------------------

	@Override
	protected Optional<CallIR> callOf(Node node) {
		if (!node.getType().equals("method_invocation")) {
			return Optional.empty();
		}
		return fieldOf(node, "name").map(name -> {
			String receiver = fieldOf(node, "object").map(JavaExtractor::receiverText).orElse(null);
			int arguments = fieldOf(node, "arguments").map(Node::getNamedChildCount).orElse(0);
			return new CallIR(name.getText(), receiver, arguments, lineOf(node));
		});
	}

	@Override
	protected Optional<TypeRefIR> instantiationOf(Node node) {
		return node.getType().equals("object_creation_expression")
				? fieldOf(node, "type").flatMap(JavaExtractor::typeRefOf) : Optional.empty();
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		return switch (node.getType()) {
			case "local_variable_declaration" -> variables(node);
			case "enhanced_for_statement", "resource" -> fieldOf(node, "name")
					.map(name -> List.of(new VariableIR(name.getText(),
							fieldOf(node, "type").flatMap(JavaExtractor::typeRefOf).orElse(null))))
					.orElse(List.of());
			default -> List.of();
		};
	}

	/** Variables of a field or local declaration: one per declarator, all sharing the declared type. */
	private static List<VariableIR> variables(Node declaration) {
		TypeRefIR type = fieldOf(declaration, "type").flatMap(JavaExtractor::typeRefOf).orElse(null);
		List<VariableIR> result = new ArrayList<>();
		for (Node declarator : declaration.getChildrenByFieldName("declarator")) {
			fieldOf(declarator, "name").ifPresent(name -> result.add(new VariableIR(name.getText(), type)));
		}
		return result;
	}

	private static String receiverText(Node receiver) {
		return truncate(receiver.getText(), MAX_RECEIVER_LENGTH);
	}

	private static Optional<TypeRefIR> typeRefOf(Node node) {
		return typeRef(node, TYPE_NAMES, TYPE_WRAPPERS).filter(type -> !type.name().equals("var"));
	}
}
