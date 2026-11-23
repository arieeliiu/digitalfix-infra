import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Arranca aplicaciones reales con H2 y un emisor JWT efímero. Nunca carga un .env.
public class VerificarHistorial {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static RSAKey clave;
    static String emisor;

    public static void main(String[] args) throws Exception {
        Path audit = Path.of(args[0]).toAbsolutePath(), bff = Path.of(args[1]).toAbsolutePath();
        Path salida = Path.of(args[2]).toAbsolutePath();
        String brokers = args[3], grupo = "validacion-http-" + UUID.randomUUID();
        for (String broker : brokers.split(","))
            if (!broker.matches("(localhost|127\\.0\\.0\\.1):[0-9]+")) throw new IllegalArgumentException("Solo brokers loopback");
        Files.createDirectories(salida);
        clave = new RSAKeyGenerator(2048).keyID("validacion-local").generate();
        HttpServer jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] publicas = new JWKSet(clave.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks.createContext("/claves", intercambio -> {
            intercambio.getResponseHeaders().set("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, publicas.length);
            try (var out = intercambio.getResponseBody()) { out.write(publicas); }
        });
        jwks.start();
        emisor = "http://127.0.0.1:" + jwks.getAddress().getPort();
        int puertoAudit = puertoLibre(), puertoBff = puertoLibre();
        Process procesoAudit = null, procesoBff = null;
        try (var admin = Admin.create(Map.of("bootstrap.servers", brokers))) {
            procesoAudit = arrancar(audit, "cl.digitalfix.audit.AuditApplication", salida.resolve("audit-http.log"),
                    "--server.address=127.0.0.1", "--server.port=" + puertoAudit,
                    "--spring.datasource.url=jdbc:h2:mem:validacion;DB_CLOSE_DELAY=-1",
                    "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
                    "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=create-drop",
                    "--digitalfix.kafka.enabled=true", "--digitalfix.kafka.bootstrap-servers=" + brokers,
                    "--digitalfix.kafka.group-id=" + grupo);
            esperarEstado("http://127.0.0.1:" + puertoAudit + "/api/audit/ordenes/1/timeline", "Admin", 200);
            procesoBff = arrancar(bff, "cl.digitalfix.bff.DigitalfixMsBffApplication", salida.resolve("bff-http.log"),
                    "--server.address=127.0.0.1", "--server.port=" + puertoBff,
                    "--digitalfix.audit-url=http://127.0.0.1:" + puertoAudit,
                    "--digitalfix.catalog-url=http://127.0.0.1:1", "--digitalfix.workorders-url=http://127.0.0.1:1");
            String url = "http://127.0.0.1:" + puertoBff + "/api/workorders/1/history";
            esperarEstado(url, "Admin", 200);
            esperarLagCero(admin, grupo);
            JsonNode eventos = JSON.readTree(consultar(url, token("Admin", true)).body());
            Set<String> tipos = new HashSet<>(), topicos = new HashSet<>(), ids = new HashSet<>();
            for (var evento : eventos) {
                tipos.add(evento.get("tipo").asText()); topicos.add(evento.get("kafkaTopico").asText());
                exigir(ids.add(evento.get("eventId").asText()), "eventId duplicado");
            }
            exigir(tipos.containsAll(List.of("ORDEN_CREADA", "ORDEN_ESTADO_CAMBIADO", "ORDEN_DATOS_ACTUALIZADOS",
                    "ORDEN_CONTACTOS_ACTUALIZADOS", "ORDEN_REPUESTOS_ACTUALIZADOS")), "Faltan eventos reales de Workorders");
            exigir(topicos.equals(Set.of("workorders.events", "audit.timeline")), "Origen Kafka incorrecto");
            for (String rol : List.of("Admin", "Auditor", "Cliente", "Operador"))
                exigir(consultar(url, token(rol, true)).statusCode() == (List.of("Admin", "Auditor").contains(rol) ? 200 : 403), "Permiso " + rol);
            exigir(consultar(url, null).statusCode() == 401, "Sin token");
            exigir(consultar(url, token("Admin", false)).statusCode() == 403, "Sin scope");
            String jwt = token("Admin", true);
            int firma = jwt.lastIndexOf('.') + 1;
            String invalido = jwt.substring(0, firma) + (jwt.charAt(firma) == 'A' ? 'B' : 'A') + jwt.substring(firma + 1);
            exigir(consultar(url, invalido).statusCode() == 401, "Firma inválida");
            System.out.println("HTTP_REAL OK: " + eventos.size() + " eventos Workorders, dos orígenes, Admin/Auditor 200, Cliente/Operador 403, JWT 401");

            // La reentrega conserva eventId: el consumidor persiste una sola fila.
            long orden = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000000L) + 1000000000L;
            String id = UUID.randomUUID().toString(), fecha = Instant.now().toString();
            String evento = JSON.writeValueAsString(Map.of("schemaVersion", "1.0", "type", "ORDEN_CREADA", "eventId", id,
                    "timestamp", fecha, "traceId", UUID.randomUUID().toString(), "correlationId", Long.toString(orden),
                    "payload", Map.of("ordenId", orden, "accion", "CREACION", "estadoNuevo", "CREADA", "actorId", "validacion-local", "actorRoles", "Admin", "fecha", fecha)));
            try (var productor = new KafkaProducer<String, String>(Map.of("bootstrap.servers", brokers, "acks", "all",
                    "key.serializer", StringSerializer.class.getName(), "value.serializer", StringSerializer.class.getName()))) {
                for (int i = 0; i < 2; i++) {
                    var registro = new ProducerRecord<String, String>("workorders.events", Long.toString(orden), evento);
                    registro.headers().add("correlationId", Long.toString(orden).getBytes(StandardCharsets.UTF_8));
                    productor.send(registro).get(10, TimeUnit.SECONDS);
                }
            }
            esperarLagCero(admin, grupo);
            String urlDuplicado = url.replace("/1/", "/" + orden + "/");
            exigir(JSON.readTree(consultar(urlDuplicado, token("Auditor", true)).body()).size() == 1, "Reentrega duplicó filas");
            System.out.println("HTTP_REAL OK: duplicado una fila y lag cero");
            if (args.length > 4) {
                Path frontend = Path.of(args[4]).toAbsolutePath();
                ProcessBuilder prueba = new ProcessBuilder("node", "node_modules/@angular/cli/bin/ng.js", "test", "--watch=false",
                        "--include=src/app/pages/historial/historial-real.spec.ts").directory(frontend.toFile());
                prueba.environment().put("HISTORIAL_REAL_URL", "http://127.0.0.1:" + puertoBff);
                prueba.environment().put("HISTORIAL_REAL_TOKEN", token("Auditor", true));
                prueba.environment().put("HISTORIAL_REAL_ORDEN", "1");
                var angular = prueba.redirectErrorStream(true).redirectOutput(salida.resolve("angular-real.log").toFile()).start();
                try {
                    exigir(angular.waitFor(120, TimeUnit.SECONDS) && angular.exitValue() == 0, "Vista Angular: revisar angular-real.log");
                } finally { if (angular.isAlive()) angular.destroyForcibly(); }
                System.out.println("HTTP_REAL OK: vista Angular renderizada con HTTP real");
            }
            detener(procesoAudit); procesoAudit = null;
            exigir(consultar(url, token("Admin", true)).statusCode() == 503, "Audit detenido debe responder 503");
            System.out.println("HTTP_REAL OK: indisponibilidad de Audit controlada; H2, sin Oracle/Entra externos");
        } finally {
            detener(procesoBff); detener(procesoAudit); jwks.stop(0);
            try (var admin = Admin.create(Map.of("bootstrap.servers", brokers))) {
                long limite = System.nanoTime() + Duration.ofSeconds(65).toNanos();
                while (true) {
                    try { admin.deleteConsumerGroups(List.of(grupo)).all().get(10, TimeUnit.SECONDS); break; }
                    catch (java.util.concurrent.ExecutionException error) {
                        if (!(error.getCause() instanceof org.apache.kafka.common.errors.GroupNotEmptyException) || System.nanoTime() > limite) throw error;
                        Thread.sleep(1000); // Windows puede terminar el proceso antes de enviar LeaveGroup.
                    }
                }
            }
        }
    }

