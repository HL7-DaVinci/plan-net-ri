package org.hl7.davinci.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the Directory Crawler single-page app under /crawler, leaving the web root for
 * the HAPI tester overlay. Unknown paths under /crawler fall back to the SPA shell so the
 * client-side router can resolve deep links and page refreshes.
 */
@Configuration
public class CrawlerFrontendConfig implements WebMvcConfigurer {

	private static final String BASE_PATH = "/crawler";
	private static final String STATIC_LOCATION = "classpath:/static/crawler/";
	private static final Resource INDEX = new ClassPathResource("static/crawler/index.html");

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addRedirectViewController(BASE_PATH, BASE_PATH + "/");
		registry.addViewController(BASE_PATH + "/").setViewName("forward:" + BASE_PATH + "/index.html");
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler(BASE_PATH + "/**")
				.addResourceLocations(STATIC_LOCATION)
				.resourceChain(true)
				.addResolver(new SpaFallbackResolver());
	}

	/** Returns the requested file when it exists, otherwise the SPA shell. */
	private static final class SpaFallbackResolver extends PathResourceResolver {
		@Override
		protected Resource getResource(String resourcePath, Resource location) throws IOException {
			Resource resolved = super.getResource(resourcePath, location);
			return resolved != null ? resolved : INDEX;
		}
	}
}
