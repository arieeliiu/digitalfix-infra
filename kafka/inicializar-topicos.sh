#!/usr/bin/env bash
set -euo pipefail

B=kafka1:29092,kafka2:29092,kafka3:29092
# Espera acotada: el clúster debe registrar los tres brokers antes de crear RF=3.
cub kafka-ready -b "$B" 3 120

configurar() {
    local topico=$1 retencion=$2 descripcion
    kafka-topics --bootstrap-server "$B" --create --if-not-exists --topic "$topico" \
        --partitions 3 --replication-factor 3 \
        --config cleanup.policy=delete --config retention.ms="$retencion" \
        --config min.insync.replicas=2

    descripcion=$(kafka-topics --bootstrap-server "$B" --describe --topic "$topico")
    if ! grep -Eq 'PartitionCount: *3[[:space:]]+ReplicationFactor: *3([[:space:]]|$)' <<< "$descripcion"; then
        echo "ERROR: $topico no tiene 3 particiones y RF=3; requiere revisión, no se recreará." >&2
        exit 1
    fi
    # --if-not-exists no actualiza propiedades. Alter conserva mensajes y asignaciones.
    kafka-configs --bootstrap-server "$B" --entity-type topics --entity-name "$topico" \
        --alter --add-config "cleanup.policy=delete,retention.ms=$retencion,min.insync.replicas=2"
    printf '%s\n' "$descripcion"
}

configurar workorders.events 604800000
# La timeline conserva todas las ediciones; compactar por ordenId borraría las anteriores.
configurar audit.timeline 2592000000
configurar workorders.events.audit.DLT 1209600000
configurar audit.timeline.audit.DLT 1209600000
# Recurso histórico reservado: no se elimina ni se atribuye un consumidor inexistente.
configurar workorders.events.report.DLT 1209600000
echo "Tópicos reconciliados correctamente."
