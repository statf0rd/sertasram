# Фаза 0: Сигналинг и фундамент — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Рабочий сквозной сигналинг-тракт (WebSocket-сервер + клиент + протокол) и оттестированные чистые медиа-утилиты (SDP-munging, конвертация аудио), на которых строится realtime-меш.

**Architecture:** Gradle multi-module на JDK 21. Вся логика сигналинга изолирована от транспорта (Javalin/`java.net.http`) в чистом `SignalingService`, тестируемом юнит-тестами; сетевой слой — тонкая обёртка. Медиа-утилиты — чистые функции, тестируемые без нативных библиотек и устройств. webrtc-java и JavaFX-рантайм НЕ входят в Фазу 0 (медиа-движок — Фаза 1), что делает первый срез полностью собираемым и тестируемым в headless-окружении.

**Tech Stack:** Java 21, Gradle (wrapper), Jackson 2.17 (JSON), Javalin 6 (WS-сервер на Jetty), `java.net.http.WebSocket` (WS-клиент, без доп. зависимостей), JUnit 5.

**Примечание по структуре:** спека рисовала под-модули `app-client/*`. В Фазе 0 разделение ответственности обеспечивается отдельными Gradle-модулями `:protocol`, `:signaling-client`, `:media` и пакетами внутри `:app-client` — это даёт ту же изоляцию с меньшей церемонией. Сигнатуры и контракты соответствуют спеке §5–§9.

---

## Структура файлов

```
sertas/
├── settings.gradle.kts                 # подключение модулей
├── build.gradle.kts                    # общая конфигурация (java 21, junit)
├── gradle/libs.versions.toml           # version catalog
├── gradle/wrapper/…                    # Gradle wrapper (генерируется)
├── protocol/
│   ├── build.gradle.kts
│   └── src/main/java/dev/sertas/protocol/
│       ├── SignalMessage.java          # sealed interface + records (Join, RoomState, …)
│       ├── Peer.java                   # record(id, name)
│       └── SignalCodec.java            # Jackson encode/decode
│   └── src/test/java/dev/sertas/protocol/SignalCodecTest.java
├── signaling-server/
│   ├── build.gradle.kts
│   └── src/main/java/dev/sertas/signaling/
│       ├── Participant.java            # record(id, name, room)
│       ├── RoomRegistry.java           # комнаты в памяти
│       ├── Outbound.java               # record(recipientId, SignalMessage)
│       ├── SignalingService.java       # чистая логика: onMessage/onDisconnect → List<Outbound>
│       └── SignalingServer.java        # Javalin WS-обёртка
│   └── src/test/java/dev/sertas/signaling/
│       ├── RoomRegistryTest.java
│       ├── SignalingServiceTest.java
│       └── SignalingEndToEndTest.java  # реальный сервер + 2 клиента на localhost
├── signaling-client/
│   ├── build.gradle.kts
│   └── src/main/java/dev/sertas/signaling/client/
│       ├── SignalingClient.java        # java.net.http.WebSocket + SignalCodec
│       └── SignalingListener.java      # колбэки
├── media/
│   ├── build.gradle.kts
│   └── src/main/java/dev/sertas/media/
│       ├── OpusSdpMunger.java          # «музыкальный» профиль Opus в SDP
│       └── AudioFormatConverter.java   # Float32 planar → S16 interleaved, тишина
│   └── src/test/java/dev/sertas/media/
│       ├── OpusSdpMungerTest.java
│       └── AudioFormatConverterTest.java
└── app-client/
    ├── build.gradle.kts
    └── src/main/java/dev/sertas/app/
        ├── SertasApp.java              # JavaFX Application (скелет)
        └── ui/JoinView.java            # экран входа в комнату
```

---

### Task 1: Gradle-скелет и version catalog

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Generate: `gradle/wrapper/*`, `gradlew`, `gradlew.bat`

- [ ] **Step 1: settings.gradle.kts**

```kotlin
rootProject.name = "sertas"
include("protocol", "signaling-server", "signaling-client", "media", "app-client")
```

- [ ] **Step 2: gradle/libs.versions.toml**

```toml
[versions]
jackson = "2.17.2"
javalin = "6.3.0"
slf4j = "2.0.13"
junit = "5.10.3"
javafx = "21.0.4"

[libraries]
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
javalin = { module = "io.javalin:javalin", version.ref = "javalin" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version = "1.10.3" }
```

