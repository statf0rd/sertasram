# Образ сигналинг-сервера sertasram.
# Сборка:  docker build -t sertas-signaling .
# Запуск:  docker run --rm -p 8080:8080 -e SERTAS_TOKEN=... sertas-signaling

# --- Этап 1: сборка дистрибутива на JDK 21 ---
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src

# Сначала только файлы сборки: слой с зависимостями кешируется, пока они не менялись.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version

# Серверу нужны только эти три модуля (signaling-client - для тестов сервера).
COPY protocol protocol
COPY signaling-server signaling-server
COPY signaling-client signaling-client
# settings.gradle.kts перечисляет и клиентские модули; пустые каталоги для них
# достаточно, чтобы Gradle сконфигурировал проект, не таща нативный клиентский код в образ.
RUN mkdir -p media media-engine app-client \
 && ./gradlew --no-daemon :signaling-server:installDist

# --- Этап 2: минимальный образ запуска (только JRE) ---
FROM eclipse-temurin:21-jre-alpine
# Обновления безопасности базового образа и непривилегированный пользователь.
RUN apk upgrade --no-cache \
 && addgroup -S sertas && adduser -S -G sertas -H -s /sbin/nologin sertas
WORKDIR /opt/sertas-signaling
COPY --from=build /src/signaling-server/build/install/signaling-server/ ./
USER sertas
EXPOSE 8080
# Куче хватает 256 МБ; лимит задаётся явно, чтобы JVM не занимала всю память хоста.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/health | grep -q ok || exit 1
ENTRYPOINT ["bin/signaling-server"]
CMD ["8080"]
