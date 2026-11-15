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
 * Rust: structs, enums, traits, {@code impl} blocks, functions, {@code use} declarations.
 *
 * <p>An {@code impl} block adds methods (and, for {@code impl Trait for Type}, an implemented trait) to a type
 * declared elsewhere, so it becomes an {@code EXTENSION}. Smart pointers and options are looked through:
 * a field of type {@code Box<dyn Repo>} is typed {@code Repo}, so calls on it resolve. Path separators
 * {@code ::} are normalised to dots.
 */
@Component
public class RustExtractor extends TreeSitterExtractor {

	private static final Set<String> TYPE_NAMES = Set.of("type_identifier", "scoped_type_identifier",
			"primitive_type");
	private static final Set<String> TYPE_WRAPPERS = Set.of("generic_type", "type_arguments", "reference_type",
			"pointer_type", "dynamic_type", "abstract_type", "array_type", "tuple_type", "bounded_type",
			"slice_type");
	/** Containers that are looked through when typing a value. */
	private static final Set<String> TRANSPARENT = Set.of("Box", "Rc", "Arc", "RefCell", "Mutex", "RwLock", "Option",
			"Cell", "Cow", "Pin");

	@Override
	public boolean supports(Language language) {
		return language == Language.RUST;
	}

	@Override
	protected Language language() {
		return Language.RUST;
	}

	@Override
	protected String grammar() {
		return "rust";
	}

	/** {@code src/a/b.rs} and {@code src/a/b/mod.rs} are {@code src.a.b}; {@code src/lib.rs} is {@code src}. */
	@Override
	protected String moduleName(SourceFile file, Node root) {
		String path = file.relativePath();
		String withoutExtension = path.endsWith(".rs") ? path.substring(0, path.length() - 3) : path;
		int slash = withoutExtension.lastIndexOf('/');
		String name = withoutExtension.substring(slash + 1);
		String directory = slash < 0 ? "" : withoutExtension.substring(0, slash);
		if (name.equals("mod") || name.equals("lib") || name.equals("main")) {
			return directory.replace('/', '.');
		}
		return withoutExtension.replace('/', '.');
	}

	@Override
	protected String groupName(SourceFile file, String moduleName) {
		int slash = file.relativePath().lastIndexOf('/');
		return slash < 0 ? "" : file.relativePath().substring(0, slash);
	}

	@Override
	protected boolean isContainer(Node node) {
		return node.getType().equals("mod_item") && fieldOf(node, "body").isPresent();
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		switch (node.getType()) {
			case "use_declaration" -> {
				List<ImportIR> result = new ArrayList<>();
				fieldOf(node, "argument").ifPresent(argument -> collectUse(argument, "", result));
				return result;
			}
			case "mod_item" -> {
				// `mod inner;` declares a child module in inner.rs or inner/mod.rs
				if (fieldOf(node, "body").isEmpty()) {
					return fieldOf(node, "name").map(name -> List.of(ImportIR.module("self." + name.getText(), null)))
							.orElse(List.of());
				}
				return List.of();
			}
			default -> {
				return List.of();
			}
		}
	}

	/** Flattens {@code use a::b::{C, d::E as F, *}} into one import per name. */
	private static void collectUse(Node node, String prefix, List<ImportIR> out) {
		switch (node.getType()) {
			case "identifier", "crate", "self", "super", "scoped_identifier" -> {
				String path = join(prefix, node.getText().replace("::", "."));
				addUse(path, null, out);
			}
			case "use_as_clause" -> {
				String path = join(prefix, fieldText(node, "path").replace("::", "."));
				addUse(path, fieldText(node, "alias"), out);
			}
			case "use_wildcard" -> node.getNamedChildren().stream().findFirst().ifPresentOrElse(
					path -> out.add(wildcard(join(prefix, path.getText().replace("::", ".")))),
					() -> out.add(wildcard(prefix)));
			case "scoped_use_list" -> {
				String base = join(prefix, fieldOf(node, "path").map(path -> path.getText().replace("::", ".")).orElse(""));
				fieldOf(node, "list").ifPresent(list -> list.getNamedChildren().forEach(item -> collectUse(item, base, out)));
			}
			case "use_list" -> node.getNamedChildren().forEach(item -> collectUse(item, prefix, out));
			default -> {
			}
		}
	}

