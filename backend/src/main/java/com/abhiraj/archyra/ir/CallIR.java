package com.abhiraj.archyra.ir;

/**
 * A method invocation inside a method body.
 *
 * @param name          invoked method name
 * @param receiver      source text of the receiver expression ({@code userService}, {@code this},
 *                      {@code Util}, {@code a.b()}), or null for an unqualified call
 * @param argumentCount number of arguments
 * @param line          1-based line of the call
 */
public record CallIR(String name, String receiver, int argumentCount, int line) {
}
