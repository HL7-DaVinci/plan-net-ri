# Da Vinci PDEX Plan Network Reference Server

This project is a reference FHIR server for the [Da Vinci Payer Data exchange
Plan Network Implementation
Guide](https://build.fhir.org/ig/HL7/davinci-pdex-plan-net/index.html). It is
based on the [HAPI FHIR JPA
Server (Version 4.1.0)](https://github.com/hapifhir/hapi-fhir-jpaserver-starter).

Note: HAPI 5.1.0 has performance issues related to the location queries required for this server.

# Running Locally

The easiest way to run this server is to use docker. First, clone this
repository. Then, from the repository root run:

```
docker build -t plan-net .
```

This will build the docker image for the reference server. Once the image has
been built, the server can be run with the following command:

```
docker run -p 8080:8080 plan-net
```

Alternatively the image can be built and run using

```
./build-docker-image.sh
docker-compose up
```

The server will then be browseable at
[http://localhost:8080/](http://localhost:8080/), and the
server's FHIR endpoint will be available at
[http://localhost:8080/fhir](http://localhost:8080/fhir)

# Updating the data

The server is read only by default. To update the data use the `plan-net-write` branch. Instructions are provided in the readme of that branch.
