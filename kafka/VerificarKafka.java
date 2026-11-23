import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.*;

// Prueba real: verifica metadatos y usa un tópico temporal, nunca las colas ni datos de negocio.
public class VerificarKafka {
    public static void main(String[] args) throws Exception {
        var props = new Properties();
        props.put("bootstrap.servers", args.length == 0 ? "localhost:9092,localhost:9093,localhost:9094" : args[0]);
        props.put("request.timeout.ms", "10000");
        props.put("default.api.timeout.ms", "30000");
        String topic = "digitalfix-verificacion-" + UUID.randomUUID();
        String group = topic + "-lector";
        boolean creado = false;
        try (var admin = Admin.create(props)) {
            exigir(admin.describeCluster().nodes().get(30, TimeUnit.SECONDS).size() == 3, "Tres brokers registrados");
            var retenciones = Map.of("workorders.events", "604800000", "audit.timeline", "2592000000",
                    "workorders.events.audit.DLT", "1209600000", "audit.timeline.audit.DLT", "1209600000",
                    "workorders.events.report.DLT", "1209600000");
            var topicos = admin.describeTopics(retenciones.keySet()).allTopicNames().get(30, TimeUnit.SECONDS);
            for (var entry : retenciones.entrySet()) {
                var descripcion = topicos.get(entry.getKey());
                exigir(descripcion.partitions().size() == 3, entry.getKey() + ": 3 particiones");
                for (var p : descripcion.partitions()) {
                    exigir(p.replicas().size() == 3 && p.isr().size() == 3, entry.getKey() + ": RF=3 e ISR=3");
                }
                var recurso = new ConfigResource(ConfigResource.Type.TOPIC, entry.getKey());
                var config = admin.describeConfigs(List.of(recurso)).all().get(30, TimeUnit.SECONDS).get(recurso);
                exigir(config.get("cleanup.policy").value().equals("delete"), entry.getKey() + ": delete");
                exigir(config.get("retention.ms").value().equals(entry.getValue()), entry.getKey() + ": retención");
                exigir(config.get("min.insync.replicas").value().equals("2"), entry.getKey() + ": ISR mínimo 2");
                System.out.println("OK configuración: " + entry.getKey());
            }
            try {
                admin.createTopics(List.of(new NewTopic(topic, 3, (short) 3).configs(Map.of(
                        "cleanup.policy", "delete", "retention.ms", "3600000", "min.insync.replicas", "2"))))
                        .all().get(30, TimeUnit.SECONDS);
                creado = true;
                props.put("key.serializer", StringSerializer.class.getName());
                props.put("value.serializer", StringSerializer.class.getName());
                props.put("acks", "all");
                props.put("max.block.ms", "10000");
                List<Long> offsets = new ArrayList<>();
                Integer particion = null;
                try (var producer = new KafkaProducer<String, String>(props)) {
                    for (int i = 0; i < 3; i++) {
                        var meta = producer.send(new ProducerRecord<>(topic, "orden-prueba", "evento-" + i))
                                .get(30, TimeUnit.SECONDS);
                        if (particion == null) particion = meta.partition();
                        exigir(particion == meta.partition(), "Misma key en la misma partición");
                        offsets.add(meta.offset());
                    }
                }
                props.put("key.deserializer", StringDeserializer.class.getName());
                props.put("value.deserializer", StringDeserializer.class.getName());
                props.put("group.id", group);
                props.put("auto.offset.reset", "earliest");
                props.put("enable.auto.commit", "false");
                List<String> leidos = new ArrayList<>();
                try (var consumer = new KafkaConsumer<String, String>(props)) {
                    consumer.subscribe(List.of(topic));
                    long limite = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                    while (leidos.size() < 3 && System.nanoTime() < limite) {
                        for (var r : consumer.poll(Duration.ofSeconds(1))) {
                            exigir(r.key().equals("orden-prueba"), "Key preservada");
                            leidos.add(r.value());
                        }
                    }
                    exigir(leidos.equals(List.of("evento-0", "evento-1", "evento-2")), "Recepción y orden de los tres mensajes");
                    consumer.commitSync();
                }
                var posiciones = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                        .get(30, TimeUnit.SECONDS);
                exigir(posiciones.values().stream().anyMatch(p -> p.offset() == offsets.get(2) + 1), "Offset confirmado tras consumir");
                System.out.println("OK producción/consumo: 3 mensajes en orden, partición=" + particion + ", offsets=" + offsets);
            } finally {
                // Solo elimina el tópico aleatorio creado por esta ejecución; conserva todos los del caso.
                if (creado) {
                    admin.deleteTopics(List.of(topic)).all().get(30, TimeUnit.SECONDS);
                    try { admin.deleteConsumerGroups(List.of(group)).all().get(30, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.ExecutionException e) {
                        if (!(e.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException)) throw e;
                    }
                    System.out.println("OK limpieza de recursos temporales");
                }
            }
        }
    }

    private static void exigir(boolean condicion, String mensaje) {
        if (!condicion) throw new IllegalStateException(mensaje);
    }
}
