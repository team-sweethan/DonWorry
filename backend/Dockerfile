
# Java 21로 Spring Boot 빌드
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src
COPY . .

RUN chmod +x gradlew && \
    ./gradlew --no-daemon bootJar -x test && \
    cp "$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)" /tmp/app.jar

# 빌드한 JAR 실행
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /tmp/app.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
