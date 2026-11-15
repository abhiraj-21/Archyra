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
 * Solidity smart contracts: contracts, interfaces, libraries, structs, enums; functions, constructors and
 * state variables; imports. A file is its own module and an import names a file, so
 * {@code import "./Token.sol"} brings that file's contracts into scope. An inheritance entry named
 * {@code IFoo} is taken to be an interface, the rest are base contracts.
 */
@Component
public class SolidityExtractor extends TreeSitterExtractor {

	private static final Set<String> TYPE_NAMES = Set.of("user_defined_type", "primitive_type");
	private static final Set<String> TYPE_WRAPPERS = Set.of("type_name");

	@Override
	public boolean supports(Language language) {
		return language == Language.SOLIDITY;
	}

	@Override
	protected Language language() {
		return Language.SOLIDITY;
	}

	@Override
	protected String grammar() {
		return "solidity";
	}

	@Override
	protected String moduleName(SourceFile file, Node root) {
		String path = file.relativePath();
		int dot = path.lastIndexOf('.');
		return (dot > path.lastIndexOf('/') ? path.substring(0, dot) : path).replace('/', '.');
	}

	@Override
	protected String groupName(SourceFile file, String moduleName) {
		int slash = file.relativePath().lastIndexOf('/');
		return slash < 0 ? "" : file.relativePath().substring(0, slash);
	}

	// ---- imports ------------------------------------------------------------------------------

	@Override
	protected List<ImportIR> importsOf(Node node) {
		if (!node.getType().equals("import_directive")) {
			return List.of();
		}
		String source = fieldOf(node, "source").map(string -> unquote(string.getText())).orElse(null);
		if (source == null) {
			return List.of();
		}
		List<ImportIR> result = new ArrayList<>();
		String pendingName = null;
		String pendingAlias = null;
		String moduleAlias = null;
		for (Node child : node.getNamedChildren()) {
			String field = node.getFieldNameForNamedChild(node.getNamedChildren().indexOf(child));
			if ("import_name".equals(field)) {
				if (pendingName != null) {
					result.add(ImportIR.symbol(source, pendingName, pendingAlias));
				}
				pendingName = child.getText();
				pendingAlias = null;
			}
			else if ("alias".equals(field)) {
				if (pendingName != null) {
					pendingAlias = child.getText();
				}
				else {
					moduleAlias = child.getText();
				}
			}
		}
		if (pendingName != null) {
			result.add(ImportIR.symbol(source, pendingName, pendingAlias));
		}
		if (result.isEmpty()) {
			result.add(moduleAlias != null ? ImportIR.module(source, moduleAlias) : ImportIR.wildcard(source));
		}
		return result;
	}

	private static String unquote(String text) {
		return text.length() >= 2 ? text.substring(1, text.length() - 1) : text;
	}

	// ---- types ---------------------------------------------------------------------------------

	@Override
	protected Optional<TypeDecl> typeOf(Node node) {
		TypeKind kind = switch (node.getType()) {
			case "contract_declaration" -> TypeKind.CLASS;
			case "interface_declaration" -> TypeKind.INTERFACE;
			case "library_declaration" -> TypeKind.OBJECT;
			case "struct_declaration" -> TypeKind.STRUCT;
			case "enum_declaration" -> TypeKind.ENUM;
			default -> null;
		};
		Optional<Node> name = fieldOf(node, "name");
		if (kind == null || name.isEmpty()) {
			return Optional.empty();
		}
		List<TypeRefIR> extendsTypes = new ArrayList<>();
		List<TypeRefIR> implementsTypes = new ArrayList<>();
		for (Node specifier : childrenOfType(node, "inheritance_specifier")) {
			fieldOf(specifier, "ancestor").flatMap(SolidityExtractor::typeRefOf).ifPresent(ancestor -> {
				if (kind == TypeKind.INTERFACE || !looksLikeInterface(ancestor.name())) {
					extendsTypes.add(ancestor);
				}
				else {
					implementsTypes.add(ancestor);
				}
			});
		}

		List<VariableIR> fields = new ArrayList<>();
		if (kind == TypeKind.STRUCT) {
			fieldOf(node, "body").ifPresent(body -> {
				for (Node member : childrenOfType(body, "struct_member")) {
					fieldOf(member, "name").ifPresent(memberName -> fields.add(new VariableIR(memberName.getText(),
							fieldOf(member, "type").flatMap(SolidityExtractor::typeRefOf).orElse(null))));
				}
			});
		}
		List<Node> members = kind == TypeKind.STRUCT || kind == TypeKind.ENUM ? List.of()
				: fieldOf(node, "body").map(Node::getNamedChildren).orElse(List.of());
		return Optional.of(new TypeDecl(name.get().getText(), kind, Declaration.FULL, extendsTypes, implementsTypes,
				members, fields));
	}

