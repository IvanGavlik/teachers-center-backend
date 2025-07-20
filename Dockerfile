# Use official Clojure image
FROM clojure:openjdk-17-lein-alpine

# Set working directory
WORKDIR /app

# Copy project files
COPY deps.edn .
COPY src/ src/
COPY resources/ resources/

# Download dependencies
RUN clj -P

# Expose port
EXPOSE $PORT

# Run the application
CMD ["clj", "-M", "-m", "teachers-center-backend.core"]