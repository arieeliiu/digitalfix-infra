"""Checks against REAL RabbitMQ. --restart also restarts/recreates MQ containers.

Run from any directory with Python 3.10+ and Docker Compose v2 available.
Credentials are read from Compose in memory, never printed or passed on a CLI.
Only uniquely named test queues created by this invocation are removed.
"""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ["docker", "compose", "--env-file", str(ROOT / "mq/.env"),
           "-f", str(ROOT / "mq/compose.yml")]
NODES = {"rabbit@rabbitmq1", "rabbit@rabbitmq2"}


def run(*args):
    result = subprocess.run([*COMPOSE, *args], cwd=ROOT, text=True,
                            capture_output=True, timeout=420)
    if result.returncode:
        # Never echo Compose config or environment contents in an exception.
        raise RuntimeError(f"Docker command failed ({args[0]}), exit {result.returncode}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restart", action="store_true",
                        help="Restart and recreate only this MQ Compose; preserve volumes")
    args = parser.parse_args()
    config = json.loads(run("config", "--format", "json"))
    env = config["services"]["rabbitmq1"]["environment"]
    auth = base64.b64encode(
        f"{env['RABBITMQ_DEFAULT_USER']}:{env['RABBITMQ_DEFAULT_PASS']}".encode()
    ).decode()

    def api(method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(
            "http://127.0.0.1:15672/api/" + path, data=data, method=method,
            headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            content = response.read()
            return json.loads(content) if content else None

    def check_cluster():
        for service in ("rabbitmq1", "rabbitmq2"):
            status = json.loads(run("exec", "-T", service,
                                    "rabbitmqctl", "cluster_status", "--formatter", "json"))
            if set(status["running_nodes"]) != NODES:
                raise RuntimeError(f"Incomplete cluster as seen by {service}")
            if status.get("partitions"):
                raise RuntimeError(f"Network partition as seen by {service}")
            run("exec", "-T", service, "rabbitmq-diagnostics", "-q", "check_port_connectivity")
            peer = "rabbitmq2" if service == "rabbitmq1" else "rabbitmq1"
            run("exec", "-T", service, "bash", "-ec", f"exec 3<>/dev/tcp/{peer}/5672")
        nodes = api("GET", "nodes")
        if {n["name"] for n in nodes if n["running"]} != NODES:
            raise RuntimeError("Management API does not report both running nodes")
        overview = api("GET", "overview")
        if overview["rabbitmq_version"] != "4.2.9":
            raise RuntimeError("Unexpected RabbitMQ version")
        with urllib.request.urlopen("http://127.0.0.1:15672/", timeout=10) as response:
            if response.status != 200:
                raise RuntimeError("Management UI unavailable")
        print("PASS: both nodes, cluster membership, internal listeners, Management API/UI, 4.2.9")

    run("up", "-d", "--wait", "--wait-timeout", "300")
    check_cluster()
    suffix = uuid.uuid4().hex
    created = []
    try:
        for index, node in enumerate(sorted(NODES), start=1):
            queue = f"q.infra.verify.{suffix}.{index}"
            api("PUT", f"queues/digitalfix/{queue}", {
                "durable": True, "auto_delete": False, "node": node,
                "arguments": {"x-queue-type": "classic"}})
            created.append(queue)
            details = api("GET", f"queues/digitalfix/{queue}")
            if details["node"] != node or not details["durable"] or details["type"] != "classic":
                raise RuntimeError("Test queue placement/type/durability mismatch")
            result = api("POST", "exchanges/digitalfix/amq.default/publish", {
                "properties": {"delivery_mode": 2, "message_id": suffix},
                "routing_key": queue, "payload": suffix, "payload_encoding": "string"})
            if not result["routed"]:
                raise RuntimeError("Test message was not routed")
        print("PASS: durable classic queue and persistent test message on EACH node")

        if args.restart:
            # Restart seed first while its peer is alive, then the second node.
            for service in ("rabbitmq1", "rabbitmq2"):
                run("restart", service)
                run("up", "-d", "--wait", "--wait-timeout", "300")
                check_cluster()
            # Both containers replaced; named volumes survive. No health dependency
            # prevents either persisted peer from starting during cluster recovery.
            run("up", "-d", "--force-recreate", "--wait", "--wait-timeout", "300")
            check_cluster()
            print("PASS: sequential restart and full container recreation (volumes retained)")

        for queue in created:
            # Read/ACK only this invocation's synthetic test message, never business data.
            messages = api("POST", f"queues/digitalfix/{queue}/get", {
                "count": 1, "ackmode": "ack_requeue_false", "encoding": "auto"})
            if len(messages) != 1 or messages[0]["payload"] != suffix:
                raise RuntimeError("Test message missing or changed")
            if messages[0]["properties"].get("delivery_mode") != 2:
                raise RuntimeError("Test message not persistent")
        print("PASS: both original payloads recovered" + (" after recreation" if args.restart else ""))
    finally:
        for queue in created:
            api("DELETE", f"queues/digitalfix/{queue}")
        print("Temporary test queues removed; volumes and business topology untouched")
    if not args.restart:
        print("NOT TESTED: restart/persistence across restart (use --restart)")


if __name__ == "__main__":
    main()
