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

/**
 * Go: structs and interfaces, functions, and methods.
 *
 * <p>Go declares a method apart from its type, possibly in another file of the package, so a method with
 * a receiver becomes an {@code EXTENSION} of that type. Embedded fields are recorded as {@code extends}
 * (composition is Go's inheritance). A package is a directory, and an import names that directory, so the
 * module name of a file is its directory.
 */
@Component
public class GoExtractor extends TreeSitterExtractor {

	private static final Set<String> TYPE_NAMES = Set.of("type_identifier", "qualified_type");
	private static final Set<String> TYPE_WRAPPERS = Set.of("pointer_type", "slice_type", "array_type", "map_type",
			"channel_type", "generic_type", "type_arguments", "type_elem", "parenthesized_type");

	@Override
	public boolean supports(Language language) {
		return language == Language.GO;
	}

	@Override
	protected Language language() {
		return Language.GO;
	}

	@Override
	protected String grammar() {
		return "go";
	}

	@Override
	protected String moduleName(SourceFile file, Node root) {
		return directory(file.relativePath()).replace('/', '.');
	}

	@Override
	protected String groupName(SourceFile file, String moduleName) {
		return directory(file.relativePath());
	}

	private static String directory(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	@Override
	protected List<Node> unwrap(Node node) {
		return node.getType().equals("type_declaration") ? node.getNamedChildren() : List.of();
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		if (!node.getType().equals("import_declaration")) {
			return List.of();
		}
		List<ImportIR> result = new ArrayList<>();
		List<Node> specs = new ArrayList<>(childrenOfType(node, "import_spec"));
		childOfType(node, "import_spec_list").ifPresent(list -> specs.addAll(childrenOfType(list, "import_spec")));
		for (Node spec : specs) {
			Optional<Node> path = fieldOf(spec, "path");
			if (path.isEmpty()) {
				continue;
			}
			String module = path.get().getText().replace("\"", "").replace("`", "");
			Optional<Node> name = fieldOf(spec, "name");
			if (name.isPresent() && name.get().getType().equals("blank_identifier")) {
				continue; // imported for side effects only
			}
			if (name.isPresent() && name.get().getType().equals("dot")) {
				result.add(ImportIR.wildcard(module));
			}
			else {
				result.add(ImportIR.module(module, name.map(Node::getText).orElse(packageNameOf(module))));
			}
		}
		return result;
	}

	/** The usual package name of an import path: the last element, skipping a {@code /v2} version suffix. */
	static String packageNameOf(String importPath) {
		String[] parts = importPath.split("/");
		String last = parts[parts.length - 1];
		if (parts.length > 1 && last.matches("v\\d+")) {
			last = parts[parts.length - 2];
		}
		return last.replace('-', '_').replace('.', '_');
	}

	// ---- types ---------------------------------------------------------------------------------

	@Override
	protected Optional<TypeDecl> typeOf(Node node) {
		if (!node.getType().equals("type_spec")) {
			return Optional.empty();
		}
		Optional<Node> name = fieldOf(node, "name");
		Optional<Node> type = fieldOf(node, "type");
		if (name.isEmpty() || type.isEmpty()) {
			return Optional.empty();
		}
		if (type.get().getType().equals("struct_type")) {
			List<TypeRefIR> embedded = new ArrayList<>();
			List<VariableIR> fields = new ArrayList<>();
			childOfType(type.get(), "field_declaration_list").ifPresent(list -> {
				for (Node field : childrenOfType(list, "field_declaration")) {
					TypeRefIR fieldType = fieldOf(field, "type").flatMap(GoExtractor::typeRefOf).orElse(null);
					List<Node> names = field.getChildrenByFieldName("name");
					if (names.isEmpty() && fieldType != null) {
						embedded.add(fieldType); // embedded struct
						fields.add(new VariableIR(simpleName(fieldType.name()), fieldType));
					}
					for (Node fieldName : names) {
						fields.add(new VariableIR(fieldName.getText(), fieldType));
					}
				}
			});
			return Optional.of(new TypeDecl(name.get().getText(), TypeKind.STRUCT,
					com.abhiraj.archyra.ir.Declaration.FULL, embedded, List.of(), List.of(), fields));
		}
		if (type.get().getType().equals("interface_type")) {
			List<TypeRefIR> embedded = new ArrayList<>();
			for (Node element : childrenOfType(type.get(), "type_elem")) {
				typeRefOf(element).ifPresent(embedded::add);
			}
			return Optional.of(new TypeDecl(name.get().getText(), TypeKind.INTERFACE, embedded, List.of(),
					childrenOfType(type.get(), "method_elem")));
		}
		return Optional.empty();
	}

	private static String simpleName(String qualified) {
		return qualified.substring(qualified.lastIndexOf('.') + 1);
	}

	// ---- functions -----------------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		switch (node.getType()) {
			case "function_declaration" -> {
				return fieldOf(node, "name").map(name -> new FunctionDecl(name.getText(), false, parameters(node),
						result(node), fieldOf(node, "body").orElse(null)));
			}
			case "method_declaration" -> {
				Optional<Node> name = fieldOf(node, "name");
				Optional<Node> receiver = fieldOf(node, "receiver").flatMap(list -> childOfType(list, "parameter_declaration"));
				if (name.isEmpty() || receiver.isEmpty()) {
					return Optional.empty();
				}
				TypeRefIR receiverType = fieldOf(receiver.get(), "type").flatMap(GoExtractor::typeRefOf).orElse(null);
				if (receiverType == null) {
					return Optional.empty();
				}
				List<VariableIR> variables = new ArrayList<>();
				fieldOf(receiver.get(), "name").ifPresent(variable -> variables.add(new VariableIR(variable.getText(), receiverType)));
				return Optional.of(new FunctionDecl(name.get().getText(), false, parameters(node), result(node),
						fieldOf(node, "body").orElse(null), receiverType.name(), variables, List.of()));
			}
			case "method_elem" -> {
				return fieldOf(node, "name").map(name -> new FunctionDecl(name.getText(), false, parameters(node),
						result(node), null));
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	private static List<VariableIR> parameters(Node function) {
		List<VariableIR> result = new ArrayList<>();
		fieldOf(function, "parameters").ifPresent(list -> {
			for (Node declaration : list.getNamedChildren()) {
				if (!isType(declaration, "parameter_declaration", "variadic_parameter_declaration")) {
					continue;
				}
				TypeRefIR type = fieldOf(declaration, "type").flatMap(GoExtractor::typeRefOf).orElse(null);
				List<Node> names = declaration.getChildrenByFieldName("name");
				if (names.isEmpty()) {
					result.add(new VariableIR("_", type)); // unnamed parameter
				}
				for (Node name : names) {
					result.add(new VariableIR(name.getText(), type));
				}
			}
		});
		return result;
	}

	/** The first result: {@code (*User, error)} yields User. */
	private static TypeRefIR result(Node function) {
		Optional<Node> result = fieldOf(function, "result");
		if (result.isEmpty()) {
			return null;
		}
		if (result.get().getType().equals("parameter_list")) {
			return childOfType(result.get(), "parameter_declaration")
					.flatMap(declaration -> fieldOf(declaration, "type")).flatMap(GoExtractor::typeRefOf).orElse(null);
		}
		return typeRefOf(result.get()).orElse(null);
	}

	// ---- bodies --------------------------------------------------------------------------------

	@Override
	protected Optional<CallIR> callOf(Node node) {
		if (!node.getType().equals("call_expression")) {
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
		if (target.getType().equals("selector_expression")) {
			Optional<Node> operand = fieldOf(target, "operand");
			Optional<Node> field = fieldOf(target, "field");
			if (operand.isPresent() && field.isPresent()) {
				return Optional.of(new CallIR(field.get().getText(), truncate(operand.get().getText(), 200), arguments,
						lineOf(node)));
			}
		}
		return Optional.empty();
	}

	/** {@code Server{}} and {@code &Server{}}. */
	@Override
	protected Optional<TypeRefIR> instantiationOf(Node node) {
		return node.getType().equals("composite_literal")
				? fieldOf(node, "type").flatMap(GoExtractor::typeRefOf) : Optional.empty();
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		switch (node.getType()) {
			case "short_var_declaration" -> {
				List<Node> left = fieldOf(node, "left").map(Node::getNamedChildren).orElse(List.of());
				List<Node> right = fieldOf(node, "right").map(Node::getNamedChildren).orElse(List.of());
				List<VariableIR> result = new ArrayList<>();
				for (int i = 0; i < left.size(); i++) {
					if (left.get(i).getType().equals("identifier")) {
						TypeRefIR type = i < right.size() ? valueType(right.get(i)).orElse(null) : null;
						result.add(new VariableIR(left.get(i).getText(), type));
					}
				}
				return result;
			}
			case "var_spec" -> {
				TypeRefIR declared = fieldOf(node, "type").flatMap(GoExtractor::typeRefOf).orElse(null);
				List<Node> values = fieldOf(node, "value").map(Node::getNamedChildren).orElse(List.of());
				List<VariableIR> result = new ArrayList<>();
				List<Node> names = node.getChildrenByFieldName("name");
				for (int i = 0; i < names.size(); i++) {
					TypeRefIR type = declared != null ? declared
							: i < values.size() ? valueType(values.get(i)).orElse(null) : null;
					result.add(new VariableIR(names.get(i).getText(), type));
				}
				return result;
			}
			default -> {
				return List.of();
			}
		}
	}

	/**
	 * The type visible at a value: a composite literal, its address, or a call. A call is typed with the
	 * callee's name (the resolver reads that function's declared result).
	 */
	private static Optional<TypeRefIR> valueType(Node value) {
		switch (value.getType()) {
			case "unary_expression" -> {
				return fieldOf(value, "operand").flatMap(GoExtractor::valueType);
			}
			case "composite_literal" -> {
				return fieldOf(value, "type").flatMap(GoExtractor::typeRefOf);
			}
			case "call_expression" -> {
				return fieldOf(value, "function")
						.filter(function -> isType(function, "identifier", "selector_expression"))
						.map(function -> TypeRefIR.of(function.getText()));
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	private static Optional<TypeRefIR> typeRefOf(Node node) {
		return typeRef(node, TYPE_NAMES, TYPE_WRAPPERS);
	}
}
