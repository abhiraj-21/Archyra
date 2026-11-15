package com.abhiraj.archyra.ir;

/**
 * A name binding introduced by an import, in a language-neutral form.
 *
 * <ul>
 * <li><b>symbol import</b> ({@code name != null}): binds {@code name} (or {@code alias}) from
 * {@code module}: Java {@code import a.B}, Python {@code from a import B as C}, JS
 * {@code import {B} from './a'}.</li>
 * <li><b>module import</b> ({@code name == null}, not a wildcard): binds the module itself under
 * {@code alias}, or its own name when there is no alias: Python {@code import a.b}, Go imports,
 * JS {@code import * as ns}.</li>
 * <li><b>wildcard import</b> ({@code wildcard}): brings every name of the module into scope: Java
 * {@code import a.*}, C# {@code using Ns}, Python {@code from a import *}.</li>
 * </ul>
 * Package separators are normalised to {@code .} by the extractor, except for path-like specifiers
 * (JS, Ruby, Solidity) which keep the text as written.
 *
 * @param module        module, namespace, package or path the import comes from
 * @param name          imported symbol; the declaring type for a Java static import; "default" for a
 *                      JS default import
 * @param member        Java static import: the imported member, or "*"; null otherwise
 * @param alias         local name when it differs from the imported one
 * @param relativeLevel how many levels up a relative import starts (Python dots, Rust {@code super}),
 *                      0 when absolute; each language's module resolver gives it its exact meaning
 */
public record ImportIR(String module, String name, String member, String alias, boolean wildcard, int relativeLevel) {

	public static ImportIR symbol(String module, String name) {
		return new ImportIR(module, name, null, null, false, 0);
	}

	public static ImportIR symbol(String module, String name, String alias) {
		return new ImportIR(module, name, null, alias, false, 0);
	}

	public static ImportIR module(String module, String alias) {
		return new ImportIR(module, null, null, alias, false, 0);
	}

	public static ImportIR wildcard(String module) {
		return new ImportIR(module, null, null, null, true, 0);
	}

	/** The name this import binds in the file, or null for wildcard imports. */
	public String localName() {
		if (alias != null) {
			return alias;
		}
		return name != null ? name : null;
	}

	public boolean isSymbol() {
		return name != null && member == null;
	}

	public boolean isStaticMember() {
		return member != null;
	}

	public boolean isModule() {
		return name == null && !wildcard;
	}
}
