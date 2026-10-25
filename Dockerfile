FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/*.jar /app/app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home appuser
WORKDIR /app
COPY --from=build /app/app.jar /app/app.jar
USER appuser
ENV SPRING_PROFILES_ACTIVE=production \
    SPRING_CONFIG_LOCATION=classpath:/application-production.properties \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
