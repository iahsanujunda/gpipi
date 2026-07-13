# Build the application distribution (installDist), NOT a fat jar: shading flyway-core +
# flyway-database-postgresql into one jar clobbers their shared META-INF/services files and
# Flyway NPEs on boot. The distribution keeps every jar separate. pgenGenerateCode reads the
# committed src/main/resources/pgen-spec.json, so no live database is needed at build time.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
# Warm the dependency cache in its own layer so source edits don't re-download.
RUN ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
# bin/ (launcher script) + lib/ (all jars, service files intact).
COPY --from=build /app/build/install/gpipi/ ./
# App listens on 8080 (application.conf default; PORT is left unset on Fly).
EXPOSE 8080
# The generated start script honours JAVA_OPTS. Default heap is 25% of container RAM — on a
# 512mb machine that strands most of it; 70% leaves headroom for metaspace and thread stacks.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["bin/gpipi"]
