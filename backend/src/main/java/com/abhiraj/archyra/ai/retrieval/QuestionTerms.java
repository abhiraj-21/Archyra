package com.abhiraj.archyra.ai.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the names of code out of a question, so that "what depends on {@code PaymentService}?" can look
 * {@code PaymentService} up in the graph instead of hoping that its vector is close: backticked terms, path-like and
 * file-like tokens, dotted names, CamelCase and snake_case identifiers, and capitalised single words. Plain lower-case
 * words are not candidates (unless backticked). The result keeps the order of the question and has no duplicates.
 */
public final class QuestionTerms {

	static final int MAX_TERMS = 24;

	private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]{1,120})`");
	private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_.\\-]+(?:/[A-Za-z0-9_.\\-]+)+");
	private static final Pattern FILE = Pattern.compile(
			"\\b[A-Za-z0-9_\\-]+\\.(?:java|kt|py|js|jsx|ts|tsx|go|rs|cs|php|rb|sol|vue|svelte|ipynb)\\b");
	private static final Pattern DOTTED = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]+(?:\\.[A-Za-z_][A-Za-z0-9_]+)+\\b");
	private static final Pattern CAMEL = Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+\\b");
	private static final Pattern LOWER_CAMEL = Pattern.compile("\\b[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)+\\b");
	private static final Pattern SNAKE = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+\\b");
	private static final Pattern CAPITALISED = Pattern.compile("\\b[A-Z][a-z0-9]{2,}\\b");
	private static final Pattern CALL_SUFFIX = Pattern.compile("\\(.*\\)$");

	/** Capitalised words that open or glue a question; they are not worth a lookup. */
	private static final Set<String> STOP_WORDS = Set.of("what", "which", "where", "when", "why", "how", "who", "whom",
			"whose", "explain", "describe", "show", "list", "tell", "give", "does", "did", "are", "can", "could",
			"should", "would", "will", "the", "this", "that", "these", "those", "there", "please", "also", "each", "any",
			"all", "some", "and", "but", "for", "with", "from", "into", "about", "has", "have", "was", "were");

	private record Found(int position, String term) {
	}

	private QuestionTerms() {
	}

	/** The candidate names in the order they appear in {@code message}; at most {@value #MAX_TERMS}. */
	public static List<String> extract(String message) {
		if (message == null || message.isBlank()) {
			return List.of();
		}
		List<Found> found = new ArrayList<>();

		Matcher backticks = BACKTICKED.matcher(message);
		while (backticks.find()) {
			String term = CALL_SUFFIX.matcher(backticks.group(1).trim()).replaceFirst("");
			if (!term.isEmpty() && term.indexOf(' ') < 0) {
				found.add(new Found(backticks.start(), term));
				addParts(found, backticks.start(), term);
			}
		}
		// what is left once the backticked terms are blanked out, positions kept
		String outside = blank(BACKTICKED, message);

		collect(PATH.matcher(outside), found, false);
		collect(FILE.matcher(outside), found, false);
		outside = blank(PATH, outside); // the parts of a path or file name (App in App.java) are not names of their own
		outside = blank(FILE, outside);
		Matcher dotted = DOTTED.matcher(outside);
		while (dotted.find()) {
			String term = dotted.group();
			if (FILE.matcher(term).matches()) {
				continue; // a file name, already collected
			}
			found.add(new Found(dotted.start(), term));
			addParts(found, dotted.start(), term);
		}
		collect(CAMEL.matcher(outside), found, false);
		collect(LOWER_CAMEL.matcher(outside), found, false);
		collect(SNAKE.matcher(outside), found, false);
		collect(CAPITALISED.matcher(outside), found, true);

		Set<String> terms = new LinkedHashSet<>();
		// stable: at one position a whole name comes before its parts
		found.stream().sorted(Comparator.comparingInt(Found::position)).forEach(entry -> terms.add(entry.term()));
		return terms.stream().limit(MAX_TERMS).toList();
	}

	private static void collect(Matcher matcher, List<Found> into, boolean skipStopWords) {
		while (matcher.find()) {
			String term = matcher.group();
			if (!(skipStopWords && STOP_WORDS.contains(term.toLowerCase(Locale.ROOT)))) {
				into.add(new Found(matcher.start(), term));
			}
		}
	}

	private static String blank(Pattern pattern, String text) {
		return pattern.matcher(text).replaceAll(match -> " ".repeat(match.group().length()));
	}

	/**
	 * {@code UserService.create} also names {@code UserService}, {@code com.foo.Bar} also names {@code Bar}, and
	 * {@code OrderService#place(OrderRequest)} names the type {@code OrderService} and the method {@code place}.
	 */
	private static void addParts(List<Found> into, int position, String name) {
		int hash = name.indexOf('#');
		String owner = hash >= 0 ? name.substring(0, hash) : name;
		for (String part : owner.split("\\.")) {
			if (part.length() >= 3 && Character.isUpperCase(part.charAt(0))) {
				into.add(new Found(position, part));
			}
		}
		if (hash >= 0) {
			String member = CALL_SUFFIX.matcher(name.substring(hash + 1)).replaceFirst("");
			if (!member.isEmpty()) {
				into.add(new Found(position, member));
			}
		}
	}
}
