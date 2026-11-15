package com.abhiraj.archyra.parsing.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * Ruby: classes, modules, methods, {@code require}s.
 *
 * <p>Ruby constants are global, so the module name is empty and a class is qualified only by its lexical
 * nesting ({@code App.Services.UserService}). {@code include}/{@code extend} are recorded as implemented
 * mixins. {@code Foo.new(...)} becomes a call named {@code Foo}, which the resolver treats as an
 * instantiation and links to {@code initialize}. {@code @repo} is normalised to {@code this.repo}.
 *
 * <p>A call without receiver and arguments cannot be told from a local variable read, so it is not recorded.
 */
@Component
public class RubyExtractor extends TreeSitterExtractor {

	private static final Set<String> MIXINS = Set.of("include", "extend", "prepend");

	@Override
	public boolean supports(Language language) {
		return language == Language.RUBY;
	}

	@Override
	protected Language language() {
		return Language.RUBY;
	}

	@Override
	protected String grammar() {
		return "ruby";
	}

	@Override
	protected String moduleName(SourceFile file, Node root) {
		return "";
	}

	@Override
	protected String groupName(SourceFile file, String moduleName) {
		int slash = file.relativePath().lastIndexOf('/');
		return slash < 0 ? "" : file.relativePath().substring(0, slash);
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		if (!node.getType().equals("call") || fieldOf(node, "receiver").isPresent()) {
			return List.of();
		}
		String method = fieldText(node, "method");
		if (!"require".equals(method) && !"require_relative".equals(method)) {
			return List.of();
		}
		return fieldOf(node, "arguments").flatMap(arguments -> childOfType(arguments, "string"))
				.flatMap(string -> childOfType(string, "string_content"))
				.map(content -> List.of(new ImportIR(content.getText(), null, null, null, false,
						method.equals("require_relative") ? 1 : 0)))
				.orElse(List.of());
	}

	// ---- types ---------------------------------------------------------------------------------

