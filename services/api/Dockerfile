FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 capstone
WORKDIR /app
COPY --from=build /build/target/beauty-api-0.1.0-SNAPSHOT.jar app.jar
USER capstone
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
