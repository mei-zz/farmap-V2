FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests clean package

FROM node:22-bookworm-slim AS pi-build
WORKDIR /app/pi-runtime
COPY pi-runtime/package.json pi-runtime/package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts
COPY pi-runtime/worker.mjs ./

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN mkdir -p /app/logs /app/uploads
COPY --from=build /build/target/zhgy-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=pi-build /usr/local/bin/node /usr/local/bin/node
COPY --from=pi-build /app/pi-runtime /app/pi-runtime
ENV FARMAP_PI_WORKER=/app/pi-runtime/worker.mjs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
