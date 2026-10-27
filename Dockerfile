# The import as an image: a multi-stage build, a non-root user, a pinned base.
#   docker run --rm --network host --env-file .env -v "$PWD/data:/app/data" restartable-import:dev \
#     --command=import --import.file=data/products.csv --run.id=first
FROM eclipse-temurin:21.0.5_11-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21.0.5_11-jre-jammy
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /build/target/restartable-import-0.1.0.jar app.jar
COPY sql sql
RUN mkdir -p data && chown app data
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
CMD ["--command=health"]
