FROM azul/zulu-openjdk-alpine:11 as fineract
RUN mkdir /opt/app
COPY target/phee-zeebe-nats-importer-rdbms-1.0.0-SNAPSHOT.jar /opt/app
CMD ["java", "-jar", "-Djava.security.egd=file:/dev/./urandom", "/opt/app/phee-zeebe-nats-importer-rdbms-1.0.0-SNAPSHOT.jar"]