- [ ] **Step 3: root build.gradle.kts**

```kotlin
plugins { java }
subprojects {
    apply(plugin = "java")
    repositories { mavenCentral() }
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }
    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }
    tasks.withType<Test> { useJUnitPlatform() }
}
```
(`rootProject.libs` доступен через version catalog accessor; если accessor в subprojects не виден, заменить на строковые координаты.)

- [ ] **Step 4: Сгенерировать wrapper и проверить**

Run: `/tmp/gradle-8.10.2/bin/gradle wrapper --gradle-version 8.10.2 --distribution-type bin`
Then: `./gradlew projects`
Expected: список из 5 подпроектов, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "build: gradle multi-module skeleton"
```

---

### Task 2: Протокол сигналинга (`:protocol`)

**Files:**
- Create: `protocol/build.gradle.kts`, `protocol/src/main/java/dev/sertas/protocol/{Peer,SignalMessage,SignalCodec}.java`
- Test: `protocol/src/test/java/dev/sertas/protocol/SignalCodecTest.java`

- [ ] **Step 1: build.gradle.kts**

```kotlin
dependencies { implementation(rootProject.libs.jackson.databind) }
```

- [ ] **Step 2: Написать падающий тест round-trip**

```java
package dev.sertas.protocol;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SignalCodecTest {
    @Test void roundTripJoin() {
        var codec = new SignalCodec();
        var msg = new SignalMessage.Join("ROOM1", "Alice");
        String json = codec.encode(msg);
        assertTrue(json.contains("\"type\":\"join\""));
        assertEquals(msg, codec.decode(json));
    }
    @Test void roundTripRoomState() {
        var codec = new SignalCodec();
        var msg = new SignalMessage.RoomState("id-1", List.of(new Peer("id-2","Bob")));
        assertEquals(msg, codec.decode(codec.encode(msg)));
    }
    @Test void roundTripOffer() {
        var codec = new SignalCodec();
        var msg = new SignalMessage.Offer("peer-9", "v=0...");
        assertEquals(msg, codec.decode(codec.encode(msg)));
    }
}
```

- [ ] **Step 3: Run — должен НЕ компилироваться/падать**

Run: `./gradlew :protocol:test`
Expected: FAIL (классы ещё не существуют).

- [ ] **Step 4: Реализовать типы**

`Peer.java`:
```java
package dev.sertas.protocol;
public record Peer(String id, String name) {}
```

`SignalMessage.java` (sealed + Jackson polymorphism по полю `type`):
```java
package dev.sertas.protocol;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SignalMessage.Join.class,        name = "join"),
    @JsonSubTypes.Type(value = SignalMessage.RoomState.class,   name = "room-state"),
    @JsonSubTypes.Type(value = SignalMessage.PeerJoined.class,  name = "peer-joined"),
    @JsonSubTypes.Type(value = SignalMessage.PeerLeft.class,    name = "peer-left"),
    @JsonSubTypes.Type(value = SignalMessage.Offer.class,       name = "offer"),
    @JsonSubTypes.Type(value = SignalMessage.Answer.class,      name = "answer"),
    @JsonSubTypes.Type(value = SignalMessage.Ice.class,         name = "ice"),
    @JsonSubTypes.Type(value = SignalMessage.TrackMeta.class,   name = "track-meta"),
})
public sealed interface SignalMessage {
    record Join(String room, String name) implements SignalMessage {}
    record RoomState(String selfId, List<Peer> peers) implements SignalMessage {}
    record PeerJoined(String id, String name) implements SignalMessage {}
    record PeerLeft(String id) implements SignalMessage {}
    record Offer(String peer, String sdp) implements SignalMessage {}
    record Answer(String peer, String sdp) implements SignalMessage {}
    record Ice(String peer, String candidate, String sdpMid, int sdpMLineIndex) implements SignalMessage {}
    record TrackMeta(String peer, String kind, String state) implements SignalMessage {}
}
```
Поле `peer` = адресат на пути client→server и источник на пути server→client (сервер переписывает).

`SignalCodec.java`:
```java
package dev.sertas.protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

