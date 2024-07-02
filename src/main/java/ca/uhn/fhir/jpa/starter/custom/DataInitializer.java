package ca.uhn.fhir.jpa.starter.custom;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;

public class DataInitializer {

  private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

  @Autowired
  private FhirContext fhirContext;

  @Autowired
  private DaoRegistry daoRegistry;

  @PostConstruct
  public void initializeData() {
    logger.info("Loading data from plan-net-data");

    Resource[] resources = null;
    String directoryPath = "plan-net-data";

    try {
      ClassLoader cl = this.getClass().getClassLoader();
			ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(cl);

      resources = resolver.getResources("classpath*:" + directoryPath + "/**/*.json");
    } catch (Exception e) {
      logger.error("Error loading resources from directory: " + directoryPath, e.getMessage());
      return;
    }

    logger.info("Found " + resources.length + " resources in directory: " + directoryPath);
    int count = 0;

    for (Resource resource : resources) {
      try {
        String resourceText = loadResource(resource);

        IBaseResource fhirResource = fhirContext.newJsonParser().parseResource(resourceText);

        IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(fhirResource);
        dao.update(fhirResource, new SystemRequestDetails());
        // logger.info("Loaded resource: " + resource.getFilename());
        count++;
      } catch (Exception e) {
        logger.error("Error loading resource: " + resource.getFilename(), e.getMessage());
      }
    }

    logger.info("Loaded " + count + " resources from directory: " + directoryPath);

  }


  protected String loadResource(Resource resource) throws IOException {

    InputStreamReader isr = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
    
    BufferedReader br = new BufferedReader(isr);
    String text = br.lines().collect(Collectors.joining("\n"));
    
    isr.close();
    br.close();
    return text;

  }

}
