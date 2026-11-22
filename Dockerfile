# Multi-stage build for Kanishk HRM Spring Boot backend
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src src
COPY integration integration
RUN ./mvnw package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/hrm-backend-0.0.1-SNAPSHOT.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN sed -i 's/\r$//' /app/docker-entrypoint.sh && chmod +x /app/docker-entrypoint.sh && chown -R app:app /app
USER app
ENV BIND_ADDRESS=0.0.0.0
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
