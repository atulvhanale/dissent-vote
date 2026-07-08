#!/bin/sh
# Convert Render's DATABASE_URL (postgresql://) to Spring Boot format (jdbc:postgresql://)
if [ -n "$DATABASE_URL" ]; then
  export SPRING_DATASOURCE_URL="jdbc:$DATABASE_URL"
fi
exec java -jar /app/app.jar