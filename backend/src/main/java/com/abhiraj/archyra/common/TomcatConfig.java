package com.abhiraj.archyra.common;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Node ids contain slashes ({@code file:src/main/App.java}), so clients send them percent-encoded
 * ({@code %2F}) in {@code /nodes/{nodeId}}. Tomcat rejects an encoded slash by default. "passthrough" lets
 * it through still encoded, so it stays inside its path segment; Spring then decodes the segment into the
 * {@code nodeId} variable. The slash is never turned into a path separator.
 */
@Configuration(proxyBeanMethods = false)
class TomcatConfig {

	@Bean
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> allowEncodedSlashInPathVariables() {
		return factory -> factory.addConnectorCustomizers(connector -> connector.setEncodedSolidusHandling("passthrough"));
	}
}
