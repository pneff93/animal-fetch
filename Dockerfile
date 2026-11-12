FROM eclipse-temurin:21-jdk
WORKDIR /app

# Resolve dependencies first so they are cached between source changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q package

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "target/animal-fetch.jar"]
