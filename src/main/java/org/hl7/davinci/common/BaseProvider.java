package org.hl7.davinci.common;

import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base class for HAPI plain providers: autowires the RestfulServer and registers the concrete
 * provider on it. Registration stays programmatic because a {@code hapi.fhir.custom-provider-classes}
 * entry requires the class to resolve in every boot, including test contexts that do not scan
 * {@code org.hl7.davinci}.
 */
public abstract class BaseProvider {

	@Autowired
	protected RestfulServer restfulServer;

	@PostConstruct
	public void registerProvider() {
		restfulServer.registerProvider(this);
	}
}
