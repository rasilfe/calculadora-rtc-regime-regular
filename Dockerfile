# Imagem do worker de cálculo (também usada pelo Job de migração).
#   docker build -t calculadora-rtc-worker:local .   (no CI/CD: .github/workflows/deploy.yml publica no GHCR)

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/api-regime-geral.jar extract --layers --launcher --destination /app

FROM eclipse-temurin:25-jre
WORKDIR /app
# Camadas do Spring Boot: dependências mudam pouco e ficam em cache entre versões.
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER 10001
EXPOSE 9101
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