	/** {@code IVault}: an I followed by an upper-case letter. */
	private static boolean looksLikeInterface(String name) {
		String simple = name.substring(name.lastIndexOf('.') + 1);
		return simple.length() > 1 && simple.charAt(0) == 'I' && Character.isUpperCase(simple.charAt(1));
	}

	// ---- functions and state -------------------------------------------------------------------

	@Override
	protected Optional<FunctionDecl> functionOf(Node node) {
		switch (node.getType()) {
			case "function_definition" -> {
				Optional<Node> name = fieldOf(node, "name");
				if (name.isEmpty()) {
					return Optional.empty();
				}
				TypeRefIR returnType = fieldOf(node, "return_type")
						.flatMap(definition -> childOfType(definition, "parameter"))
						.flatMap(parameter -> fieldOf(parameter, "type")).flatMap(SolidityExtractor::typeRefOf)
						.orElse(null);
				return Optional.of(new FunctionDecl(name.get().getText(), false, parameters(node), returnType,
						fieldOf(node, "body").orElse(null)));
			}
			case "constructor_definition" -> {
				return enclosingContract(node).map(name -> new FunctionDecl(name, true, parameters(node), null,
						fieldOf(node, "body").orElse(null)));
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	private static Optional<String> enclosingContract(Node node) {
		Optional<Node> parent = node.getParent();
		while (parent.isPresent() && !isType(parent.get(), "contract_declaration", "library_declaration")) {
			parent = parent.get().getParent();
		}
		return parent.flatMap(contract -> fieldOf(contract, "name")).map(Node::getText);
	}

	private static List<VariableIR> parameters(Node function) {
		List<VariableIR> result = new ArrayList<>();
		for (Node parameter : childrenOfType(function, "parameter")) {
			result.add(new VariableIR(fieldText(parameter, "name") != null ? fieldText(parameter, "name") : "_",
					fieldOf(parameter, "type").flatMap(SolidityExtractor::typeRefOf).orElse(null)));
		}
		return result;
	}

	@Override
	protected List<VariableIR> fieldsOf(Node node) {
		if (!node.getType().equals("state_variable_declaration")) {
			return List.of();
		}
		return fieldOf(node, "name").map(name -> List.of(new VariableIR(name.getText(),
				fieldOf(node, "type").flatMap(SolidityExtractor::typeRefOf).orElse(null)))).orElse(List.of());
	}

	// ---- bodies --------------------------------------------------------------------------------

	@Override
	protected Optional<CallIR> callOf(Node node) {
		if (!node.getType().equals("call_expression")) {
			return Optional.empty();
		}
		Optional<Node> function = fieldOf(node, "function").flatMap(expression -> expression.getNamedChildren().stream().findFirst());
		if (function.isEmpty()) {
			return Optional.empty();
		}
		int arguments = childrenOfType(node, "call_argument").size();
		Node target = function.get();
		if (target.getType().equals("identifier")) {
			return Optional.of(new CallIR(target.getText(), null, arguments, lineOf(node)));
		}
		if (target.getType().equals("member_expression")) {
			Optional<Node> object = fieldOf(target, "object");
			Optional<Node> property = fieldOf(target, "property");
			if (object.isPresent() && property.isPresent()) {
				return Optional.of(new CallIR(property.get().getText(), truncate(object.get().getText(), 200),
						arguments, lineOf(node)));
			}
		}
		return Optional.empty();
	}

	@Override
	protected Optional<TypeRefIR> instantiationOf(Node node) {
		return node.getType().equals("new_expression")
				? fieldOf(node, "name").flatMap(SolidityExtractor::typeRefOf) : Optional.empty();
	}

	@Override
	protected List<VariableIR> localsOf(Node node) {
		if (!node.getType().equals("variable_declaration")) {
			return List.of();
		}
		return fieldOf(node, "name").map(name -> List.of(new VariableIR(name.getText(),
				fieldOf(node, "type").flatMap(SolidityExtractor::typeRefOf).orElse(null)))).orElse(List.of());
	}

	private static Optional<TypeRefIR> typeRefOf(Node node) {
		return typeRef(node, TYPE_NAMES, TYPE_WRAPPERS);
	}
}