	@Override
	protected Optional<TypeDecl> typeOf(Node node) {
		if (!isType(node, "class", "module")) {
			return Optional.empty();
		}
		Optional<Node> name = fieldOf(node, "name");
		if (name.isEmpty()) {
			return Optional.empty();
		}
		List<TypeRefIR> extendsTypes = new ArrayList<>();
		fieldOf(node, "superclass").ifPresent(superclass -> superclass.getNamedChildren().stream()
				.filter(child -> isType(child, "constant", "scope_resolution")).findFirst()
				.ifPresent(parent -> extendsTypes.add(TypeRefIR.of(constantName(parent)))));

		List<Node> members = fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of());
		List<TypeRefIR> mixins = new ArrayList<>();
		for (Node member : members) {
			if (member.getType().equals("call") && fieldOf(member, "receiver").isEmpty()
					&& MIXINS.contains(fieldText(member, "method"))) {
				fieldOf(member, "arguments").ifPresent(arguments -> arguments.getNamedChildren().stream()
						.filter(argument -> isType(argument, "constant", "scope_resolution"))
						.forEach(argument -> mixins.add(TypeRefIR.of(constantName(argument)))));
			}
		}
		// the declared name may itself be scoped: class Foo::Bar
		String simple = constantName(name.get());
		// classes and modules are open: any file may reopen one, so declarations merge
		return Optional.of(new TypeDecl(simple.substring(simple.lastIndexOf('.') + 1),
				node.getType().equals("module") ? TypeKind.MODULE : TypeKind.CLASS, Declaration.PARTIAL, extendsTypes,
				mixins, members, List.of()));
	}

	/** {@code A::B} becomes {@code A.B}. */
	private static String constantName(Node node) {
		return node.getText().replace("::", ".");
	}

	// ---- methods -------------------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		if (!isType(node, "method", "singleton_method")) {
			return Optional.empty();
		}
		Optional<Node> name = fieldOf(node, "name");
		if (name.isEmpty()) {
			return Optional.empty();
		}
		List<VariableIR> parameters = new ArrayList<>();
		fieldOf(node, "parameters").ifPresent(list -> {
			for (Node parameter : list.getNamedChildren()) {
				switch (parameter.getType()) {
					case "identifier" -> parameters.add(new VariableIR(parameter.getText(), null));
					case "optional_parameter", "keyword_parameter" -> fieldOf(parameter, "name")
							.ifPresent(parameterName -> parameters.add(new VariableIR(parameterName.getText(), null)));
					default -> {
					}
				}
			}
		});
		boolean constructor = node.getType().equals("method") && name.get().getText().equals("initialize");
		Node body = fieldOf(node, "body").orElse(null);
		List<VariableIR> fields = constructor && body != null ? instanceVariables(body) : List.of();
		return Optional.of(new FunctionDecl(name.get().getText(), constructor, parameters, null, body, null, List.of(),
				fields));
	}

	/** {@code @x = Foo.new} in the constructor: a field, typed when the value is a constructor call. */
	private static List<VariableIR> instanceVariables(Node body) {
		List<VariableIR> fields = new ArrayList<>();
		Deque<Node> stack = new ArrayDeque<>();
		stack.push(body);
		while (!stack.isEmpty()) {
			Node node = stack.pop();
			if (node.getType().equals("assignment")) {
				Optional<Node> left = fieldOf(node, "left");
				if (left.isPresent() && left.get().getType().equals("instance_variable")) {
					TypeRefIR type = fieldOf(node, "right").flatMap(RubyExtractor::constructedType).orElse(null);
					fields.add(new VariableIR(left.get().getText().substring(1), type));
				}
			}
			List<Node> children = node.getNamedChildren();
			for (int i = children.size() - 1; i >= 0; i--) {
				stack.push(children.get(i));
			}
		}
		return fields;
	}

	/** {@code Foo.new(...)}: the constructed type. */
	private static Optional<TypeRefIR> constructedType(Node value) {
		if (value.getType().equals("call") && "new".equals(fieldText(value, "method"))) {
			return fieldOf(value, "receiver").filter(receiver -> isType(receiver, "constant", "scope_resolution"))
					.map(receiver -> TypeRefIR.of(constantName(receiver)));
		}
		return Optional.empty();
	}

	// ---- bodies --------------------------------------------------------------------------------

	@Override
	protected Optional<CallIR> callOf(Node node) {
		if (!node.getType().equals("call")) {
			return Optional.empty();
		}
		Optional<Node> method = fieldOf(node, "method");
		if (method.isEmpty() || !method.get().getType().equals("identifier")) {
			return Optional.empty();
		}
		String name = method.get().getText();
		int arguments = fieldOf(node, "arguments").map(Node::getNamedChildCount).orElse(0);
		Optional<Node> receiver = fieldOf(node, "receiver");

		if (receiver.isEmpty()) {
			return isKeyword(name) ? Optional.empty() : Optional.of(new CallIR(name, null, arguments, lineOf(node)));
		}
		Node target = receiver.get();
		if (name.equals("new") && isType(target, "constant", "scope_resolution")) {
			// Foo.new(args) is an instantiation: a call named like the class
			return Optional.of(new CallIR(constantName(target), null, arguments, lineOf(node)));
		}
		return Optional.of(new CallIR(name, receiverText(target), arguments, lineOf(node)));
	}

	private static boolean isKeyword(String name) {
		return MIXINS.contains(name) || Set.of("require", "require_relative", "attr_reader", "attr_writer",
				"attr_accessor", "private", "public", "protected", "puts", "raise").contains(name);
	}

	/** {@code self} is {@code this}; {@code @repo} is {@code this.repo}; {@code A::B} is {@code A.B}. */
	private static String receiverText(Node receiver) {
		String text = truncate(receiver.getText(), 200).replace("::", ".");
		if (text.equals("self")) {
			return "this";
		}
		if (text.startsWith("@") && !text.startsWith("@@")) {
			return "this." + text.substring(1);
		}
		return text;
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		if (!node.getType().equals("assignment")) {
			return List.of();
		}
		Optional<Node> left = fieldOf(node, "left");
		if (left.isEmpty() || !left.get().getType().equals("identifier")) {
			return List.of();
		}
		TypeRefIR type = fieldOf(node, "right").flatMap(RubyExtractor::constructedType).orElse(null);
		return List.of(new VariableIR(left.get().getText(), type));
	}
}