	private static String join(String prefix, String path) {
		return prefix.isEmpty() ? path : path.isEmpty() ? prefix : prefix + "." + path;
	}

	/** {@code a.b.C} is the symbol C of module {@code a.b}; {@code self}-prefixed and {@code super} paths are relative. */
	private static void addUse(String path, String alias, List<ImportIR> out) {
		int level = 0;
		String rest = path;
		while (rest.equals("super") || rest.startsWith("super.")) {
			level++;
			rest = rest.equals("super") ? "" : rest.substring("super.".length());
		}
		if (rest.isEmpty()) {
			return;
		}
		int dot = rest.lastIndexOf('.');
		if (dot < 0) {
			// `use serde;` or `use super::x;` → the module/symbol directly under the base
			if (level > 0) {
				out.add(new ImportIR("", rest, null, alias, false, level));
			}
			else {
				out.add(ImportIR.module(rest, alias));
			}
			return;
		}
		out.add(new ImportIR(rest.substring(0, dot), rest.substring(dot + 1), null, alias, false, level));
	}

	private static ImportIR wildcard(String path) {
		int level = 0;
		String rest = path;
		while (rest.equals("super") || rest.startsWith("super.")) {
			level++;
			rest = rest.equals("super") ? "" : rest.substring("super.".length());
		}
		return new ImportIR(rest, null, null, null, true, level);
	}

	// ---- types ---------------------------------------------------------------------------------

