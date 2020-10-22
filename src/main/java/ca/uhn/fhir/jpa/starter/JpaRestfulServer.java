package ca.uhn.fhir.jpa.starter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import javax.servlet.ServletException;

@Import(AppProperties.class)
public class JpaRestfulServer extends BaseJpaRestfulServer {

  @Autowired
  AppProperties appProperties;

  private static final long serialVersionUID = 1L;

  public JpaRestfulServer() {
    super();
  }

  @Override
  protected void initialize() throws ServletException {
    super.initialize();

    // Add ReadOnly Interceptor
    ReadOnlyInterceptor readOnlyInterceptor = new ReadOnlyInterceptor(); 
    this.registerInterceptor(readOnlyInterceptor);

    // Add MetaData provider
    MetadataProvider metadata = new MetadataProvider(this, this.fhirSystemDao, this.daoConfig, this.searchParamRegistry);
    this.setServerConformanceProvider(metadata);
  }

}
