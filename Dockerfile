FROM eclipse-temurin:27-jdk@sha256:5e6d9093c7a8e95adfaa8828253c58f2c6820116d07a062440a778358807578a AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY config/ config/
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp verify

FROM eclipse-temurin:27-jre@sha256:acfb350b50e707addf3dcbb47f7052d1882d1e5f1670ce838a9c7838d82d4fe5 AS runtime
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/transaction-service-1.0.0.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
