package org.hl7.davinci.api.web;

import org.hl7.davinci.api.config.ApiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

	private final ApiProperties props;

	public ConfigController(ApiProperties props) {
		this.props = props;
	}

	@GetMapping(value = "/crawler/config.js", produces = "application/javascript")
	public String getConfig() {
		StringBuilder config = new StringBuilder("window.APP_CONFIG = {");

		// FHIR servers
		String fhirServers = props.getFhirServers();
		config.append(" fhirServers: ").append(isBlank(fhirServers) ? "[]" : fhirServers);

		// API base URL
		String apiBaseUrl = props.getPublicBaseUrl();
		config.append(", apiBaseUrl: ").append(isBlank(apiBaseUrl) ? "null" : "\"" + apiBaseUrl + "\"");

		config.append(" };");
		return config.toString();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isEmpty();
	}
}
