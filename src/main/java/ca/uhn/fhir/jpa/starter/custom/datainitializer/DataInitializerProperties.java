package ca.uhn.fhir.jpa.starter.custom.datainitializer;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties
@Configuration
@EnableConfigurationProperties
public class DataInitializerProperties {

  private List<String> initialData;

  public List<String> getInitialData() {
    return initialData;
  }

  public void setInitialData(List<String> initialData) {
    this.initialData = initialData;
  }
  
}