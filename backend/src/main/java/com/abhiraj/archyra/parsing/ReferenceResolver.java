package com.abhiraj.archyra.parsing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ir.CallIR;
import com.abhiraj.archyra.ir.CodeGraphIR;
import com.abhiraj.archyra.ir.Declaration;
import com.abhiraj.archyra.ir.EdgeIR;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.ImportIR;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.NodeIR;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.RepositoryIR;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeRefIR;
import com.abhiraj.archyra.ir.VariableIR;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Second pass of the analysis: resolves names across the whole repository, after every file has been
 * parsed, and turns the per-file IR into a connected {@link CodeGraphIR}. The pass is language-neutral;
 * the only language-specific part is how an import specifier maps to a module of the repository, which
 * a {@link ModuleResolver} answers.
 *
 * <h3>What is resolved</h3>
 * <ul>
 * <li>{@code CONTAINS}: repository, module (package, namespace or directory), file, type, method;
 * file to free-standing function.</li>
 * <li>{@code EXTENDS} and {@code IMPLEMENTS} between types. Members that a language declares apart from the
 * type (Go receivers, Rust {@code impl}, C# {@code partial}) are merged into it.</li>
 * <li>{@code IMPORTS} from a file to the files that declare what it imports, wherever the language has
 * imports.</li>
 * <li>{@code REFERENCES} from a type (fields) or method/function (signature, locals, instantiations) to
 * the types it mentions.</li>
 * <li>{@code CALLS} to methods and functions, using the declared type of the receiver, the imports and the
 * enclosing scopes.</li>
 * <li>{@code DEPENDS_ON} between types of the repository (derived from references and calls) and between
 * modules (derived from imports across modules).</li>
 * </ul>
 *
 * <h3>External and unresolved targets</h3>
 * Targets outside the repository become nodes flagged {@code external} (an imported {@code java.util.List},
 * an npm package, a call into it) instead of being dropped. A reference that cannot be tied to anything
 * known produces no node; unresolved calls are counted and logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceResolver {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
	private static final Pattern CHAIN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
	private static final int MAX_LINKED_FILES = 25;
	private static final int MAX_REEXPORT_DEPTH = 4;

	/** For languages without a dedicated resolver: the import names a module exactly. */
	private static final ModuleResolver EXACT = new ModuleResolver() {
		@Override
		public boolean supports(com.abhiraj.archyra.ir.Language language) {
			return true;
		}

		@Override
		public Optional<ModuleRef> resolve(FileIR from, String module, int relativeLevel, ModuleIndex index) {
			return index.containsModule(module) ? Optional.of(ModuleRef.ofModule(module)) : Optional.empty();
		}
	};

	private final List<ModuleResolver> moduleResolvers;

	public CodeGraphIR resolve(RepositoryIR repository) {
		return new Run(repository).execute();
	}

	// ---- per-run state ---------------------------------------------------------------------

	private record MethodEntry(MethodIR method, String nodeId, TypeEntry owner, FileIR file) {
	}

	private record FieldEntry(VariableIR field, FileIR file) {
	}

	private record Part(TypeIR type, FileIR file) {
	}

	private static final class TypeEntry {
		final TypeIR type;
		final FileIR file;
		final String nodeId;
		final NodeType nodeType;
		final TypeEntry parent;
		final List<Part> parts = new ArrayList<>();
		final List<MethodEntry> methods = new ArrayList<>();
		final List<FieldEntry> fields = new ArrayList<>();
		final List<TypeEntry> superTypes = new ArrayList<>();

		TypeEntry(TypeIR type, FileIR file, String nodeId, NodeType nodeType, TypeEntry parent) {
			this.type = type;
			this.file = file;
			this.nodeId = nodeId;
			this.nodeType = nodeType;
			this.parent = parent;
		}

		String qualifiedName() {
			return type.qualifiedName();
		}
	}

	/** A resolved type name: {@code entry} is null for an external type. */
	private record Resolved(String qualifiedName, TypeEntry entry) {
	}

	private record FieldHit(FieldEntry field, TypeEntry owner) {
	}

	/** What a call receiver denotes: a type, a module of the repository, or an external module. */
	private record Receiver(Resolved type, ModuleRef module, String externalModule) {
	}

	private final class Run implements ModuleIndex {

		private final RepositoryIR repository;
		private final Map<String, NodeIR> nodes = new LinkedHashMap<>();
		private final Set<EdgeIR> edges = new LinkedHashSet<>();
		private final Map<String, TypeEntry> typesByQualifiedName = new HashMap<>();
		private final List<TypeEntry> allTypes = new ArrayList<>();
		private final Map<String, List<FileIR>> modules = new LinkedHashMap<>();
		private final Map<String, FileIR> filesByPath = new LinkedHashMap<>();
		private final List<MethodEntry> functions = new ArrayList<>();
		private final Map<String, Map<String, List<MethodEntry>>> functionsByModule = new HashMap<>();
		private final Map<String, NodeIR> externalTypes = new HashMap<>();
		private final Map<String, NodeIR> externalModules = new HashMap<>();
		private final Map<String, Set<TypeEntry>> dependencies = new HashMap<>();
		private String repositoryNodeId;
		private int resolvedCalls;
		private int externalCalls;
		private int unresolvedCalls;
		private int duplicateTypes;
		private int orphanExtensions;

		Run(RepositoryIR repository) {
			this.repository = repository;
		}

		CodeGraphIR execute() {
			declare();
			for (TypeEntry entry : allTypes) {
				resolveHeritage(entry);
			}
			for (FileIR file : repository.files()) {
				resolveImports(file);
			}
			for (TypeEntry entry : allTypes) {
				resolveMembers(entry);
			}
			for (MethodEntry function : functions) {
				resolveMethod(function, null);
			}
			addDependencies();

			log.info("Resolved {} nodes and {} relationships for {}: {} calls resolved, {} to external targets, "
							+ "{} unresolved, {} duplicate types ignored, {} orphan extensions ignored",
					nodes.size(), edges.size(), repository.name(), resolvedCalls, externalCalls, unresolvedCalls,
					duplicateTypes, orphanExtensions);
			return new CodeGraphIR(repository.name(), new ArrayList<>(nodes.values()), new ArrayList<>(edges));
		}

		// ---- ModuleIndex ---------------------------------------------------------------------

		@Override
		public boolean containsModule(String moduleName) {
			return modules.containsKey(moduleName);
		}

		@Override
		public java.util.Collection<String> moduleNames() {
			return modules.keySet();
		}

		@Override
		public List<FileIR> files(String moduleName) {
			return modules.getOrDefault(moduleName, List.of());
		}

		@Override
		public Optional<FileIR> fileAt(String path) {
			return Optional.ofNullable(filesByPath.get(path));
		}

		@Override
		public java.util.Collection<String> filePaths() {
			return filesByPath.keySet();
		}

		// ---- pass 1: declare nodes and containment ---------------------------------------

		private void declare() {
			repositoryNodeId = "repository:" + repository.name();
			putNode(new NodeIR(repositoryNodeId, NodeType.REPOSITORY, repository.name(), repository.url(),
					null, null, null, false));

			for (FileIR file : repository.files()) {
				filesByPath.put(file.path(), file);
				modules.computeIfAbsent(file.moduleName(), key -> new ArrayList<>()).add(file);

				String moduleId = moduleNodeId(file);
				if (!nodes.containsKey(moduleId)) {
					String name = groupName(file);
					putNode(new NodeIR(moduleId, NodeType.MODULE, name, name, null, null, null, false));
					addEdge(repositoryNodeId, moduleId, RelationshipType.CONTAINS);
				}
				putNode(new NodeIR(fileId(file), NodeType.FILE, fileName(file.path()), file.path(), file.path(), null,
						file.language().name(), false));
				addEdge(moduleId, fileId(file), RelationshipType.CONTAINS);

				for (MethodIR function : file.functions()) {
					String id = uniqueId("function:" + file.path() + "#" + signature(function));
					putNode(new NodeIR(id, NodeType.FUNCTION, function.name(), file.path() + "#" + signature(function),
							file.path(), function.line(), null, false));
					addEdge(fileId(file), id, RelationshipType.CONTAINS);
					MethodEntry entry = new MethodEntry(function, id, null, file);
					functions.add(entry);
					functionsByModule.computeIfAbsent(file.moduleName(), key -> new HashMap<>())
							.computeIfAbsent(function.name(), key -> new ArrayList<>()).add(entry);
				}
			}

			for (FileIR file : repository.files()) {
				for (TypeIR type : file.types()) {
					if (type.declaration() != Declaration.EXTENSION) {
						declareType(type, file, null, fileId(file));
					}
				}
			}
			for (FileIR file : repository.files()) {
				for (TypeIR type : file.types()) {
					if (type.declaration() == Declaration.EXTENSION) {
						TypeEntry target = typesByQualifiedName.get(type.qualifiedName());
						if (target == null) {
							orphanExtensions++;
						}
						else {
							addMembers(target, type, file);
						}
					}
				}
			}
		}

		private void declareType(TypeIR type, FileIR file, TypeEntry parent, String containerId) {
			TypeEntry existing = typesByQualifiedName.get(type.qualifiedName());
			if (existing != null) {
				if (type.declaration() == Declaration.PARTIAL) {
					addMembers(existing, type, file);
				}
				else {
					duplicateTypes++;
					log.debug("Duplicate type {} in {} ignored", type.qualifiedName(), file.path());
				}
				return;
			}

			NodeType nodeType = type.kind().interfaceLike() ? NodeType.INTERFACE : NodeType.CLASS;
			String id = (nodeType == NodeType.INTERFACE ? "interface:" : "class:") + type.qualifiedName();
			if (nodes.containsKey(id)) {
				duplicateTypes++;
				return;
			}
			TypeEntry entry = new TypeEntry(type, file, id, nodeType, parent);
			typesByQualifiedName.put(type.qualifiedName(), entry);
			allTypes.add(entry);
			putNode(new NodeIR(id, nodeType, type.name(), type.qualifiedName(), file.path(), type.line(),
					type.kind().name(), false));
			addEdge(containerId, id, RelationshipType.CONTAINS);
			addMembers(entry, type, file);
		}

		/** Adds the methods, fields and nested types of one declaration (or part of it) to {@code entry}. */
		private void addMembers(TypeEntry entry, TypeIR type, FileIR file) {
			entry.parts.add(new Part(type, file));
			for (VariableIR field : type.fields()) {
				entry.fields.add(new FieldEntry(field, file));
			}
			for (MethodIR method : type.methods()) {
				String methodId = uniqueId("method:" + type.qualifiedName() + "#" + signature(method));
				putNode(new NodeIR(methodId, NodeType.METHOD, method.name(),
						type.qualifiedName() + "#" + signature(method), file.path(), method.line(),
						method.constructor() ? "CONSTRUCTOR" : null, false));
				addEdge(entry.nodeId, methodId, RelationshipType.CONTAINS);
				entry.methods.add(new MethodEntry(method, methodId, entry, file));
			}
			for (TypeIR nested : type.nestedTypes()) {
				declareType(nested, file, entry, entry.nodeId);
			}
		}

		// ---- pass 2: heritage, imports, members -------------------------------------------

		private void resolveHeritage(TypeEntry entry) {
			boolean interfaceLike = entry.nodeType == NodeType.INTERFACE;
			for (Part part : entry.parts) {
				for (TypeRefIR ref : part.type().extendsTypes()) {
					// A class extends a class; an interface extends interfaces.
					linkHeritage(entry, part.file(), ref, RelationshipType.EXTENDS,
							interfaceLike ? NodeType.INTERFACE : NodeType.CLASS);
				}
				for (TypeRefIR ref : part.type().implementsTypes()) {
					linkHeritage(entry, part.file(), ref, RelationshipType.IMPLEMENTS, NodeType.INTERFACE);
				}
			}
		}

		private void linkHeritage(TypeEntry source, FileIR file, TypeRefIR ref, RelationshipType relationship,
				NodeType expected) {
			Resolved resolved = resolveType(ref.name(), file, source);
			if (resolved != null && resolved.entry() == source) {
				return;
			}
			if (resolved != null && resolved.entry() != null) {
				if (!source.superTypes.contains(resolved.entry())) {
					source.superTypes.add(resolved.entry());
				}
				addEdge(source.nodeId, resolved.entry().nodeId, relationship);
			}
			else {
				// External or unresolvable: heritage is structural, so keep it with a best-effort name.
				String qualifiedName = resolved != null ? resolved.qualifiedName() : ref.name();
				addEdge(source.nodeId, externalType(qualifiedName, expected).id(), relationship);
			}
		}

		private void resolveImports(FileIR file) {
			ModuleResolver resolver = resolverFor(file);
			for (ImportIR imported : file.imports()) {
				Optional<ModuleRef> ref = resolveModule(file, imported.module(), imported.relativeLevel());
				boolean relative = resolver.isRelative(imported.module(), imported.relativeLevel());

				if (imported.name() != null) {
					TypeEntry type = importedTypeEntry(file, imported, ref);
					if (type != null) {
						linkFiles(file, type.file);
					}
					else if (ref.isPresent()) {
						linkSymbolFiles(file, imported, ref.get());
					}
					else {
						// the symbol may itself be a module (Python `from pkg import module`)
						Optional<ModuleRef> submodule = resolveModule(file, submodulePath(imported), imported.relativeLevel());
						if (submodule.isPresent()) {
							linkAll(file, submodule.get());
						}
						else if (!relative) {
							if (resolver.importsTypes()) {
								addEdge(fileId(file), externalType(qualify(imported), NodeType.CLASS).id(),
										RelationshipType.IMPORTS);
							}
							else if (!imported.module().isEmpty()) {
								addEdge(fileId(file), externalModule(imported.module()).id(), RelationshipType.IMPORTS);
							}
						}
					}
				}
				else if (ref.isPresent()) {
					linkAll(file, ref.get());
				}
				else if (!relative && !resolver.importsTypes() && !imported.module().isEmpty()) {
					addEdge(fileId(file), externalModule(imported.module()).id(), RelationshipType.IMPORTS);
				}
			}
		}

		private void linkSymbolFiles(FileIR file, ImportIR imported, ModuleRef ref) {
			List<MethodEntry> functionsNamed = functionsNamed(ref, imported);
			if (!functionsNamed.isEmpty()) {
				functionsNamed.forEach(function -> linkFiles(file, function.file()));
				return;
			}
			// the symbol may itself be a module (Python `from pkg import module`)
			Optional<ModuleRef> submodule = resolveModule(file, submodulePath(imported), imported.relativeLevel());
			linkAll(file, submodule.orElse(ref));
		}

		private void linkAll(FileIR file, ModuleRef ref) {
			List<FileIR> targets = new ArrayList<>();
			if (!ref.files().isEmpty()) {
				ref.files().forEach(path -> fileAt(path).ifPresent(targets::add));
			}
			else {
				targets.addAll(files(ref.module()));
			}
			targets.stream().limit(MAX_LINKED_FILES).forEach(target -> linkFiles(file, target));
		}

		private void linkFiles(FileIR from, FileIR to) {
			if (from.path().equals(to.path())) {
				return;
			}
			addEdge(fileId(from), fileId(to), RelationshipType.IMPORTS);
			addEdge(moduleNodeId(from), moduleNodeId(to), RelationshipType.DEPENDS_ON);
		}

		private void resolveMembers(TypeEntry entry) {
			for (FieldEntry field : entry.fields) {
				reference(entry.nodeId, field.field().type(), field.file(), entry);
			}
			for (MethodEntry method : entry.methods) {
				resolveMethod(method, entry);
			}
		}

		private void resolveMethod(MethodEntry entry, TypeEntry scope) {
			MethodIR method = entry.method();
			FileIR file = entry.file();
			Map<String, TypeRefIR> variables = new HashMap<>();

			for (VariableIR parameter : method.parameters()) {
				reference(entry.nodeId(), parameter.type(), file, scope);
				variables.put(parameter.name(), parameter.type());
			}
			reference(entry.nodeId(), method.returnType(), file, scope);
			for (VariableIR local : method.locals()) {
				reference(entry.nodeId(), local.type(), file, scope);
				variables.put(local.name(), local.type());
			}
			for (TypeRefIR instantiated : method.instantiatedTypes()) {
				reference(entry.nodeId(), instantiated, file, scope);
			}
			for (CallIR call : method.calls()) {
				resolveCall(entry, scope, call, variables);
			}
		}

		/** REFERENCES from {@code sourceId} to every type named in {@code ref}; records class dependencies. */
		private void reference(String sourceId, TypeRefIR ref, FileIR file, TypeEntry scope) {
			if (ref == null) {
				return;
			}
			List<String> names = new ArrayList<>();
			names.add(ref.name());
			names.addAll(ref.typeArguments());
			for (String name : names) {
				Resolved resolved = resolveType(name, file, scope);
				if (resolved == null) {
					continue;
				}
				if (resolved.entry() != null) {
					addEdge(sourceId, resolved.entry().nodeId, RelationshipType.REFERENCES);
					dependOn(scope, resolved.entry());
				}
				else {
					addEdge(sourceId, externalType(resolved.qualifiedName(), NodeType.CLASS).id(),
							RelationshipType.REFERENCES);
				}
			}
		}

		// ---- calls ---------------------------------------------------------------------------

		private void resolveCall(MethodEntry caller, TypeEntry scope, CallIR call, Map<String, TypeRefIR> variables) {
			String receiver = call.receiver();
			FileIR file = caller.file();
			List<MethodEntry> targets = List.of();

			if (receiver == null) {
				targets = findImplicit(caller, scope, call);
				if (targets.isEmpty()) {
					targets = findImported(file, call);
				}
				if (targets.isEmpty() && instantiateByCall(caller, scope, call)) {
					return;
				}
			}
			else if (receiver.equals("this")) {
				targets = scope == null ? List.of() : findMethods(scope, call, new HashSet<>());
			}
			else if (receiver.equals("super")) {
				targets = scope == null ? List.of() : findInSuperTypes(scope, call, new HashSet<>());
			}
			else {
				Receiver target = resolveReceiver(receiver, caller, scope, variables);
				if (target != null && target.type() != null) {
					if (target.type().entry() != null) {
						targets = findMethods(target.type().entry(), call, new HashSet<>());
					}
					else {
						linkExternalMethod(caller, target.type().qualifiedName(), call);
						return;
					}
				}
				else if (target != null && target.module() != null) {
					targets = functionsNamed(target.module(), call.name());
					if (targets.isEmpty() && instantiateQualified(caller, scope, target.module(), call)) {
						return;
					}
				}
				else if (target != null && target.externalModule() != null) {
					linkExternalFunction(caller, target.externalModule(), call);
					return;
				}
			}

			if (targets.isEmpty()) {
				unresolvedCalls++;
				return;
			}
			resolvedCalls++;
			for (MethodEntry target : targets) {
				addEdge(caller.nodeId(), target.nodeId(), RelationshipType.CALLS);
				dependOn(scope, target.owner());
			}
		}

		/** A call such as {@code Foo(...)} that names a type: an instantiation. */
		private boolean instantiateByCall(MethodEntry caller, TypeEntry scope, CallIR call) {
			Resolved type = resolveType(call.name(), caller.file(), scope);
			if (type == null) {
				return false;
			}
			instantiate(caller, scope, type, call);
			return true;
		}

		private boolean instantiateQualified(MethodEntry caller, TypeEntry scope, ModuleRef module, CallIR call) {
			TypeEntry entry = lookupType(module, call.name(), 0);
			if (entry == null) {
				return false;
			}
			instantiate(caller, scope, new Resolved(entry.qualifiedName(), entry), call);
			return true;
		}

		private void instantiate(MethodEntry caller, TypeEntry scope, Resolved type, CallIR call) {
			if (type.entry() == null) {
				addEdge(caller.nodeId(), externalType(type.qualifiedName(), NodeType.CLASS).id(),
						RelationshipType.REFERENCES);
				externalCalls++;
				return;
			}
			addEdge(caller.nodeId(), type.entry().nodeId, RelationshipType.REFERENCES);
			dependOn(scope, type.entry());
			List<MethodEntry> constructors = type.entry().methods.stream()
					.filter(method -> method.method().constructor()).toList();
			for (MethodEntry constructor : filterByArity(constructors, call)) {
				addEdge(caller.nodeId(), constructor.nodeId(), RelationshipType.CALLS);
			}
			resolvedCalls++;
		}

		private void linkExternalMethod(MethodEntry caller, String ownerQualifiedName, CallIR call) {
			NodeIR owner = externalType(ownerQualifiedName, NodeType.CLASS);
			String id = "external:method:" + ownerQualifiedName + "#" + call.name();
			if (!nodes.containsKey(id)) {
				putNode(new NodeIR(id, NodeType.METHOD, call.name(), ownerQualifiedName + "#" + call.name(),
						null, null, null, true));
				addEdge(owner.id(), id, RelationshipType.CONTAINS);
			}
			addEdge(caller.nodeId(), id, RelationshipType.CALLS);
			externalCalls++;
		}

		private void linkExternalFunction(MethodEntry caller, String moduleName, CallIR call) {
			NodeIR module = externalModule(moduleName);
			String id = "external:function:" + moduleName + "#" + call.name();
			if (!nodes.containsKey(id)) {
				putNode(new NodeIR(id, NodeType.FUNCTION, call.name(), moduleName + "#" + call.name(),
						null, null, null, true));
				addEdge(module.id(), id, RelationshipType.CONTAINS);
			}
			addEdge(caller.nodeId(), id, RelationshipType.CALLS);
			externalCalls++;
		}

		/** What a receiver written as a name or a dotted chain denotes. */
		private Receiver resolveReceiver(String receiver, MethodEntry caller, TypeEntry scope,
				Map<String, TypeRefIR> variables) {
			FileIR file = caller.file();
			if (IDENTIFIER.matcher(receiver).matches()) {
				Resolved type = typeOfName(receiver, caller, scope, variables);
				return type != null ? new Receiver(type, null, null) : moduleReceiver(receiver, file);
			}
			if (!CHAIN.matcher(receiver).matches()) {
				return null;
			}
			String[] parts = receiver.split("\\.");
			if (isValue(parts[0], scope, variables)) {
				// a.b.c where a is a variable, a field or this: walk the declared field types from the head
				Resolved current = parts[0].equals("this")
						? (scope == null ? null : new Resolved(scope.qualifiedName(), scope))
						: typeOfName(parts[0], caller, scope, variables);
				for (int i = 1; i < parts.length && current != null; i++) {
					if (current.entry() == null) {
						return null;
					}
					FieldHit hit = findField(current.entry(), parts[i], new HashSet<>());
					VariableIR field = hit == null ? null : hit.field().field();
					current = field == null || field.type() == null ? null
							: resolveType(field.type().name(), hit.field().file(), hit.owner());
				}
				return current == null ? null : new Receiver(current, null, null);
			}
			// Outer.Inner, pkg.Type, ns.helper: a type or a module, not a value
			Resolved qualifiedType = resolveType(receiver, file, scope);
			return qualifiedType != null ? new Receiver(qualifiedType, null, null) : moduleReceiver(receiver, file);
		}

		private boolean isValue(String name, TypeEntry scope, Map<String, TypeRefIR> variables) {
			if (name.equals("this") || variables.containsKey(name)) {
				return true;
			}
			for (TypeEntry t = scope; t != null; t = t.parent) {
				if (findField(t, name, new HashSet<>()) != null) {
					return true;
				}
			}
			return false;
		}

		/** The declared type of a receiver written as a single name: a variable, a field, or a type. */
		private Resolved typeOfName(String name, MethodEntry caller, TypeEntry scope, Map<String, TypeRefIR> variables) {
			FileIR file = caller.file();
			if (variables.containsKey(name)) {
				TypeRefIR type = variables.get(name);
				return type == null ? null : resolveTypeOrReturnType(type.name(), file, scope);
			}
			for (TypeEntry t = scope; t != null; t = t.parent) {
				FieldHit hit = findField(t, name, new HashSet<>());
				if (hit != null) {
					VariableIR field = hit.field().field();
					return field.type() == null ? null
							: resolveType(field.type().name(), hit.field().file(), hit.owner());
				}
			}
			return resolveType(name, file, scope); // static call on a type
		}

		/**
		 * A variable initialised by a call is typed with the callee's name (the extractor cannot know
		 * more): a type if the name is one, otherwise the declared return type of a function of that name.
		 */
		private Resolved resolveTypeOrReturnType(String name, FileIR file, TypeEntry scope) {
			Resolved type = resolveType(name, file, scope);
			if (type != null) {
				return type;
			}
			List<MethodEntry> candidates = new ArrayList<>();
			int dot = name.lastIndexOf('.');
			if (dot > 0) {
				String head = name.substring(0, dot);
				String member = name.substring(dot + 1);
				// pkg.NewServer: a function of an imported module
				Receiver module = moduleReceiver(head, file);
				if (module != null && module.module() != null) {
					candidates.addAll(functionsNamed(module.module(), member));
				}
				// Server.new: a method of a type
				Resolved owner = resolveType(head, file, scope);
				if (owner != null && owner.entry() != null) {
					owner.entry().methods.stream().filter(method -> method.method().name().equals(member))
							.forEach(candidates::add);
				}
			}
			else {
				candidates.addAll(functions.stream()
						.filter(function -> function.file() == file && function.method().name().equals(name)).toList());
				if (candidates.isEmpty()) {
					candidates.addAll(findImported(file, new CallIR(name, null, -1, 0)));
				}
			}
			for (MethodEntry candidate : candidates) {
				TypeRefIR returnType = candidate.method().returnType();
				if (returnType != null) {
					if (returnType.name().equals("Self") && candidate.owner() != null) {
						return new Resolved(candidate.owner().qualifiedName(), candidate.owner());
					}
					Resolved resolved = resolveType(returnType.name(), candidate.file(), candidate.owner());
					if (resolved != null) {
						return resolved;
					}
				}
			}
			return null;
		}

		/** A receiver that names a module through an import: {@code utils.helper()}, {@code np.array()}. */
		private Receiver moduleReceiver(String receiver, FileIR file) {
			ModuleResolver resolver = resolverFor(file);
			for (ImportIR imported : file.imports()) {
				if (imported.isModule()) {
					String prefix = imported.alias() != null ? imported.alias() : imported.module();
					if (!receiver.equals(prefix) && !receiver.startsWith(prefix + ".")) {
						continue;
					}
					String rest = receiver.length() == prefix.length() ? "" : receiver.substring(prefix.length() + 1);
					Optional<ModuleRef> ref = resolveModule(file, imported.module(), imported.relativeLevel());
					if (ref.isPresent()) {
						if (rest.isEmpty()) {
							return new Receiver(null, ref.get(), null);
						}
						String submodule = qualified(ref.get().module(), rest);
						return containsModule(submodule) ? new Receiver(null, ModuleRef.ofModule(submodule), null) : null;
					}
					if (resolver.isRelative(imported.module(), imported.relativeLevel())) {
						return null;
					}
					return new Receiver(null, null, rest.isEmpty() ? imported.module() : imported.module() + "." + rest);
				}
				else if (imported.isSymbol() && receiver.equals(imported.localName())) {
					// `from pkg import module`, or a JS default import of a file used as a namespace
					Optional<ModuleRef> submodule = resolveModule(file, submodulePath(imported), imported.relativeLevel());
					if (submodule.isPresent()) {
						return new Receiver(null, submodule.get(), null);
					}
					if ("default".equals(imported.name())) {
						Optional<ModuleRef> ref = resolveModule(file, imported.module(), imported.relativeLevel());
						if (ref.isPresent()) {
							return new Receiver(null, ref.get(), null);
						}
						if (!resolver.isRelative(imported.module(), imported.relativeLevel())) {
							return new Receiver(null, null, imported.module());
						}
					}
				}
			}
			return null;
		}

		private List<MethodEntry> findImplicit(MethodEntry caller, TypeEntry scope, CallIR call) {
			for (TypeEntry t = scope; t != null; t = t.parent) {
				List<MethodEntry> found = findMethods(t, call, new HashSet<>());
				if (!found.isEmpty()) {
					return found;
				}
			}
			// Free-standing functions of the same file.
			List<MethodEntry> sameFile = functions.stream()
					.filter(function -> function.file() == caller.file() && function.method().name().equals(call.name()))
					.toList();
			return filterByArity(sameFile, call);
		}

		/** Functions reached without a receiver through imports, the module itself or static imports. */
		private List<MethodEntry> findImported(FileIR file, CallIR call) {
			List<MethodEntry> found = new ArrayList<>();
			for (ImportIR imported : file.imports()) {
				if (imported.isSymbol() && call.name().equals(imported.localName())) {
					resolveModule(file, imported.module(), imported.relativeLevel())
							.ifPresent(ref -> found.addAll(functionsNamed(ref, imported)));
				}
				else if (imported.isStaticMember()
						&& (imported.member().equals("*") || imported.member().equals(call.name()))) {
					TypeEntry type = importedTypeEntry(file, imported,
							resolveModule(file, imported.module(), imported.relativeLevel()));
					if (type != null) {
						found.addAll(findMethods(type, call, new HashSet<>()));
					}
				}
				else if (imported.wildcard()) {
					resolveModule(file, imported.module(), imported.relativeLevel())
							.ifPresent(ref -> found.addAll(functionsNamed(ref, call.name())));
				}
			}
			if (found.isEmpty()) {
				found.addAll(functionsNamed(ModuleRef.ofModule(file.moduleName()), call.name()));
			}
			return filterByArity(found, call);
		}

		/** The functions called {@code name} in the module or files of {@code ref}, following re-exports. */
		private List<MethodEntry> functionsNamed(ModuleRef ref, String name) {
			return functionsIn(ref, name, 0);
		}

		private List<MethodEntry> functionsIn(ModuleRef ref, String name, int depth) {
			List<MethodEntry> inModule = functionsByModule.getOrDefault(ref.module(), Map.of())
					.getOrDefault(name, List.of());
			if (!ref.files().isEmpty()) {
				inModule = inModule.stream().filter(function -> ref.files().contains(function.file().path())).toList();
			}
			if (!inModule.isEmpty() || depth >= MAX_REEXPORT_DEPTH) {
				return inModule;
			}
			List<MethodEntry> viaReexport = new ArrayList<>();
			for (FileIR file : filesOf(ref)) {
				if (resolverFor(file).importsTypes()) {
					continue;
				}
				for (ImportIR imported : file.imports()) {
					Optional<ModuleRef> next = reexportTarget(file, imported, name);
					if (next.isPresent()) {
						String exported = imported.isSymbol() ? imported.name() : name;
						viaReexport.addAll(functionsIn(next.get(), exported, depth + 1));
					}
				}
			}
			return viaReexport;
		}

		/** The type called {@code name} in the module or files of {@code ref}, following re-exports. */
		private TypeEntry lookupType(ModuleRef ref, String name, int depth) {
			TypeEntry entry = typesByQualifiedName.get(qualified(ref.module(), name));
			if (entry != null && (ref.files().isEmpty() || ref.files().contains(entry.file.path()))) {
				return entry;
			}
			if (depth >= MAX_REEXPORT_DEPTH) {
				return null;
			}
			for (FileIR file : filesOf(ref)) {
				if (resolverFor(file).importsTypes()) {
					continue;
				}
				for (ImportIR imported : file.imports()) {
					Optional<ModuleRef> next = reexportTarget(file, imported, name);
					if (next.isPresent()) {
						TypeEntry found = lookupType(next.get(), imported.isSymbol() ? imported.name() : name, depth + 1);
						if (found != null) {
							return found;
						}
					}
				}
			}
			return null;
		}

		/**
		 * Where {@code name} comes from when {@code file} re-exports it: a symbol import that binds it
		 * ({@code from .user import User}) or a wildcard import ({@code export * from './user'}).
		 */
		private Optional<ModuleRef> reexportTarget(FileIR file, ImportIR imported, String name) {
			if ((imported.isSymbol() && name.equals(imported.localName())) || imported.wildcard()) {
				return resolveModule(file, imported.module(), imported.relativeLevel());
			}
			return Optional.empty();
		}

		private List<FileIR> filesOf(ModuleRef ref) {
			if (ref.files().isEmpty()) {
				return files(ref.module());
			}
			List<FileIR> result = new ArrayList<>();
			ref.files().forEach(path -> fileAt(path).ifPresent(result::add));
			return result;
		}

		/** Functions an import binds, including a default import (the function named like the alias, or the only one). */
		private List<MethodEntry> functionsNamed(ModuleRef ref, ImportIR imported) {
			if (!"default".equals(imported.name())) {
				return functionsNamed(ref, imported.name());
			}
			List<MethodEntry> named = imported.alias() == null ? List.of() : functionsNamed(ref, imported.alias());
			if (!named.isEmpty()) {
				return named;
			}
			List<MethodEntry> all = functionsByModule.getOrDefault(ref.module(), Map.of()).values().stream()
					.flatMap(List::stream)
					.filter(function -> ref.files().isEmpty() || ref.files().contains(function.file().path()))
					.toList();
			return all.size() == 1 ? all : List.of();
		}

		private List<MethodEntry> findMethods(TypeEntry type, CallIR call, Set<TypeEntry> visited) {
			if (!visited.add(type)) {
				return List.of();
			}
			List<MethodEntry> byName = type.methods.stream()
					.filter(method -> !method.method().constructor() && method.method().name().equals(call.name()))
					.toList();
			List<MethodEntry> found = filterByArity(byName, call);
			return found.isEmpty() ? findInSuperTypes(type, call, visited) : found;
		}

		private List<MethodEntry> findInSuperTypes(TypeEntry type, CallIR call, Set<TypeEntry> visited) {
			for (TypeEntry superType : type.superTypes) {
				List<MethodEntry> found = findMethods(superType, call, visited);
				if (!found.isEmpty()) {
					return found;
				}
			}
			return List.of();
		}

		/** Same-arity overloads if there are any; otherwise all same-name methods (varargs, defaults). */
		private static List<MethodEntry> filterByArity(List<MethodEntry> byName, CallIR call) {
			List<MethodEntry> exact = byName.stream()
					.filter(method -> method.method().parameters().size() == call.argumentCount())
					.toList();
			return exact.isEmpty() ? byName : exact;
		}

		private FieldHit findField(TypeEntry type, String name, Set<TypeEntry> visited) {
			if (!visited.add(type)) {
				return null;
			}
			for (FieldEntry field : type.fields) {
				if (field.field().name().equals(name)) {
					return new FieldHit(field, type);
				}
			}
			for (TypeEntry superType : type.superTypes) {
				FieldHit hit = findField(superType, name, visited);
				if (hit != null) {
					return hit;
				}
			}
			return null;
		}

		// ---- type name resolution ------------------------------------------------------------

		/** Resolves a type name as written in {@code file}, inside {@code scope} (may be null). */
		private Resolved resolveType(String name, FileIR file, TypeEntry scope) {
			if (name == null || name.isBlank()) {
				return null;
			}
			int dot = name.indexOf('.');
			if (dot < 0) {
				return resolveSimple(name, file, scope);
			}
			TypeEntry exact = typesByQualifiedName.get(name);
			if (exact != null) {
				return new Resolved(exact.qualifiedName(), exact);
			}
			Resolved head = resolveSimple(name.substring(0, dot), file, scope);
			if (head != null) {
				String qualified = head.qualifiedName() + name.substring(dot);
				TypeEntry nested = typesByQualifiedName.get(qualified);
				if (nested != null) {
					return new Resolved(qualified, nested);
				}
				// Outer.Inner on an external Outer stays external; on an internal Outer it simply is not a type
				return head.entry() == null ? new Resolved(qualified, null) : null;
			}
			Resolved viaModule = resolveThroughModuleImport(name, file);
			if (viaModule != null) {
				return viaModule;
			}
			// Fully qualified name of a type outside the repository: java.util.List
			return resolverFor(file).importsTypes() && Character.isLowerCase(name.charAt(0))
					? new Resolved(name, null) : null;
		}

		/** {@code ns.Type}, {@code pkg.Type}, {@code np.ndarray}: a type reached through a module import. */
		private Resolved resolveThroughModuleImport(String name, FileIR file) {
			ModuleResolver resolver = resolverFor(file);
			for (ImportIR imported : file.imports()) {
				String prefix = imported.isModule() ? (imported.alias() != null ? imported.alias() : imported.module())
						: imported.isSymbol() ? imported.localName() : null;
				if (prefix == null || !name.startsWith(prefix + ".")) {
					continue;
				}
				String rest = name.substring(prefix.length() + 1);
				Optional<ModuleRef> ref = imported.isModule()
						? resolveModule(file, imported.module(), imported.relativeLevel())
						: resolveModule(file, submodulePath(imported), imported.relativeLevel());
				if (ref.isPresent()) {
					TypeEntry entry = lookupType(ref.get(), rest, 0);
					if (entry != null) {
						return new Resolved(entry.qualifiedName(), entry);
					}
				}
				else if (imported.isModule() && !resolver.isRelative(imported.module(), imported.relativeLevel())) {
					return new Resolved(imported.module() + "." + rest, null);
				}
			}
			return null;
		}

		private Resolved resolveSimple(String name, FileIR file, TypeEntry scope) {
			// 1. the enclosing types and their member types
			for (TypeEntry t = scope; t != null; t = t.parent) {
				if (t.type.name().equals(name) && t.type.declaration() != Declaration.EXTENSION) {
					return new Resolved(t.qualifiedName(), t);
				}
				TypeEntry nested = typesByQualifiedName.get(t.qualifiedName() + "." + name);
				if (nested != null) {
					return new Resolved(nested.qualifiedName(), nested);
				}
			}
			// 2. names bound by an import
			ModuleResolver resolver = resolverFor(file);
			for (ImportIR imported : file.imports()) {
				if (imported.isSymbol() && name.equals(imported.localName())) {
					Optional<ModuleRef> ref = resolveModule(file, imported.module(), imported.relativeLevel());
					TypeEntry entry = importedTypeEntry(file, imported, ref);
					if (entry != null) {
						return new Resolved(entry.qualifiedName(), entry);
					}
					if (ref.isPresent() || resolver.isRelative(imported.module(), imported.relativeLevel())) {
						return null; // a module of the repository, but the name is not a type (a function, a constant)
					}
					return new Resolved(qualify(imported), null);
				}
			}
			// 3. the same module (package, namespace, file)
			TypeEntry sameModule = typesByQualifiedName.get(qualified(file.moduleName(), name));
			if (sameModule != null) {
				return new Resolved(sameModule.qualifiedName(), sameModule);
			}
			// 4. wildcard imports
			for (ImportIR imported : file.imports()) {
				if (imported.wildcard()) {
					Optional<ModuleRef> ref = resolveModule(file, imported.module(), imported.relativeLevel());
					if (ref.isPresent()) {
						TypeEntry found = lookupType(ref.get(), name, 0);
						if (found != null) {
							return new Resolved(found.qualifiedName(), found);
						}
					}
				}
			}
			return null;
		}

		/** The type of the repository that a symbol import binds, if there is one. */
		private TypeEntry importedTypeEntry(FileIR file, ImportIR imported, Optional<ModuleRef> ref) {
			TypeEntry direct = typesByQualifiedName.get(qualify(imported));
			if (direct != null) {
				return direct;
			}
			if (ref.isEmpty()) {
				return null;
			}
			ModuleRef module = ref.get();
			TypeEntry entry = lookupType(module, imported.name(), 0);
			if (entry != null) {
				return entry;
			}
			if ("default".equals(imported.name())) {
				return defaultType(module, imported.alias());
			}
			return null;
		}

		/** A default import binds the type named like the alias, or the only type of the file. */
		private TypeEntry defaultType(ModuleRef module, String alias) {
			List<TypeEntry> candidates = allTypes.stream()
					.filter(type -> type.parent == null && type.file.moduleName().equals(module.module())
							&& (module.files().isEmpty() || module.files().contains(type.file.path())))
					.toList();
			return candidates.stream().filter(type -> type.type.name().equals(alias)).findFirst()
					.orElse(candidates.size() == 1 ? candidates.getFirst() : null);
		}

		// ---- modules -------------------------------------------------------------------------

		private ModuleResolver resolverFor(FileIR file) {
			return moduleResolvers.stream()
					.filter(resolver -> resolver.supports(file.language()))
					.findFirst()
					.orElse(EXACT);
		}

		private Optional<ModuleRef> resolveModule(FileIR file, String module, int relativeLevel) {
			if (module == null || (module.isEmpty() && relativeLevel == 0)) {
				return Optional.empty();
			}
			return resolverFor(file).resolve(file, module, relativeLevel, this);
		}

		// ---- derived dependencies -----------------------------------------------------------

		private void dependOn(TypeEntry from, TypeEntry to) {
			if (from == null || to == null || from == to) {
				return;
			}
			dependencies.computeIfAbsent(from.nodeId, key -> new LinkedHashSet<>()).add(to);
		}

		private void addDependencies() {
			dependencies.forEach((sourceId, targets) -> {
				for (TypeEntry target : targets) {
					addEdge(sourceId, target.nodeId, RelationshipType.DEPENDS_ON);
				}
			});
		}

		// ---- graph helpers ----------------------------------------------------------------

		private NodeIR externalType(String qualifiedName, NodeType preferred) {
			return externalTypes.computeIfAbsent(qualifiedName, key -> {
				NodeType type = preferred == NodeType.INTERFACE ? NodeType.INTERFACE : NodeType.CLASS;
				String id = "external:" + (type == NodeType.INTERFACE ? "interface:" : "class:") + key;
				NodeIR node = new NodeIR(id, type, simpleName(key), key, null, null, null, true);
				putNode(node);
				return node;
			});
		}

		private NodeIR externalModule(String moduleName) {
			return externalModules.computeIfAbsent(moduleName, key -> {
				NodeIR node = new NodeIR("external:module:" + key, NodeType.MODULE, key, key, null, null, null, true);
				putNode(node);
				return node;
			});
		}

		private void putNode(NodeIR node) {
			nodes.putIfAbsent(node.id(), node);
		}

		private void addEdge(String source, String target, RelationshipType type) {
			if (!source.equals(target)) {
				edges.add(new EdgeIR(source, target, type));
			}
		}

		/** Overloads that differ only in ways the IR does not capture still get distinct ids. */
		private String uniqueId(String base) {
			String id = base;
			for (int counter = 2; nodes.containsKey(id); counter++) {
				id = base + "~" + counter;
			}
			return id;
		}
	}

	// ---- shared helpers ------------------------------------------------------------------------

	private static String fileId(FileIR file) {
		return "file:" + file.path();
	}

	private static String groupName(FileIR file) {
		return file.packageName().isEmpty() ? "(default)" : file.packageName();
	}

	private static String moduleNodeId(FileIR file) {
		return "module:" + groupName(file);
	}

	private static String fileName(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private static String simpleName(String qualifiedName) {
		return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
	}

	private static String qualified(String module, String name) {
		return module.isEmpty() ? name : module + "." + name;
	}

	/** {@code module.name} of an imported symbol. */
	private static String qualify(ImportIR imported) {
		return qualified(imported.module(), imported.name());
	}

	/** Where a symbol import would point if the symbol is itself a module: {@code from pkg import mod}. */
	private static String submodulePath(ImportIR imported) {
		return imported.module().isEmpty() ? imported.name() : imported.module() + "." + imported.name();
	}

	/** {@code create(String,int)}: name plus the written parameter types, which tells overloads apart. */
	private static String signature(MethodIR method) {
		StringBuilder builder = new StringBuilder(method.name()).append('(');
		for (int i = 0; i < method.parameters().size(); i++) {
			if (i > 0) {
				builder.append(',');
			}
			TypeRefIR type = method.parameters().get(i).type();
			builder.append(type == null ? "?" : type.name());
		}
		return builder.append(')').toString();
	}
}
