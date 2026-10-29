# 1) 빌드: Gradle로 실행 가능한 jar 만들기
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

# 2) 실행: jar만 가져와서 가볍게 실행
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# 무료 서버 메모리(512MB)에 맞춰 Java 메모리 제한
ENV JAVA_OPTS="-Xmx350m -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]