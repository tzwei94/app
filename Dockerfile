# Runtime for an executable JAR built by Maven, Gradle, or another build tool.
# JAVA_IMAGE must provide a compatible Java runtime; JAR_FILE selects one JAR.
ARG JAVA_IMAGE=eclipse-temurin:25-jdk-jammy@sha256:0cbcd13ae550a9c8334d5ef4483a4247625f47067f16127e28638fdafaeb35ea
FROM eclipse-temurin:25-jdk-jammy@sha256:0cbcd13ae550a9c8334d5ef4483a4247625f47067f16127e28638fdafaeb35ea AS certificates
ARG CA_BUNDLE_URL=https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
ARG CA_BUNDLE_SHA256=e5bb2084ccf45087bda1c9bffdea0eb15ee67f0b91646106e466714f9de3c7e3
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
RUN curl --fail --silent --show-error --location "$CA_BUNDLE_URL" -o /rds-ca.pem \
    && printf '%s  /rds-ca.pem\n' "$CA_BUNDLE_SHA256" | sha256sum -c -

FROM ${JAVA_IMAGE}
ARG JAR_FILE=target/*.jar
ARG SOURCE_SHA=local
LABEL org.opencontainers.image.revision=$SOURCE_SHA
ENV SOURCE_SHA=$SOURCE_SHA
WORKDIR /app
COPY --from=certificates /rds-ca.pem /opt/app/certs/rds-ca.pem
RUN mkdir -p /tmp /var/log/app \
    && chmod 755 /opt/app /opt/app/certs && chmod 644 /opt/app/certs/rds-ca.pem \
    && chmod 1777 /tmp \
    && chown 10001:10001 /var/log/app && chmod 700 /var/log/app
COPY --chown=10001:10001 ${JAR_FILE} /app/app.jar
VOLUME ["/tmp", "/var/log/app"]
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
