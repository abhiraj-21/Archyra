package com.abhiraj.archyra.user;

import java.util.List;

/** Response body of {@code GET /api/v1/admin/users}, newest accounts first. */
public record AdminUserPageResponse(List<AdminUserResponse> items, int page, int size, long totalElements, long totalPages) {
}