public final class SignalCodec {
    private final ObjectMapper mapper = new ObjectMapper();
    public String encode(SignalMessage msg) {
        try { return mapper.writeValueAsString(msg); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }
    public SignalMessage decode(String json) {
        try { return mapper.readValue(json, SignalMessage.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("bad signal: " + json, e); }
    }
}
```

- [ ] **Step 5: Run — PASS**

Run: `./gradlew :protocol:test` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit** — `git commit -am "feat(protocol): signaling messages + JSON codec"`

---

### Task 3: RoomRegistry (`:signaling-server`)

**Files:**
- Create: `signaling-server/build.gradle.kts`, `.../signaling/{Participant,RoomRegistry}.java`
- Test: `.../signaling/RoomRegistryTest.java`

- [ ] **Step 1: build.gradle.kts**

```kotlin
dependencies {
    implementation(project(":protocol"))
    implementation(rootProject.libs.javalin)
    runtimeOnly(rootProject.libs.slf4j.simple)
    testImplementation(project(":signaling-client"))
}
```

- [ ] **Step 2: Падающий тест**

```java
package dev.sertas.signaling;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomRegistryTest {
    @Test void joinAddsParticipantAndListsPeersExcludingSelf() {
        var r = new RoomRegistry();
        r.join("a", "Alice", "ROOM");
        r.join("b", "Bob", "ROOM");
        assertEquals("ROOM", r.roomOf("a"));
        assertEquals(1, r.peersInRoom("ROOM", "a").size());
        assertEquals("b", r.peersInRoom("ROOM", "a").get(0).id());
    }
    @Test void leaveReturnsRoomAndRemoves() {
        var r = new RoomRegistry();
        r.join("a", "Alice", "ROOM");
        assertEquals("ROOM", r.leave("a"));
        assertNull(r.roomOf("a"));
    }
    @Test void leaveUnknownReturnsNull() {
        assertNull(new RoomRegistry().leave("ghost"));
    }
}
```

- [ ] **Step 3: Run → FAIL.** `./gradlew :signaling-server:test`

- [ ] **Step 4: Реализация**

`Participant.java`:
```java
package dev.sertas.signaling;
public record Participant(String id, String name, String room) {}
```

`RoomRegistry.java`:
```java
package dev.sertas.signaling;
import dev.sertas.protocol.Peer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RoomRegistry {
    private final Map<String, Participant> byId = new ConcurrentHashMap<>();

    public void join(String id, String name, String room) {
        byId.put(id, new Participant(id, name, room));
    }
    /** @return имя покинутой комнаты или null, если участник неизвестен. */
    public String leave(String id) {
        Participant p = byId.remove(id);
        return p == null ? null : p.room();
    }
    public String roomOf(String id) {
        Participant p = byId.get(id);
        return p == null ? null : p.room();
    }
    public List<Peer> peersInRoom(String room, String excludeId) {
        List<Peer> out = new ArrayList<>();
        for (Participant p : byId.values())
            if (p.room().equals(room) && !p.id().equals(excludeId))
                out.add(new Peer(p.id(), p.name()));
        return out;
    }
    public Participant get(String id) { return byId.get(id); }
}
```

- [ ] **Step 5: Run → PASS.**
- [ ] **Step 6: Commit** — `git commit -am "feat(signaling): in-memory room registry"`

---

### Task 4: SignalingService — чистая логика маршрутизации

**Files:**
- Create: `.../signaling/{Outbound,SignalingService}.java`
- Test: `.../signaling/SignalingServiceTest.java`

- [ ] **Step 1: Падающий тест (ядро Фазы 0)**

```java
package dev.sertas.signaling;
import dev.sertas.protocol.SignalMessage;
import dev.sertas.protocol.SignalMessage.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SignalingServiceTest {
    @Test void joinNotifiesSelfStateAndExistingPeers() {
        var svc = new SignalingService();
        svc.onMessage("a", new Join("ROOM", "Alice"));   // первый — пиров нет
        List<Outbound> out = svc.onMessage("b", new Join("ROOM", "Bob"));
        // b получает room-state со списком [a]; a получает peer-joined(b)
        assertTrue(out.contains(new Outbound("b",
            new RoomState("b", List.of(new dev.sertas.protocol.Peer("a","Alice"))))));
        assertTrue(out.contains(new Outbound("a", new PeerJoined("b","Bob"))));
    }
    @Test void offerIsRelayedToTargetWithSenderAsPeer() {
        var svc = new SignalingService();
        svc.onMessage("a", new Join("ROOM", "Alice"));
        svc.onMessage("b", new Join("ROOM", "Bob"));
        List<Outbound> out = svc.onMessage("a", new Offer("b", "SDP"));
        assertEquals(List.of(new Outbound("b", new Offer("a", "SDP"))), out);
    }
    @Test void disconnectNotifiesRemainingPeers() {
        var svc = new SignalingService();
        svc.onMessage("a", new Join("ROOM", "Alice"));
        svc.onMessage("b", new Join("ROOM", "Bob"));
        assertEquals(List.of(new Outbound("b", new PeerLeft("a"))), svc.onDisconnect("a"));
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Реализация**

`Outbound.java`:
```java
package dev.sertas.signaling;
import dev.sertas.protocol.SignalMessage;
public record Outbound(String recipientId, SignalMessage message) {}
```

`SignalingService.java`:
```java
package dev.sertas.signaling;
import dev.sertas.protocol.Peer;
import dev.sertas.protocol.SignalMessage;
import dev.sertas.protocol.SignalMessage.*;
import java.util.ArrayList;
import java.util.List;

/** Транспорт-независимая логика сигналинга. Возвращает что и кому отправить. */
public final class SignalingService {
    private final RoomRegistry registry = new RoomRegistry();

    public List<Outbound> onMessage(String connId, SignalMessage msg) {
        List<Outbound> out = new ArrayList<>();
        if (msg instanceof Join j) {
            registry.join(connId, j.name(), j.room());
            List<Peer> peers = registry.peersInRoom(j.room(), connId);
            out.add(new Outbound(connId, new RoomState(connId, peers)));
            for (Peer p : peers) out.add(new Outbound(p.id(), new PeerJoined(connId, j.name())));
        } else if (msg instanceof Offer o) {
            out.add(new Outbound(o.peer(), new Offer(connId, o.sdp())));
        } else if (msg instanceof Answer a) {
            out.add(new Outbound(a.peer(), new Answer(connId, a.sdp())));
        } else if (msg instanceof Ice ice) {
            out.add(new Outbound(ice.peer(), new Ice(connId, ice.candidate(), ice.sdpMid(), ice.sdpMLineIndex())));
        } else if (msg instanceof TrackMeta tm) {
            String room = registry.roomOf(connId);
            if (room != null)
                for (Peer p : registry.peersInRoom(room, connId))
                    out.add(new Outbound(p.id(), new TrackMeta(connId, tm.kind(), tm.state())));
        }
        return out;
    }

    public List<Outbound> onDisconnect(String connId) {
        String room = registry.roomOf(connId);
        List<Outbound> out = new ArrayList<>();
        if (room != null)
            for (Peer p : registry.peersInRoom(room, connId))
                out.add(new Outbound(p.id(), new PeerLeft(connId)));
        registry.leave(connId);
        return out;
    }
}
```

- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `git commit -am "feat(signaling): transport-independent routing service"`

---

### Task 5: SignalingClient (`:signaling-client`)

**Files:**
- Create: `signaling-client/build.gradle.kts`, `.../signaling/client/{SignalingListener,SignalingClient}.java`

- [ ] **Step 1: build.gradle.kts**

```kotlin
dependencies { implementation(project(":protocol")) }
```

- [ ] **Step 2: SignalingListener.java**

```java
package dev.sertas.signaling.client;
import dev.sertas.protocol.SignalMessage;
public interface SignalingListener {
    void onMessage(SignalMessage msg);
    default void onOpen() {}
    default void onClose(int code, String reason) {}
    default void onError(Throwable t) {}
}
```

- [ ] **Step 3: SignalingClient.java** (на `java.net.http.WebSocket`, без сторонних зависимостей)

```java
package dev.sertas.signaling.client;
import dev.sertas.protocol.SignalCodec;
import dev.sertas.protocol.SignalMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

public final class SignalingClient implements WebSocket.Listener {
    private final SignalCodec codec = new SignalCodec();
    private final SignalingListener listener;
    private final StringBuilder buffer = new StringBuilder();
    private volatile WebSocket ws;

    public SignalingClient(SignalingListener listener) { this.listener = listener; }

    public CompletableFuture<Void> connect(String url) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create(url), this).thenAccept(w -> this.ws = w);
    }
    public void send(SignalMessage msg) {
        WebSocket w = ws;
        if (w != null) w.sendText(codec.encode(msg), true);
    }
    public void close() {
        WebSocket w = ws;
        if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }
    @Override public void onOpen(WebSocket webSocket) {
        this.ws = webSocket; webSocket.request(1); listener.onOpen();
    }
    @Override public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            String json = buffer.toString(); buffer.setLength(0);
            try { listener.onMessage(codec.decode(json)); }
            catch (RuntimeException e) { listener.onError(e); }
        }
        webSocket.request(1);
        return null;
    }
    @Override public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        listener.onClose(statusCode, reason); return null;
    }
    @Override public void onError(WebSocket webSocket, Throwable error) { listener.onError(error); }
}
```

- [ ] **Step 4: Компиляция** — `./gradlew :signaling-client:compileJava` → SUCCESS.
- [ ] **Step 5: Commit** — `git commit -am "feat(signaling-client): java.net.http websocket client"`

---

### Task 6: SignalingServer — Javalin WS-обёртка

**Files:**
- Create: `.../signaling/SignalingServer.java`

- [ ] **Step 1: Реализация**

```java
package dev.sertas.signaling;
import dev.sertas.protocol.SignalCodec;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SignalingServer {
    private final SignalingService service = new SignalingService();
    private final SignalCodec codec = new SignalCodec();
    private final Map<String, WsContext> conns = new ConcurrentHashMap<>();
    private Javalin app;

    public void start(int port) {
        app = Javalin.create().ws("/signal", ws -> {
            ws.onConnect(ctx -> { ctx.enableAutomaticPings(); conns.put(ctx.sessionId(), ctx); });
            ws.onMessage(ctx -> dispatch(service.onMessage(ctx.sessionId(), codec.decode(ctx.message()))));
            ws.onClose(ctx -> { dispatch(service.onDisconnect(ctx.sessionId())); conns.remove(ctx.sessionId()); });
        }).start(port);
    }
    private void dispatch(java.util.List<Outbound> outs) {
        for (Outbound o : outs) {
            WsContext c = conns.get(o.recipientId());
            if (c != null && c.session.isOpen()) c.send(codec.encode(o.message()));
        }
    }
    public int port() { return app.port(); }
    public void stop() { if (app != null) app.stop(); }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new SignalingServer().start(port);
        System.out.println("signaling on ws://localhost:" + port + "/signal");
    }
}
```
Примечание: `ctx.sessionId()` (Javalin) — стабильный id на соединение = id участника.

- [ ] **Step 2: Компиляция** — `./gradlew :signaling-server:compileJava` → SUCCESS.
- [ ] **Step 3: Commit** — `git commit -am "feat(signaling): javalin websocket server"`

---

### Task 7: Сквозной интеграционный тест сигналинга

**Files:**
- Test: `.../signaling/SignalingEndToEndTest.java`

- [ ] **Step 1: Тест — реальный сервер + 2 клиента**

```java
package dev.sertas.signaling;
import dev.sertas.protocol.SignalMessage;
import dev.sertas.protocol.SignalMessage.*;
import dev.sertas.signaling.client.SignalingClient;
import dev.sertas.signaling.client.SignalingListener;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SignalingEndToEndTest {
    static SignalingServer server; static int port;
    @BeforeAll static void up() { server = new SignalingServer(); server.start(0); port = server.port(); }
    @AfterAll static void down() { server.stop(); }

    static final class Collector implements SignalingListener {
        final BlockingQueue<SignalMessage> q = new LinkedBlockingQueue<>();
        public void onMessage(SignalMessage m) { q.add(m); }
        SignalMessage take() throws InterruptedException { return q.poll(5, TimeUnit.SECONDS); }
    }

    @Test void twoPeersJoinAndRelayOffer() throws Exception {
        String url = "ws://localhost:" + port + "/signal";
        var ca = new Collector(); var cb = new Collector();
        var alice = new SignalingClient(ca); var bob = new SignalingClient(cb);
        alice.connect(url).get(5, TimeUnit.SECONDS);
        alice.send(new Join("ROOM", "Alice"));
        SignalMessage aState = ca.take();
        assertInstanceOf(RoomState.class, aState);

        bob.connect(url).get(5, TimeUnit.SECONDS);
        bob.send(new Join("ROOM", "Bob"));
        // Alice должна получить peer-joined(Bob)
        SignalMessage joined = ca.take();
        assertInstanceOf(PeerJoined.class, joined);
        assertEquals("Bob", ((PeerJoined) joined).name());

        // Bob шлёт offer Алисе (по её id из room-state)
        String bobState = ((RoomState) cb.take()).peers().get(0).id();
        bob.send(new Offer(bobState, "SDP-DATA"));
        SignalMessage offer = ca.take();
        assertInstanceOf(Offer.class, offer);
        assertEquals("SDP-DATA", ((Offer) offer).sdp());

        alice.close(); bob.close();
    }
}
```

- [ ] **Step 2: Run → PASS** (поднимает Jetty на эфемерном порту).

Run: `./gradlew :signaling-server:test`
Expected: BUILD SUCCESSFUL, тест проходит.

- [ ] **Step 3: Commit** — `git commit -am "test(signaling): end-to-end two-peer relay over websocket"`

---

### Task 8: OpusSdpMunger (`:media`)

**Files:**
- Create: `media/build.gradle.kts`, `.../media/OpusSdpMunger.java`
- Test: `.../media/OpusSdpMungerTest.java`

- [ ] **Step 1: build.gradle.kts** — пусто кроме общего (нет зависимостей).

```kotlin
// нет внешних зависимостей; только JUnit из root
```

- [ ] **Step 2: Падающий тест**

```java
package dev.sertas.media;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpusSdpMungerTest {
    static final String SDP = String.join("\r\n",
        "v=0",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "a=rtpmap:111 opus/48000/2",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "") ;

    @Test void appliesStereoHighBitrateToOpusFmtp() {
        String out = OpusSdpMunger.applyMusicProfile(SDP);
        String fmtp = out.lines().filter(l -> l.startsWith("a=fmtp:111")).findFirst().orElseThrow();
        assertTrue(fmtp.contains("stereo=1"));
        assertTrue(fmtp.contains("sprop-stereo=1"));
        assertTrue(fmtp.contains("maxaveragebitrate=192000"));
        assertTrue(fmtp.contains("usedtx=0"));
        assertTrue(fmtp.contains("useinbandfec=1"));
    }
    @Test void addsFmtpWhenMissing() {
        String noFmtp = "v=0\r\nm=audio 9 RTP 111\r\na=rtpmap:111 opus/48000/2\r\n";
        String out = OpusSdpMunger.applyMusicProfile(noFmtp);
        assertTrue(out.lines().anyMatch(l -> l.startsWith("a=fmtp:111") && l.contains("stereo=1")));
    }
    @Test void noOpusIsUnchanged() {
        String pcmu = "v=0\r\nm=audio 9 RTP 0\r\na=rtpmap:0 PCMU/8000\r\n";
        assertEquals(pcmu, OpusSdpMunger.applyMusicProfile(pcmu));
    }
}
```

- [ ] **Step 3: Run → FAIL.**

- [ ] **Step 4: Реализация**

```java
package dev.sertas.media;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Переписывает fmtp-строку Opus под «музыкальный» профиль (стерео, высокий битрейт, без DTX). */
public final class OpusSdpMunger {
    private OpusSdpMunger() {}
    private static final Map<String,String> MUSIC = new LinkedHashMap<>();
    static {
        MUSIC.put("minptime","10");
        MUSIC.put("useinbandfec","1");
        MUSIC.put("stereo","1");
        MUSIC.put("sprop-stereo","1");
        MUSIC.put("maxaveragebitrate","192000");
        MUSIC.put("usedtx","0");
        MUSIC.put("cbr","0");
    }
    private static final Pattern RTPMAP = Pattern.compile("(?m)^a=rtpmap:(\\d+)\\s+opus/48000/2");

    public static String applyMusicProfile(String sdp) {
        Matcher m = RTPMAP.matcher(sdp);
        if (!m.find()) return sdp;
        String pt = m.group(1);
        String eol = sdp.contains("\r\n") ? "\r\n" : "\n";
        Pattern fmtp = Pattern.compile("(?m)^a=fmtp:" + pt + " (.*)$");
        Matcher fm = fmtp.matcher(sdp);
        if (fm.find()) {
            Map<String,String> params = parse(fm.group(1));
            params.putAll(MUSIC);
            return new StringBuilder(sdp).replace(fm.start(), fm.end(),
                "a=fmtp:" + pt + " " + render(params)).toString();
        }
        // добавить fmtp сразу после rtpmap-строки
        int insert = sdp.indexOf(eol, m.end());
        String line = eol + "a=fmtp:" + pt + " " + render(new LinkedHashMap<>(MUSIC));
        return insert < 0 ? sdp + line : sdp.substring(0, insert) + line + sdp.substring(insert);
    }
    private static Map<String,String> parse(String s) {
        Map<String,String> p = new LinkedHashMap<>();
        for (String kv : s.split(";")) {
            String t = kv.trim(); if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq < 0) p.put(t, ""); else p.put(t.substring(0,eq), t.substring(eq+1));
        }
        return p;
    }
    private static String render(Map<String,String> p) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String,String> e : p.entrySet()) {
            if (b.length() > 0) b.append(';');
            b.append(e.getKey());
            if (!e.getValue().isEmpty()) b.append('=').append(e.getValue());
        }
        return b.toString();
    }
}
```

- [ ] **Step 5: Run → PASS.**
- [ ] **Step 6: Commit** — `git commit -am "feat(media): opus music-profile sdp munger"`

---

### Task 9: AudioFormatConverter (`:media`)

**Files:**
- Create: `.../media/AudioFormatConverter.java`
- Test: `.../media/AudioFormatConverterTest.java`

- [ ] **Step 1: Падающий тест**

```java
package dev.sertas.media;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioFormatConverterTest {
    @Test void interleavesAndScalesToS16LE() {
        float[] left = { 0f, 1f };
        float[] right = { -1f, 0.5f };
        byte[] out = AudioFormatConverter.float32PlanarToS16Interleaved(left, right);
        // 2 кадра * 2 канала * 2 байта = 8 байт
        assertEquals(8, out.length);
        // кадр0: L=0 → 0x0000 ; R=-1 → -32768 = 0x8000 (LE: 00 80)
        assertEquals(0, le16(out, 0));
        assertEquals((short) -32768, le16(out, 2));
        // кадр1: L=1 → 32767 ; R=0.5 → 16383
        assertEquals((short) 32767, le16(out, 4));
        assertEquals((short) 16383, le16(out, 6));
    }
    @Test void clampsOutOfRange() {
        byte[] out = AudioFormatConverter.float32PlanarToS16Interleaved(new float[]{2f}, new float[]{-2f});
        assertEquals((short)32767, le16(out,0));
        assertEquals((short)-32768, le16(out,2));
    }
    @Test void silenceFrameIsZeroedAndCorrectSize() {
        byte[] s = AudioFormatConverter.silenceFrame(480, 2);
        assertEquals(480*2*2, s.length);
        for (byte b : s) assertEquals(0, b);
    }
    private static short le16(byte[] a, int i) {
        return (short)((a[i] & 0xFF) | (a[i+1] << 8));
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Реализация**

```java
package dev.sertas.media;

/** Конвертация захваченного аудио в формат CustomAudioSource.pushAudio (S16 interleaved, LE). */
public final class AudioFormatConverter {
    private AudioFormatConverter() {}

    public static byte[] float32PlanarToS16Interleaved(float[] left, float[] right) {
        int frames = left.length;
        byte[] out = new byte[frames * 2 * 2];
        int o = 0;
        for (int i = 0; i < frames; i++) {
            o = writeSample(out, o, left[i]);
            o = writeSample(out, o, right[i]);
        }
        return out;
    }
    public static byte[] silenceFrame(int frameCount, int channels) {
        return new byte[frameCount * channels * 2];
    }
    private static int writeSample(byte[] out, int o, float v) {
        int s = Math.round(v * 32767f);
        if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
        out[o] = (byte) (s & 0xFF);
        out[o + 1] = (byte) ((s >> 8) & 0xFF);
        return o + 2;
    }
}
```
Примечание: `-1f → round(-32767) = -32767`? Нет: тест ждёт `-32768`. Уточнение: для `-1f` используем масштаб 32768 на отрицательной стороне. Реализация ниже скорректирована в Step 3-fix.

- [ ] **Step 3-fix: Симметричное масштабирование с правильными границами**

Заменить `writeSample` на:
```java
private static int writeSample(byte[] out, int o, float v) {
    float c = v < -1f ? -1f : (v > 1f ? 1f : v);
    int s = (int) Math.round(c >= 0 ? c * 32767f : c * 32768f);
    if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
    out[o] = (byte) (s & 0xFF);
    out[o + 1] = (byte) ((s >> 8) & 0xFF);
    return o + 2;
}
```

- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `git commit -am "feat(media): float32-planar to s16-interleaved converter"`

---

### Task 10: app-client — JavaFX-скелет с экраном входа

**Files:**
- Create: `app-client/build.gradle.kts`, `.../app/SertasApp.java`, `.../app/ui/JoinView.java`

- [ ] **Step 1: build.gradle.kts** (JavaFX-плагин)

```kotlin
plugins { application; id("org.openjfx.javafxplugin") version "0.1.0" }
javafx { version = "21.0.4"; modules("javafx.controls") }
application { mainClass.set("dev.sertas.app.SertasApp") }
dependencies {
    implementation(project(":protocol"))
    implementation(project(":signaling-client"))
    implementation(project(":media"))
}
```
(Плагин подключается в `pluginManagement` settings — см. Step 1-fix.)

- [ ] **Step 1-fix: pluginManagement в settings.gradle.kts**

Добавить в начало `settings.gradle.kts`:
```kotlin
pluginManagement { repositories { gradlePluginPortal() } }
```

- [ ] **Step 2: SertasApp.java**

```java
package dev.sertas.app;
import dev.sertas.app.ui.JoinView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class SertasApp extends Application {
    @Override public void start(Stage stage) {
        stage.settitle("sertas");
        stage.setScene(new Scene(new JoinView().getRoot(), 420, 280));
        stage.show();
    }
    public static void main(String[] args) { launch(args); }
}
```

- [ ] **Step 3: JoinView.java** (экран входа; подключение к сигналингу появится в Фазе 1)

```java
package dev.sertas.app.ui;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.Parent;

public final class JoinView {
    private final VBox root = new VBox(10);
    private final TextField server = new TextField("ws://localhost:8080/signal");
    private final TextField room = new TextField();
    private final TextField name = new TextField();
    private final Button join = new Button("Войти в комнату");

    public JoinView() {
        root.setPadding(new Insets(20));
        room.setPromptText("Код комнаты");
        name.setPromptText("Ваше имя");
        root.getChildren().addAll(new Label("Сервер"), server,
            new Label("Комната"), room, new Label("Имя"), name, join);
    }
    public Parent getRoot() { return root; }
    public Button joinButton() { return join; }
    public String serverUrl() { return server.getText().trim(); }
    public String roomCode() { return room.getText().trim(); }
    public String displayName() { return name.getText().trim(); }
}
```

- [ ] **Step 4: Компиляция** — `./gradlew :app-client:compileJava` → SUCCESS (GUI не запускаем в headless).
- [ ] **Step 5: Полная сборка** — `./gradlew build` → BUILD SUCCESSFUL (все тесты зелёные).
- [ ] **Step 6: Commit** — `git commit -am "feat(app): javafx shell with join screen"`

---

## Self-Review

**Spec coverage (срез Фазы 0):**
- §9 Протокол сигналинга → Tasks 2,4,6,7
- §6 SDP-munging «музыкальный профиль» → Task 8
- §7 конвертация аудио Float32→S16 + тишина → Task 9
- §12 тесты (юнит протокола/роутера/SDP/аудио + интеграция loopback) → Tasks 2,4,7,8,9
- §3 стек/модули (Gradle multi-module, без FFmpeg) → Task 1
- Медиа-движок webrtc-java, захват, рендер, нативный звук → **Фаза 1+** (вне Фазы 0 намеренно).

**Placeholder scan:** код приведён полностью; в Task 9 намеренно показан баг и его фикс (Step 3-fix) — при исполнении сразу применять исправленную версию `writeSample`.

**Type consistency:** `SignalMessage.*`, `Peer(id,name)`, `Outbound(recipientId,message)`, `SignalingService.onMessage/onDisconnect`, `RoomRegistry.peersInRoom(room,excludeId)` — согласованы между задачами 2–7. `SignalingClient(SignalingListener)` / `connect(url)` / `send(msg)` совпадают между задачами 5 и 7.

---

## Следующие фазы (отдельные планы)

- **Фаза 1** — webrtc-java интеграция: `PeerConnectionFactory`, меш `RTCPeerConnection` на пира, микрофон + камера, рендер `PixelBuffer`, mute/deafen/громкость, выбор устройств. (Здесь же — проверка точного классификатора нативной зависимости.)
- **Фаза 2** — демонстрация экрана (`VideoDesktopSource`) + пресеты качества.
- **Фаза 3** — нативный захват системного звука (ScreenCaptureKit / WASAPI) → `CustomAudioSource`.
- **Фаза 4** — тумблеры NS/AEC/AGC, дефолты чистого звука, soak/leak-тесты, jpackage.