    static Process arrancar(Path repo, String principal, Path log, String... args) throws Exception {
        String cp = repo.resolve("target/classes") + java.io.File.pathSeparator
                + Files.readString(repo.resolve("target/classpath-prueba.txt")).trim();
        List<String> comando = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", cp, principal));
        comando.addAll(List.of(args));
        comando.addAll(List.of("--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + emisor,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + emisor + "/claves",
                "--spring.security.oauth2.resourceserver.jwt.audiences=validacion-local"));
        return new ProcessBuilder(comando).directory(repo.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    static void esperarLagCero(Admin admin, String grupo) throws Exception {
        long limite = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        int consecutivas = 0;
        while (System.nanoTime() < limite) {
            var offsets = admin.listConsumerGroupOffsets(grupo).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            if (!offsets.isEmpty()) {
                var consultas = new HashMap<TopicPartition, org.apache.kafka.clients.admin.OffsetSpec>();
                for (String nombre : List.of("workorders.events", "audit.timeline"))
                    for (int i = 0; i < 3; i++) consultas.put(new TopicPartition(nombre, i), org.apache.kafka.clients.admin.OffsetSpec.latest());
                var finales = admin.listOffsets(consultas).all().get(5, TimeUnit.SECONDS);
                boolean igual = finales.entrySet().stream().allMatch(e ->
                        (offsets.containsKey(e.getKey()) ? offsets.get(e.getKey()).offset() : 0L) == e.getValue().offset());
                if (igual && ++consecutivas == 3) return;
                if (!igual) consecutivas = 0;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Lag no llegó a cero");
    }
    static void esperarEstado(String url, String rol, int status) throws Exception {
        long limite = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < limite) {
            try { if (consultar(url, token(rol, true)).statusCode() == status) return; } catch (java.io.IOException ignored) {}
            Thread.sleep(300);
        }
        throw new AssertionError("No arrancó: " + url);
    }
    static HttpResponse<String> consultar(String url, String jwt) throws Exception {
        var solicitud = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (jwt != null) solicitud.header("Authorization", "Bearer " + jwt);
        return HTTP.send(solicitud.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static String token(String rol, boolean scope) throws Exception {
        var ahora = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(emisor).audience("validacion-local").subject("validacion-local")
                .claim("oid", "validacion-local").claim("roles", List.of(rol))
                .issueTime(Date.from(ahora)).expirationTime(Date.from(ahora.plusSeconds(600)));
        if (scope) claims.claim("scp", "access_as_user");
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(clave.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(clave));
        return jwt.serialize();
    }
    static int puertoLibre() throws Exception { try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    static void detener(Process proceso) throws Exception {
        if (proceso == null || !proceso.isAlive()) return;
        proceso.destroy(); if (!proceso.waitFor(10, TimeUnit.SECONDS)) proceso.destroyForcibly().waitFor();
    }
    static void exigir(boolean condicion, String mensaje) { if (!condicion) throw new AssertionError(mensaje); }
}