	@Override
	protected Optional<TypeDecl> typeOf(Node node) {
		switch (node.getType()) {
			case "struct_item" -> {
				Optional<Node> name = fieldOf(node, "name");
				if (name.isEmpty()) {
					return Optional.empty();
				}
				List<VariableIR> fields = new ArrayList<>();
				fieldOf(node, "body").ifPresent(body -> {
					for (Node field : childrenOfType(body, "field_declaration")) {
						fieldOf(field, "name").ifPresent(fieldName -> fields.add(new VariableIR(fieldName.getText(),
								fieldOf(field, "type").flatMap(RustExtractor::typeRefOf).orElse(null))));
					}
				});
				return Optional.of(new TypeDecl(name.get().getText(), TypeKind.STRUCT, Declaration.FULL, List.of(),
						List.of(), List.of(), fields));
			}
			case "enum_item" -> {
				return fieldOf(node, "name").map(name -> new TypeDecl(name.getText(), TypeKind.ENUM, List.of(),
						List.of(), List.of()));
			}
			case "trait_item" -> {
				Optional<Node> name = fieldOf(node, "name");
				if (name.isEmpty()) {
					return Optional.empty();
				}
				List<TypeRefIR> supertraits = new ArrayList<>();
				fieldOf(node, "bounds").ifPresent(bounds -> bounds.getNamedChildren()
						.forEach(bound -> typeRefOf(bound).ifPresent(supertraits::add)));
				return Optional.of(new TypeDecl(name.get().getText(), TypeKind.TRAIT, supertraits, List.of(),
						fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of())));
			}
			case "impl_item" -> {
				Optional<TypeRefIR> target = fieldOf(node, "type").flatMap(RustExtractor::typeRefOf);
				if (target.isEmpty()) {
					return Optional.empty();
				}
				List<TypeRefIR> traits = new ArrayList<>();
				fieldOf(node, "trait").flatMap(RustExtractor::typeRefOf).ifPresent(traits::add);
				String name = target.get().name();
				return Optional.of(new TypeDecl(name.substring(name.lastIndexOf('.') + 1), TypeKind.STRUCT,
						Declaration.EXTENSION, List.of(), traits,
						fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of()), List.of()));
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	// ---- functions -----------------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		if (!isType(node, "function_item", "function_signature_item")) {
			return Optional.empty();
		}
		Optional<Node> name = fieldOf(node, "name");
		if (name.isEmpty()) {
			return Optional.empty();
		}
		List<VariableIR> parameters = new ArrayList<>();
		fieldOf(node, "parameters").ifPresent(list -> {
			for (Node parameter : childrenOfType(list, "parameter")) {
				String parameterName = fieldOf(parameter, "pattern").filter(p -> p.getType().equals("identifier"))
						.map(Node::getText).orElse("_");
				parameters.add(new VariableIR(parameterName,
						fieldOf(parameter, "type").flatMap(RustExtractor::typeRefOf).orElse(null)));
			}
		});
		TypeRefIR returnType = fieldOf(node, "return_type").flatMap(RustExtractor::typeRefOf).orElse(null);
		return Optional.of(new FunctionDecl(name.get().getText(), false, parameters, returnType,
				fieldOf(node, "body").orElse(null)));
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
		switch (target.getType()) {
			case "identifier" -> {
				return Optional.of(new CallIR(target.getText(), null, arguments, lineOf(node)));
			}
			case "scoped_identifier" -> {
				// Type::new(), module::function()
				Optional<Node> path = fieldOf(target, "path");
				Optional<Node> name = fieldOf(target, "name");
				if (path.isPresent() && name.isPresent()) {
					return Optional.of(new CallIR(name.get().getText(), receiver(path.get().getText().replace("::", ".")),
							arguments, lineOf(node)));
				}
				return Optional.empty();
			}
			case "field_expression" -> {
				Optional<Node> value = fieldOf(target, "value");
				Optional<Node> field = fieldOf(target, "field");
				if (value.isPresent() && field.isPresent()) {
					return Optional.of(new CallIR(field.get().getText(), receiver(truncate(value.get().getText(), 200)),
							arguments, lineOf(node)));
				}
				return Optional.empty();
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	/** {@code self} and {@code Self} are the enclosing type. */
	private static String receiver(String text) {
		if (text.equals("self") || text.equals("Self")) {
			return "this";
		}
		return text.startsWith("self.") ? "this" + text.substring(4) : text;
	}

	@Override
	protected Optional<TypeRefIR> instantiationOf(Node node) {
		return node.getType().equals("struct_expression")
				? fieldOf(node, "name").map(name -> TypeRefIR.of(name.getText().replace("::", "."))) : Optional.empty();
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		if (!node.getType().equals("let_declaration")) {
			return List.of();
		}
		Optional<Node> pattern = fieldOf(node, "pattern");
		if (pattern.isEmpty() || !pattern.get().getType().equals("identifier")) {
			return List.of();
		}
		TypeRefIR type = fieldOf(node, "type").flatMap(RustExtractor::typeRefOf)
				.or(() -> fieldOf(node, "value").flatMap(RustExtractor::valueType))
				.orElse(null);
		return List.of(new VariableIR(pattern.get().getText(), type));
	}

	/** A struct literal, or a call typed by its callee ({@code Server::new} → the resolver reads {@code Self}). */
	private static Optional<TypeRefIR> valueType(Node value) {
		switch (value.getType()) {
			case "struct_expression" -> {
				return fieldOf(value, "name").map(name -> TypeRefIR.of(name.getText().replace("::", ".")));
			}
			case "call_expression" -> {
				return fieldOf(value, "function")
						.filter(function -> isType(function, "identifier", "scoped_identifier"))
						.map(function -> TypeRefIR.of(function.getText().replace("::", ".")));
			}
			case "try_expression", "reference_expression" -> {
				return value.getNamedChildren().stream().findFirst().flatMap(RustExtractor::valueType);
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	/** Base name first, with smart pointers and options looked through; paths use dots. */
	private static Optional<TypeRefIR> typeRefOf(Node node) {
		Optional<TypeRefIR> ref = typeRef(node, TYPE_NAMES, TYPE_WRAPPERS);
		if (ref.isEmpty()) {
			return ref;
		}
		List<String> names = new ArrayList<>();
		names.add(ref.get().name().replace("::", "."));
		ref.get().typeArguments().forEach(argument -> names.add(argument.replace("::", ".")));
		while (names.size() > 1 && TRANSPARENT.contains(names.getFirst())) {
			names.removeFirst();
		}
		return Optional.of(new TypeRefIR(names.getFirst(), names.subList(1, names.size())));
	}
}
