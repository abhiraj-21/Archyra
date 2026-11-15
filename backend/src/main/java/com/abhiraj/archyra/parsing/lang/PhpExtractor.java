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
 * PHP: classes, interfaces, traits, enums; methods and functions; {@code use} imports. The namespace
 * separator {@code \} is normalised to a dot. Constructor promotion
 * ({@code __construct(private Repo $r)}) declares fields, and {@code use Trait;} inside a class is recorded
 * as implementing the trait.
 */
@Component
public class PhpExtractor extends TreeSitterExtractor {

	private static final Set<String> TYPE_NAMES = Set.of("named_type", "primitive_type");
	private static final Set<String> TYPE_WRAPPERS = Set.of("optional_type", "union_type", "intersection_type",
			"disjunctive_normal_form_type");

	@Override
	public boolean supports(Language language) {
		return language == Language.PHP;
	}

	@Override
	protected Language language() {
		return Language.PHP;
	}

	@Override
	protected String grammar() {
		return "php";
	}

	@Override
	protected String moduleName(SourceFile file, Node root) {
		return root.getNamedChildren().stream()
				.filter(child -> child.getType().equals("namespace_definition"))
				.findFirst()
				.map(namespace -> fieldText(namespace, "name"))
				.map(PhpExtractor::normalize)
				.orElse("");
	}

	/** {@code \App\Models\User} becomes {@code App.Models.User}. */
	static String normalize(String name) {
		if (name == null) {
			return "";
		}
		String trimmed = name.startsWith("\\") ? name.substring(1) : name;
		return trimmed.replace('\\', '.');
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		if (!node.getType().equals("namespace_use_declaration")) {
			return List.of();
		}
		List<ImportIR> result = new ArrayList<>();
		Optional<Node> group = fieldOf(node, "body");
		if (group.isPresent()) {
			// use App\Contracts\{Repo, Other as O};
			String prefix = node.getNamedChildren().stream().filter(child -> child.getType().equals("namespace_name"))
					.findFirst().map(Node::getText).map(PhpExtractor::normalize).orElse("");
			for (Node clause : childrenOfType(group.get(), "namespace_use_clause")) {
				addClause(prefix, clause, result);
			}
		}
		else {
			for (Node clause : childrenOfType(node, "namespace_use_clause")) {
				addClause("", clause, result);
			}
		}
		return result;
	}

	private static void addClause(String prefix, Node clause, List<ImportIR> out) {
		String alias = fieldText(clause, "alias");
		Optional<Node> qualified = childOfType(clause, "qualified_name");
		String full;
		if (qualified.isPresent()) {
			full = normalize(qualified.get().getText());
		}
		else {
			full = childOfType(clause, "name").map(Node::getText).orElse(null);
		}
		if (full == null) {
			return;
		}
		if (!prefix.isEmpty()) {
			full = prefix + "." + full;
		}
		int dot = full.lastIndexOf('.');
		out.add(ImportIR.symbol(dot < 0 ? "" : full.substring(0, dot), full.substring(dot + 1), alias));
	}

	// ---- types ---------------------------------------------------------------------------------

