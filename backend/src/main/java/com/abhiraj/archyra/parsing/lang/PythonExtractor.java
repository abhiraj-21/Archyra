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
 * Python: classes, functions and methods, imports, calls.
 *
 * <p>Python has no declared types for most values, so receivers are typed from what is visible:
 * annotations, class-level fields, {@code self.x = x} assignments in {@code __init__} (typed by the
 * parameter annotation or the constructor call on the right) and {@code x = Foo(...)} locals.
 * {@code self} and {@code cls} are normalised to {@code this}.
 */
@Component
public class PythonExtractor extends TreeSitterExtractor {

	private static final Set<String> TYPE_NAMES = Set.of("identifier", "attribute");
	private static final Set<String> TYPE_WRAPPERS = Set.of("type", "subscript", "generic_type", "type_parameter",
			"union_type", "binary_operator", "tuple", "list");

	@Override
	public boolean supports(Language language) {
		return language == Language.PYTHON;
	}

	@Override
	protected Language language() {
		return Language.PYTHON;
	}

	@Override
	protected String grammar() {
		return "python";
	}

	/** {@code app/services/user.py} is {@code app.services.user}; {@code app/__init__.py} is {@code app}. */
	@Override
	protected String moduleName(SourceFile file, Node root) {
		return modulePath(file.relativePath());
	}

	static String modulePath(String path) {
		String withoutExtension = path.endsWith(".py") ? path.substring(0, path.length() - 3)
				: path.endsWith(".ipynb") ? path.substring(0, path.length() - 6) : path;
		if (withoutExtension.equals("__init__")) {
			return "";
		}
		if (withoutExtension.endsWith("/__init__")) {
			withoutExtension = withoutExtension.substring(0, withoutExtension.length() - "/__init__".length());
		}
		return withoutExtension.replace('/', '.');
	}

	/** The package a module lives in; a package's {@code __init__} is the package itself. */
	@Override
	protected String groupName(SourceFile file, String moduleName) {
		if (file.relativePath().endsWith("__init__.py")) {
			return moduleName;
		}
		int dot = moduleName.lastIndexOf('.');
		return dot < 0 ? "" : moduleName.substring(0, dot);
	}

	@Override
	protected List<Node> unwrap(Node node) {
		return node.getType().equals("decorated_definition") ? fieldOf(node, "definition").stream().toList() : List.of();
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		return switch (node.getType()) {
			case "import_statement" -> importStatement(node);
			case "import_from_statement" -> importFrom(node);
			default -> List.of();
		};
	}

	private static List<ImportIR> importStatement(Node node) {
		List<ImportIR> result = new ArrayList<>();
		for (Node name : node.getChildrenByFieldName("name")) {
			if (name.getType().equals("aliased_import")) {
				result.add(ImportIR.module(fieldText(name, "name"), fieldText(name, "alias")));
			}
			else {
				result.add(ImportIR.module(name.getText(), null));
			}
		}
		return result;
	}

	private static List<ImportIR> importFrom(Node node) {
		Node moduleNode = fieldOf(node, "module_name").orElse(null);
		if (moduleNode == null) {
			return List.of();
		}
		int level = 0;
		String module = "";
		if (moduleNode.getType().equals("relative_import")) {
			level = childOfType(moduleNode, "import_prefix").map(prefix -> prefix.getText().length()).orElse(0);
			module = childOfType(moduleNode, "dotted_name").map(Node::getText).orElse("");
		}
		else {
			module = moduleNode.getText();
		}
		if (module.equals("__future__")) {
			return List.of();
		}

		List<ImportIR> result = new ArrayList<>();
		for (Node child : node.getNamedChildren()) {
			if (child.getType().equals("wildcard_import")) {
				result.add(new ImportIR(module, null, null, null, true, level));
			}
		}
		for (Node name : node.getChildrenByFieldName("name")) {
			if (name.getType().equals("aliased_import")) {
				result.add(new ImportIR(module, fieldText(name, "name"), null, fieldText(name, "alias"), false, level));
			}
			else {
				result.add(new ImportIR(module, name.getText(), null, null, false, level));
			}
		}
		return result;
	}

