package org.hl7.davinci.publish.feed;

import ca.uhn.fhir.IHapiBootOrder;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class WriteFrontierRegistrar {
	private static final Logger ourLog = LoggerFactory.getLogger(WriteFrontierRegistrar.class);

	private final IInterceptorService myInterceptorService;
	private final WriteFrontier myWriteFrontier;

	public WriteFrontierRegistrar(IInterceptorService theInterceptorService, WriteFrontier theWriteFrontier) {
		myInterceptorService = theInterceptorService;
		myWriteFrontier = theWriteFrontier;
	}

	@EventListener(classes = {ContextRefreshedEvent.class})
	@Order(IHapiBootOrder.REGISTER_INTERCEPTORS)
	public void register() {
		ourLog.info("Registering WriteFrontier with JPA interceptor service");
		myInterceptorService.registerInterceptor(myWriteFrontier);
	}
}