	@Override
	protected Optional<TypeDecl> typeOf(Node node) {
		TypeKind kind = switch (node.getType()) {
			case "class_declaration" -> TypeKind.CLASS;
			case "interface_declaration" -> TypeKind.INTERFACE;
			case "trait_declaration" -> TypeKind.TRAIT;
			case "enum_declaration" -> TypeKind.ENUM;
			default -> null;
		};
		Optional<Node> name = fieldOf(node, "name");
		if (kind == null || name.isEmpty()) {
			return Optional.empty();
		}
		List<TypeRefIR> extendsTypes = new ArrayList<>();
		List<TypeRefIR> implementsTypes = new ArrayList<>();
		childOfType(node, "base_clause").ifPresent(clause -> clause.getNamedChildren()
				.forEach(base -> nameRef(base).ifPresent(extendsTypes::add)));
		childOfType(node, "class_interface_clause").ifPresent(clause -> clause.getNamedChildren()
				.forEach(contract -> nameRef(contract).ifPresent(implementsTypes::add)));

		List<Node> members = fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of());
		for (Node member : members) {
			if (member.getType().equals("use_declaration")) { // use Loggable;
				member.getNamedChildren().forEach(used -> nameRef(used).ifPresent(implementsTypes::add));
			}
		}
		return Optional.of(new TypeDecl(name.get().getText(), kind, Declaration.FULL, extendsTypes, implementsTypes,
				members, List.of()));
	}

	private static Optional<TypeRefIR> nameRef(Node node) {
		if (isType(node, "name", "qualified_name", "named_type")) {
			return Optional.of(TypeRefIR.of(normalize(node.getText())));
		}
		return Optional.empty();
	}

	// ---- functions -----------------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		if (!isType(node, "method_declaration", "function_definition")) {
			return Optional.empty();
		}
		Optional<Node> name = fieldOf(node, "name");
		if (name.isEmpty()) {
			return Optional.empty();
		}
		List<VariableIR> parameters = new ArrayList<>();
		List<VariableIR> promoted = new ArrayList<>();
		fieldOf(node, "parameters").ifPresent(list -> {
			for (Node parameter : list.getNamedChildren()) {
				if (!isType(parameter, "simple_parameter", "property_promotion_parameter", "variadic_parameter")) {
					continue;
				}
				Optional<Node> variable = fieldOf(parameter, "name");
				if (variable.isEmpty()) {
					continue;
				}
				String parameterName = variableName(variable.get());
				TypeRefIR type = fieldOf(parameter, "type").flatMap(PhpExtractor::typeRefOf).orElse(null);
				parameters.add(new VariableIR(parameterName, type));
				if (parameter.getType().equals("property_promotion_parameter")) {
					promoted.add(new VariableIR(parameterName, type));
				}
			}
		});
		TypeRefIR returnType = fieldOf(node, "return_type").flatMap(PhpExtractor::typeRefOf).orElse(null);
		if (returnType != null && returnType.name().equals("void")) {
			returnType = null;
		}
		boolean constructor = name.get().getText().equals("__construct");
		return Optional.of(new FunctionDecl(name.get().getText(), constructor, parameters, returnType,
				fieldOf(node, "body").orElse(null), null, List.of(), promoted));
	}

	private static String variableName(Node variable) {
		return childOfType(variable, "name").map(Node::getText).orElse(variable.getText().replace("$", ""));
	}

	@Override
	protected List<VariableIR> fieldsOf(Node node) {
		if (!node.getType().equals("property_declaration")) {
			return List.of();
		}
		TypeRefIR type = fieldOf(node, "type").flatMap(PhpExtractor::typeRefOf).orElse(null);
		List<VariableIR> result = new ArrayList<>();
		for (Node element : childrenOfType(node, "property_element")) {
			fieldOf(element, "name").ifPresent(name -> result.add(new VariableIR(variableName(name), type)));
		}
		return result;
	}

	// ---- bodies --------------------------------------------------------------------------------

	@Override
	protected Optional<CallIR> callOf(Node node) {
		switch (node.getType()) {
			case "member_call_expression", "nullsafe_member_call_expression" -> {
				Optional<Node> name = fieldOf(node, "name");
				Optional<Node> object = fieldOf(node, "object");
				if (name.isEmpty() || object.isEmpty()) {
					return Optional.empty();
				}
				return Optional.of(new CallIR(name.get().getText(), receiver(object.get().getText()),
						argumentCount(node), lineOf(node)));
			}
			case "scoped_call_expression" -> {
				Optional<Node> name = fieldOf(node, "name");
				Optional<Node> scope = fieldOf(node, "scope");
				if (name.isEmpty() || scope.isEmpty()) {
					return Optional.empty();
				}
				String text = scope.get().getText();
				String receiver = switch (text) {
					case "self", "static" -> "this";
					case "parent" -> "super";
					default -> normalize(text);
				};
				return Optional.of(new CallIR(name.get().getText(), receiver, argumentCount(node), lineOf(node)));
			}
			case "function_call_expression" -> {
				Optional<Node> function = fieldOf(node, "function");
				if (function.isPresent() && isType(function.get(), "name", "qualified_name")) {
					String full = normalize(function.get().getText());
					return Optional.of(new CallIR(full.substring(full.lastIndexOf('.') + 1), null,
							argumentCount(node), lineOf(node)));
				}
				return Optional.empty();
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	private static int argumentCount(Node call) {
		return fieldOf(call, "arguments").map(Node::getNamedChildCount).orElse(0);
	}

	/** {@code $this->repo} becomes {@code this.repo}; {@code $u} becomes {@code u}. */
	private static String receiver(String text) {
		return truncate(text, 200).replace("?->", ".").replace("->", ".").replace("$", "");
	}

	@Override
	protected Optional<TypeRefIR> instantiationOf(Node node) {
		return node.getType().equals("object_creation_expression")
				? node.getNamedChildren().stream().filter(child -> isType(child, "name", "qualified_name"))
						.findFirst().map(name -> TypeRefIR.of(normalize(name.getText())))
				: Optional.empty();
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		if (!node.getType().equals("assignment_expression")) {
			return List.of();
		}
		Optional<Node> left = fieldOf(node, "left");
		Optional<Node> right = fieldOf(node, "right");
		if (left.isEmpty() || !left.get().getType().equals("variable_name") || right.isEmpty()) {
			return List.of();
		}
		TypeRefIR type = instantiationOf(right.get()).orElse(null);
		return List.of(new VariableIR(variableName(left.get()), type));
	}

	private static Optional<TypeRefIR> typeRefOf(Node node) {
		return typeRef(node, TYPE_NAMES, TYPE_WRAPPERS)
				.map(ref -> new TypeRefIR(normalize(ref.name()), ref.typeArguments()));
	}
}
