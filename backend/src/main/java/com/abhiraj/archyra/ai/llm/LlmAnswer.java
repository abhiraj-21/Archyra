package com.abhiraj.archyra.ai.llm;

/** The text a provider answered with, and which provider it was. */
public record LlmAnswer(String text, ProviderId provider) {
}
