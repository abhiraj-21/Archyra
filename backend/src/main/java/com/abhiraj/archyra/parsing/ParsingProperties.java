package com.abhiraj.archyra.parsing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parsing configuration ({@code archyra.parsing.*}).
 *
 * @param nativeLibraryDir directory with the Tree-sitter native libraries, relative to the working
 *                         directory unless absolute
 */
@ConfigurationProperties(prefix = "archyra.parsing")
public record ParsingProperties(@DefaultValue("native") String nativeLibraryDir) {
}
