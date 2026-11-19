package com.abhiraj.archyra.ai.chat;

import java.util.List;

/** A page of results, in the same shape as the other list endpoints. */
public record PageOf<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
}
