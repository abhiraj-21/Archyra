package com.abhiraj.archyra.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import jakarta.validation.constraints.NotBlank;

/** @param from the sender address of every email Archyra sends ({@code app.mail.from}) */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(@DefaultValue("no-reply@archyra.local") @NotBlank String from) {
}
