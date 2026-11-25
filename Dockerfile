FROM maven:3.9.12-eclipse-temurin-21-noble AS build
WORKDIR /build
COPY pom.xml ./
COPY src/ src/
RUN mvn -B -ntp verify

FROM eclipse-temurin:21-jre-noble
RUN groupadd --gid 10001 urutau \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin urutau
WORKDIR /app
COPY --from=build /build/target/urutau-jigs-*.jar /app/urutau-jigs.jar

ENV HOME=/home/urutau \
    SPRING_PROFILES_ACTIVE=railway \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
USER 10001:10001
CMD ["java", "-jar", "/app/urutau-jigs.jar"]
