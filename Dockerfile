FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY backend/pom.xml .
COPY backend/src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/dissent-vote-1.0.0.jar app.jar
COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh
EXPOSE 8080
CMD ["/app/start.sh"]
