FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon || true
COPY src/ src/
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
VOLUME /app/cache
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