	// ---- types ---------------------------------------------------------------------------------

	@Override
	protected Optional<TypeDecl> typeOf(Node node) {
		if (!node.getType().equals("class_definition")) {
			return Optional.empty();
		}
		Optional<Node> name = fieldOf(node, "name");
		if (name.isEmpty()) {
			return Optional.empty();
		}
		List<TypeRefIR> bases = new ArrayList<>();
		fieldOf(node, "superclasses").ifPresent(superclasses -> {
			for (Node base : superclasses.getNamedChildren()) {
				if (isType(base, "identifier", "attribute")) {
					bases.add(TypeRefIR.of(base.getText()));
				}
				else if (base.getType().equals("subscript")) {
					typeRefOf(base).ifPresent(bases::add);
				}
			}
		});
		List<Node> members = fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of());
		return Optional.of(new TypeDecl(name.get().getText(), TypeKind.CLASS, bases, List.of(), members));
	}

	@Override
	protected List<VariableIR> fieldsOf(Node node) {
		// class-level `x: Foo = ...` or `x = Foo()`
		if (!node.getType().equals("expression_statement")) {
			return List.of();
		}
		return node.getNamedChildren().stream()
				.filter(child -> child.getType().equals("assignment"))
				.map(PythonExtractor::assignedVariable)
				.flatMap(Optional::stream)
				.toList();
	}

	// ---- functions -----------------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		if (!node.getType().equals("function_definition")) {
			return Optional.empty();
		}
		Optional<Node> name = fieldOf(node, "name");
		if (name.isEmpty()) {
			return Optional.empty();
		}
		List<VariableIR> parameters = fieldOf(node, "parameters").map(PythonExtractor::parameters).orElse(List.of());
		TypeRefIR returnType = fieldOf(node, "return_type").flatMap(PythonExtractor::typeRefOf).orElse(null);
		Node body = fieldOf(node, "body").orElse(null);
		boolean constructor = name.get().getText().equals("__init__");
		List<VariableIR> propertyFields = constructor && body != null ? instanceFields(body, parameters) : List.of();
		return Optional.of(new FunctionDecl(name.get().getText(), constructor, parameters, returnType, body, null,
				List.of(), propertyFields));
	}

	private static List<VariableIR> parameters(Node parameters) {
		List<VariableIR> result = new ArrayList<>();
		for (Node parameter : parameters.getNamedChildren()) {
			switch (parameter.getType()) {
				case "identifier" -> result.add(new VariableIR(parameter.getText(), null));
				case "typed_parameter" -> childOfType(parameter, "identifier").ifPresent(name -> result.add(
						new VariableIR(name.getText(), fieldOf(parameter, "type").flatMap(PythonExtractor::typeRefOf).orElse(null))));
				case "default_parameter" -> fieldOf(parameter, "name")
						.ifPresent(name -> result.add(new VariableIR(name.getText(), null)));
				case "typed_default_parameter" -> fieldOf(parameter, "name").ifPresent(name -> result.add(
						new VariableIR(name.getText(), fieldOf(parameter, "type").flatMap(PythonExtractor::typeRefOf).orElse(null))));
				default -> {
				}
			}
		}
		// the receiver is not an argument
		if (!result.isEmpty() && (result.getFirst().name().equals("self") || result.getFirst().name().equals("cls"))) {
			result.removeFirst();
		}
		return result;
	}

	/** {@code self.x = ...} assignments in a constructor, typed from the parameter or the constructor call. */
	private static List<VariableIR> instanceFields(Node body, List<VariableIR> parameters) {
		List<VariableIR> fields = new ArrayList<>();
		java.util.Deque<Node> stack = new java.util.ArrayDeque<>();
		stack.push(body);
		while (!stack.isEmpty()) {
			Node node = stack.pop();
			if (node.getType().equals("assignment")) {
				Optional<Node> left = fieldOf(node, "left");
				if (left.isPresent() && left.get().getType().equals("attribute")
						&& fieldText(left.get(), "object") != null && fieldText(left.get(), "object").equals("self")) {
					String name = fieldText(left.get(), "attribute");
					TypeRefIR type = fieldOf(node, "type").flatMap(PythonExtractor::typeRefOf)
							.or(() -> fieldOf(node, "right").flatMap(right -> valueType(right, parameters)))
							.orElse(null);
					fields.add(new VariableIR(name, type));
				}
			}
			List<Node> children = node.getNamedChildren();
			for (int i = children.size() - 1; i >= 0; i--) {
				stack.push(children.get(i));
			}
		}
		return fields;
	}

	/** The type of a right-hand side we can see: a typed parameter, or a call to a constructor. */
	private static Optional<TypeRefIR> valueType(Node value, List<VariableIR> parameters) {
		if (value.getType().equals("identifier")) {
			return parameters.stream().filter(parameter -> parameter.name().equals(value.getText()))
					.map(VariableIR::type).filter(java.util.Objects::nonNull).findFirst();
		}
		if (value.getType().equals("call")) {
			return fieldOf(value, "function")
					.filter(function -> isType(function, "identifier", "attribute"))
					.map(function -> TypeRefIR.of(function.getText()));
		}
		return Optional.empty();
	}

	// ---- bodies --------------------------------------------------------------------------------

	@Override
	protected Optional<CallIR> callOf(Node node) {
		if (!node.getType().equals("call")) {
			return Optional.empty();
		}
		Optional<Node> function = fieldOf(node, "function");
		if (function.isEmpty()) {
			return Optional.empty();
		}
		int arguments = fieldOf(node, "arguments").map(PythonExtractor::argumentCount).orElse(0);
		Node target = function.get();
		if (target.getType().equals("identifier")) {
			return Optional.of(new CallIR(target.getText(), null, arguments, lineOf(node)));
		}
		if (target.getType().equals("attribute")) {
			Optional<Node> object = fieldOf(target, "object");
			Optional<Node> attribute = fieldOf(target, "attribute");
			if (object.isPresent() && attribute.isPresent()) {
				return Optional.of(new CallIR(attribute.get().getText(), receiver(object.get()), arguments, lineOf(node)));
			}
		}
		return Optional.empty();
	}

	private static int argumentCount(Node arguments) {
		return (int) arguments.getNamedChildren().stream().filter(child -> !child.getType().equals("comment")).count();
	}

	/** {@code self} and {@code cls} become {@code this}; {@code super()} becomes {@code super}. */
	private static String receiver(Node object) {
		String text = truncate(object.getText(), 200);
		if (object.getType().equals("call") && fieldText(object, "function") != null
				&& fieldText(object, "function").equals("super")) {
			return "super";
		}
		if (text.equals("self") || text.equals("cls")) {
			return "this";
		}
		if (text.startsWith("self.")) {
			return "this" + text.substring(4);
		}
		if (text.startsWith("cls.")) {
			return "this" + text.substring(3);
		}
		return text;
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		if (!node.getType().equals("assignment")) {
			return List.of();
		}
		return assignedVariable(node).stream().toList();
	}

	/** {@code x: Foo = ...} or {@code x = Foo(...)}: the variable and the type we can see for it. */
	private static Optional<VariableIR> assignedVariable(Node assignment) {
		Optional<Node> left = fieldOf(assignment, "left");
		if (left.isEmpty() || !left.get().getType().equals("identifier")) {
			return Optional.empty();
		}
		TypeRefIR type = fieldOf(assignment, "type").flatMap(PythonExtractor::typeRefOf)
				.or(() -> fieldOf(assignment, "right").flatMap(right -> valueType(right, List.of())))
				.orElse(null);
		return Optional.of(new VariableIR(left.get().getText(), type));
	}

	private static Optional<TypeRefIR> typeRefOf(Node node) {
		return typeRef(node, TYPE_NAMES, TYPE_WRAPPERS);
	}
}
