package com.abhiraj.archyra.ai.retrieval;

/**
 * What a question is after, decided by fixed rules (no model): {@link #OVERVIEW} asks about the project as a whole ("what is
 * this project about?", the tech stack, how it is structured) and gets the project context pack at top priority;
 * {@link #SPECIFIC} is everything else and uses the ordinary retrieval.
 */
public enum QuestionIntent {
	OVERVIEW,
	SPECIFIC
}
