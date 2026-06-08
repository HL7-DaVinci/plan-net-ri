package org.hl7.davinci.api.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

  @Value("${app.fhir.servers:}")
  private String fhirServersJson;

	@Value("${api.public-base-url:}")
	private String apiBaseUrl;

  @GetMapping(value = "/crawler/config.js", produces = "application/javascript")
  public String getConfig() {
    StringBuilder config = new StringBuilder("window.APP_CONFIG = {");

    // FHIR servers
    String fhirServers = fhirServersJson.isEmpty() ? "[]" : fhirServersJson;
    config.append(" fhirServers: ").append(fhirServers);

    // API base URL
    String apiBaseUrlValue = apiBaseUrl.isEmpty() ? "null" : "\"" + apiBaseUrl + "\"";
    config.append(", apiBaseUrl: ").append(apiBaseUrlValue);

    config.append(" };");
    return config.toString();
  }
}
