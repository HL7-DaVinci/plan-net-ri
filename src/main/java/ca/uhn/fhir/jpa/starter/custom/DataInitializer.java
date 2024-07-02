package ca.uhn.fhir.jpa.starter.custom;

import java.nio.charset.StandardCharsets;

import javax.annotation.PostConstruct;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.util.FileCopyUtils;

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
  @Autowired
  private ResourceLoader resourceLoader;

  @PostConstruct
  public void initializeData() {
    logger.info("Loading data from plan-net-data");

    Resource[] resources = null;
    String directoryPath = "plan-net-data";

    try {
      resources = ResourcePatternUtils.getResourcePatternResolver(resourceLoader).getResources("classpath:" + directoryPath + "/**/*.json");
    } catch (Exception e) {
      logger.error("Error loading resources from directory: " + directoryPath, e);
      return;
    }

    logger.info("Found " + resources.length + " resources in directory: " + directoryPath);
    int count = 0;

    for (Resource resource : resources) {
      try {
        String resourceText = new String(FileCopyUtils.copyToByteArray(resource.getInputStream()), StandardCharsets.UTF_8);

        IBaseResource fhirResource = fhirContext.newJsonParser().parseResource(resourceText);

        IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(fhirResource);
        dao.update(fhirResource, new SystemRequestDetails());
        // logger.info("Loaded resource: " + resource.getFilename());
        count++;
      } catch (Exception e) {
        logger.error("Error loading resource: " + resource.getFilename(), e);
      }
    }

    logger.info("Loaded " + count + " resources from directory: " + directoryPath);

  }

}
