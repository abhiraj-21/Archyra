package com.abhiraj.archyra.ai.llm;

import java.util.regex.Pattern;

/**
 * Cleans what a model wrote before the rest of the application sees it: reasoning models that think inside the answer
 * ({@code <think>...</think>}) must not leak their thoughts into a citation-checked answer. The result may be empty, which
 * the providers treat as a failed answer.
 */
final class AnswerText {

	private static final Pattern THINK_BLOCK = Pattern.compile("<think(?:ing)?>.*?</think(?:ing)?>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern CLOSING = Pattern.compile("</think(?:ing)?>", Pattern.CASE_INSENSITIVE);
	private static final Pattern OPENING = Pattern.compile("<think(?:ing)?>", Pattern.CASE_INSENSITIVE);

	private AnswerText() {
	}

	/** Without think blocks (also a closing tag with no opening one, and an opening tag that never closes), trimmed; never null. */
	static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		String text = THINK_BLOCK.matcher(raw).replaceAll("");
		var closing = CLOSING.matcher(text);
		int afterLast = -1;
		while (closing.find()) {
			afterLast = closing.end(); // the template opened the block, so only the end tag is in the output
		}
		if (afterLast >= 0) {
			text = text.substring(afterLast);
		}
		var opening = OPENING.matcher(text);
		if (opening.find()) {
			text = text.substring(0, opening.start()); // the output was cut inside the thinking
		}
		return text.strip();
	}
}
