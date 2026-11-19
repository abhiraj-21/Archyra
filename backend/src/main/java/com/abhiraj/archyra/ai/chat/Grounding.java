package com.abhiraj.archyra.ai.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.abhiraj.archyra.ai.retrieval.ContextItem;

/**
 * Makes an answer's citations trustworthy. The model cites numbered context items; this reads the {@code [n]} markers,
 * ignores any number that is not one of the items, renumbers the rest by first appearance ({@code 1..m}) and reports which
 * items were cited, in that order. A node id the model might have invented can never appear, because ids are never shown
 * to the model.
 *
 * <p>Markers inside code (fenced blocks, inline code) are left alone: {@code xs[1]} is an index, not a citation.
 * {@code [1, 2]} becomes {@code [1][2]}, and a marker followed by {@code (} is a Markdown link and is left alone.
 */
final class Grounding {

	/** @param text the answer with its markers renumbered; @param cited the items it cites, {@code cited.get(r - 1)} is {@code [r]} */
	record Outcome(String text, List<ContextItem> cited) {
	}

	private static final Pattern CODE = Pattern.compile("(?s)```.*?(?:```|$)|`[^`\\n]*`");
	private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,4}(?:\\s*,\\s*\\d{1,4})*)\\](?!\\()");

	private Grounding() {
	}

	/**
	 * @param accept which items (0-based) may be cited; markers of other items are dropped like out-of-range ones
	 */
	static Outcome ground(String answer, List<ContextItem> items, IntPredicate accept) {
		Map<Integer, Integer> refOf = new LinkedHashMap<>();
		StringBuilder out = new StringBuilder(answer.length());
		Matcher code = CODE.matcher(answer);
		int last = 0;
		while (code.find()) {
			rewrite(answer.substring(last, code.start()), items.size(), accept, refOf, out);
			out.append(code.group());
			last = code.end();
		}
		rewrite(answer.substring(last), items.size(), accept, refOf, out);

		List<ContextItem> cited = new ArrayList<>();
		for (int index : refOf.keySet()) {
			cited.add(items.get(index));
		}
		return new Outcome(out.toString(), cited);
	}

	/** The items an answer cites, as 0-based indexes, by first appearance. */
	static List<Integer> citedIndexes(String answer, int itemCount) {
		Map<Integer, Integer> refOf = new LinkedHashMap<>();
		Matcher code = CODE.matcher(answer);
		int last = 0;
		StringBuilder sink = new StringBuilder();
		while (code.find()) {
			rewrite(answer.substring(last, code.start()), itemCount, index -> true, refOf, sink);
			last = code.end();
		}
		rewrite(answer.substring(last), itemCount, index -> true, refOf, sink);
		return List.copyOf(refOf.keySet());
	}

	private static void rewrite(String text, int itemCount, IntPredicate accept, Map<Integer, Integer> refOf, StringBuilder out) {
		Matcher marker = MARKER.matcher(text);
		int last = 0;
		while (marker.find()) {
			out.append(text, last, marker.start());
			Set<Integer> refs = new LinkedHashSet<>();
			for (String number : marker.group(1).split("\\s*,\\s*")) {
				int index = Integer.parseInt(number) - 1;
				if (index >= 0 && index < itemCount && accept.test(index)) {
					refs.add(refOf.computeIfAbsent(index, key -> refOf.size() + 1));
				}
			}
			if (refs.isEmpty()) {
				if (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
					out.setLength(out.length() - 1); // "claim [99]." becomes "claim."
				}
			}
			else {
				refs.forEach(ref -> out.append('[').append(ref).append(']'));
			}
			last = marker.end();
		}
		out.append(text, last, text.length());
	}
}
