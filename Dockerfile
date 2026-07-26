# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# Bring in the bundled JMT jar first so the system-scope dependency resolves.
COPY lib/ lib/
COPY pom.xml .
RUN mvn -q -o -DskipTests dependency:go-offline || mvn -q -DskipTests dependency:go-offline
COPY src/ src/
# Package + copy runtime dependencies (see pom dependency-plugin, Task 1).
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/qsim-service.jar app.jar
COPY --from=build /src/target/dependency/ dependency/
# System-scoped JMT is excluded from copy-dependencies; bring it explicitly.
COPY --from=build /src/lib/JMT-singlejar-1.4.0.jar dependency/JMT-singlejar-1.4.0.jar
COPY NOTICE LICENSE ./
ENV QSIM_PORT=8080
EXPOSE 8080
# Headless is also set in App.main; -Djava.awt.headless=true is belt-and-suspenders.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-cp", "app.jar:dependency/*", "qsim.http.App"]
