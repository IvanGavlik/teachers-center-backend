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
# Render's 512 MB plan: the JVM's default heap is 25% of the container (128 MB), too little for a
# whole book in memory. 75% leaves ~130 MB for the JVM itself and PDFBox's native buffers.
CMD ["clojure", "-J-XX:MaxRAMPercentage=75", "-M", "-m", "teachers-center-backend.core"]