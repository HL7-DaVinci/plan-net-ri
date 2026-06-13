package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.web.ConfigController;
import org.junit.jupiter.api.Test;

class ConfigControllerTest {

	@Test
	void emitsDefaultsWhenNothingIsConfigured() {
		ConfigController controller = new ConfigController(new ApiProperties());

		assertEquals("window.APP_CONFIG = { fhirServers: [], apiBaseUrl: null };", controller.getConfig());
	}

	@Test
	void emitsConfiguredServersAndBaseUrl() {
		ApiProperties props = new ApiProperties();
		props.setFhirServers("[{\"name\":\"Prod\",\"url\":\"https://x/fhir\"}]");
		props.setPublicBaseUrl("https://api.example.org");

		assertEquals(
				"window.APP_CONFIG = { fhirServers: [{\"name\":\"Prod\",\"url\":\"https://x/fhir\"}],"
						+ " apiBaseUrl: \"https://api.example.org\" };",
				new ConfigController(props).getConfig());
	}
}
