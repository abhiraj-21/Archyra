package com.abhiraj.archyra.parsing;

/** A file that could not be parsed. The analysis continues without it. */
public record ParseFailure(String path, String reason) {
}
