# Etapa 1: Build da aplicação com Maven
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# 1. Copia só o pom.xml
COPY pom.xml .
# 2. Baixa as dependências (camada de cache)
RUN mvn dependency:go-offline

# 3. Copia o código fonte
COPY src ./src
# 4. Compila e empacota forçando a leitura em UTF-8
RUN mvn clean package -DskipTests -Dfile.encoding=UTF-8

# Etapa 2: Imagem final com JRE (menor e mais segura)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
VOLUME /tmp

# Copia o .jar gerado no estágio anterior para a imagem final
COPY --from=build /app/target/*.jar app.jar

# Expõe a porta (deve ser a mesma do application-prod.properties)
EXPOSE 8092

# Comando para iniciar a aplicação
ENTRYPOINT ["java", "-jar", "app.jar"]