package com.abhiraj.archyra.user;

import java.util.UUID;

/** Published when an account is disabled. Lets other features (sessions) react without the user feature knowing them. */
public record UserDisabledEvent(UUID userId) {
}
