# Use official Clojure image with tools.deps
FROM clojure:openjdk-17-tools-deps-alpine

# Set working directory
WORKDIR /app

# Copy project files
COPY deps.edn .
COPY src/ src/
COPY resources/ resources/

# Download dependencies
RUN clojure -P

# Expose port
EXPOSE $PORT

# Run the application
CMD ["clojure", "-M", "-m", "teachers-center-backend.core"]