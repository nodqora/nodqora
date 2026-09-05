# The jar is architecture-neutral, so a cross-architecture build (arm64 laptop, amd64 nodes) needs
# no emulation: nothing here runs at build time.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/market-aggregator.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
