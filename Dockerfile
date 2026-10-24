FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY src/main/ src/main/

# Tests are verified separately; the image build needs no Docker test runtime.
RUN ./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

#jar has been created after build

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system --gid 10001 fluxbank \
    && useradd --system --uid 10001 --gid fluxbank --no-create-home fluxbank

WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/*.jar app.jar
#docker take the jar here with copy

USER 10001:10001
EXPOSE 8080
#contract to docker for fluxbank will run on 8080 port
ENTRYPOINT ["java", "-jar", "app.jar"]
#when the container starts it starts with theese extensions.