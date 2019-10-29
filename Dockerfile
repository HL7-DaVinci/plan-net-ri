FROM jetty:9-jre8-alpine
USER jetty:jetty
EXPOSE 8080
FROM maven:3.6.1-jdk-8 AS build
COPY src /usr/src/app/src
COPY pom.xml /usr/src/app
RUN mvn -f /usr/src/app/pom.xml clean package

FROM jetty:9-jre8-alpine
COPY --from=build /usr/src/app/target/hapi-fhir-jpaserver.war /var/lib/jetty/webapps/hapi-fhir-jpaserver.war
EXPOSE 8080
