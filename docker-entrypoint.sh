#!/bin/sh
set -e

# Build JDBC DB_URL automatically when hosted platforms (e.g. Render) inject discrete DB_* fields
if [ -z "$DB_URL" ] && [ -n "$DB_HOST" ] && [ -n "$DB_NAME" ]; then
  DB_PORT="${DB_PORT:-5432}"
  export DB_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=require"
fi

exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -jar /app/app.jar
