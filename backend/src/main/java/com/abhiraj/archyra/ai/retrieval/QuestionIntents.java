package com.abhiraj.archyra.ai.retrieval;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tells an overview question from a specific one, with whole-phrase patterns over the lower-cased question with its
 * punctuation removed (so "What's this project about?" and "what is this project about" are the same). A phrase must be the
 * whole question, apart from a polite opening and a short closing ("please", "to me", "in a few sentences"): "explain the
 * project plan for Q4" is not an overview of the repository, nor is "what is the repository pattern" or "what does the app
 * module do". A question that names a piece of code (a CamelCase, dotted, snake_case or backticked name, a path or a file) is
 * {@link QuestionIntent#SPECIFIC} even if it contains one of the phrases. Deterministic and cheap: it runs on every question.
 */
public final class QuestionIntents {

	private static final String DET = "(?:(?:this|the|that|my|our|your|current|whole|entire) )?";
	private static final String PROJECT = "(?:project|repo|repository|codebase|code base|app|application|source code)";
	private static final String THE_PROJECT = DET + PROJECT;
	private static final String OPENING = "^(?:(?:please|hey|hi|hello|ok|okay|so) )*(?:(?:can|could|would|will) you (?:please )?)?";
	private static final String CLOSING = "(?: (?:to me|for me|please|briefly|quickly|in short|in a nutshell|in a few sentences|in simple terms|in detail|here|overall|in general|at a high level|high level))*$";

	private static final List<Pattern> OVERVIEW = Stream.of(
			// what is this project (about / for)
			"what is " + THE_PROJECT + "(?: about| for| used for| meant for| all about| supposed to do)?",
			"what does " + THE_PROJECT + "(?: actually)? do",
			"what does this do",
			"what is (?:it|this) about",
			"what is (?:it|this) for",
			// summarise, describe, explain, tell me about
			"(?:(?:tell|show) me )?(?:summari[sz]e|describe|explain|tell me about|walk me through) " + THE_PROJECT,
			"(?:(?:tell|show) me )?(?:give me|show me|i want|i need) (?:an? )?(?:high level |quick |short |brief )?(?:overview|summary) of " + THE_PROJECT,
			"(?:an? )?(?:high level |quick |short |brief )?(?:overview|summary) of " + THE_PROJECT,
			"(?:give me )?(?:an? )?" + PROJECT + " (?:overview|summary)",
			// purpose
			"what is the (?:purpose|goal|aim|point|idea) of " + THE_PROJECT,
			"(?:the )?purpose of " + THE_PROJECT,
			// tech stack
			"(?:what is |show me |tell me )?(?:the )?tech(?:nology)? stack(?: of " + THE_PROJECT + ")?(?: used| used here| in use)?",
			"what tech(?:nology)? stack (?:does|is) (?:it|this|" + THE_PROJECT + ") (?:use|using|built with|used)",
			"(?:what|which) (?:programming )?(?:languages|frameworks|technologies|libraries)",
			"(?:what|which) (?:programming )?(?:languages|frameworks|technologies|libraries) (?:does|do) (?:it|this|" + THE_PROJECT + ") use",
			"(?:what|which) (?:programming )?(?:languages|frameworks|technologies|libraries) (?:is|are) (?:used|in use)(?: in| by)?(?: " + THE_PROJECT + ")?",
			"(?:what|which) (?:programming )?languages? (?:is|are) (?:it|this|" + THE_PROJECT + ") (?:written|built|made|coded) (?:in|with|using)",
			"what is (?:it|this|" + THE_PROJECT + ") (?:written|built|made|coded) (?:in|with|using)",
			// structure and architecture
			"how is " + THE_PROJECT + " (?:structured|organi[sz]ed|laid out|arranged|designed)",
			"how is it (?:structured|organi[sz]ed|laid out|arranged)",
			"what is the (?:structure|layout|architecture|organi[sz]ation) of " + THE_PROJECT,
			"(?:describe|explain|show me) the (?:structure|layout|architecture) of " + THE_PROJECT,
			"(?:what is )?(?:the )?(?:high level|big picture) (?:architecture|overview|summary|structure)(?: of " + THE_PROJECT + ")?")
			.map(body -> Pattern.compile(OPENING + "(?:" + body + ")" + CLOSING)).toList();

	private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");

	private QuestionIntents() {
	}

	public static QuestionIntent classify(String message) {
		if (message == null || message.isBlank() || namesCode(message)) {
			return QuestionIntent.SPECIFIC;
		}
		String text = message.toLowerCase(Locale.ROOT).replace("’", "'").replaceAll("\\b(what|that|where|how)'s\\b", "$1 is");
		text = NON_WORD.matcher(text.replace("high-level", "high level")).replaceAll(" ").strip();
		for (Pattern pattern : OVERVIEW) {
			if (pattern.matcher(text).matches()) {
				return QuestionIntent.OVERVIEW;
			}
		}
		return QuestionIntent.SPECIFIC;
	}

	/**
	 * Whether the question names code: anything {@link QuestionTerms} finds except a plain capitalised word (a project's own
	 * name, "Java", the first word of a sentence), which says nothing about code.
	 */
	private static boolean namesCode(String message) {
		for (String term : QuestionTerms.extract(message)) {
			boolean plainWord = term.matches("[A-Z][a-z0-9]+") && !message.contains("`" + term + "`");
			if (!plainWord) {
				return true;
			}
		}
		return false;
	}
}
