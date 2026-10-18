"""Inspección de solo lectura: Compose y, opcionalmente, broker local. No imprime secretos."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parent.parent


def comando(*args):
    r = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=45)
    if r.returncode:
        raise RuntimeError("Falló comprobación local; revisar Docker y variables requeridas (salida omitida para proteger secretos)")
    return r.stdout


def comprobar(condicion, mensaje):
    if not condicion:
        raise RuntimeError(mensaje)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', default='apps/.env.example')
    parser.add_argument('--broker', action='store_true')
    parser.add_argument('--exigir-listo', action='store_true', help='Falla si faltan consumidores, permisos o credenciales de despliegue')
    args = parser.parse_args()
    cfg = json.loads(comando('docker', 'compose', '--env-file', args.env_file,
                             '-f', 'docker-compose.yml', 'config', '--format', 'json'))
    servicios = cfg['services']
    comprobar(set(servicios) == {'bff', 'catalog', 'workorders', 'notify', 'report', 'rabbit-admin'}, 'Deben existir seis servicios')
    redes = {'bff': {'digitalfix-backend'}, 'catalog': {'digitalfix-backend'},
             'workorders': {'digitalfix-backend', 'digitalfix-messaging'},
             'notify': {'digitalfix-messaging'}, 'report': {'digitalfix-messaging'}, 'rabbit-admin': {'digitalfix-messaging'}}
    for nombre, esperadas in redes.items():
        comprobar(set(servicios[nombre]['networks']) == esperadas, 'Redes incorrectas: ' + nombre)
    comprobar(cfg['networks']['digitalfix-messaging']['external'], 'Mensajería debe usar red externa')
    usuarios = []
    for nombre in ('workorders', 'notify', 'report', 'rabbit-admin'):
        env = servicios[nombre]['environment']
        comprobar(env['RABBITMQ_ADDRESSES'] == 'rabbitmq1:5672,rabbitmq2:5672' and env['RABBITMQ_VHOST'] == 'digitalfix', 'Broker/vhost incorrecto')
        usuarios.append(env['RABBITMQ_USERNAME'])
    comprobar(len(set(usuarios)) == 4, 'Las cuatro cuentas deben ser independientes')
    comprobar(servicios['workorders']['environment']['DIGITALFIX_RABBIT_ENABLED'] == 'true', 'Mensajería deshabilitada')
    comprobar(servicios['workorders']['environment']['CATALOG_URL'] == 'http://catalog:8081', 'Catalog incorrecto')
    for variable in ('DB_URL', 'DB_USERNAME', 'DB_PASSWORD'):
        comprobar(servicios['workorders']['environment'][variable] == servicios['catalog']['environment'][variable], 'Oracle divergente')
    for nombre in ('notify', 'report'):
        comprobar(not servicios[nombre].get('ports'), 'Consumidor no debe publicar puertos')
        volumen = servicios[nombre]['volumes'][0]
        comprobar(volumen['target'] == '/data' and cfg['volumes'][volumen['source']]['external'], 'Persistencia incorrecta')
    puertos = servicios['rabbit-admin']['ports']
    comprobar(len(puertos) == 1 and puertos[0]['host_ip'] == '127.0.0.1' and puertos[0]['target'] == 8085, 'Rabbit Admin debe quedar privado')
    print('OK: Compose, seis servicios, redes mínimas, cuentas separadas, Oracle/Catalog, mensajería, puertos y volúmenes')
    pendientes = []
    if any('CAMBIAR' in str(v) or 'example.invalid' in str(v) or 'TU_' in str(v)
           for s in servicios.values() for v in s.get('environment', {}).values()):
        pendientes.append('configuración ilustrativa: completar secretos y SMTP antes de arrancar')
    if args.broker:
        mq = json.loads(comando('docker', 'compose', '--env-file', 'mq/.env', '-f', 'mq/compose.yml', 'config', '--format', 'json'))
        env = mq['services']['rabbitmq1']['environment']
        auth = base64.b64encode((env['RABBITMQ_DEFAULT_USER'] + ':' + env['RABBITMQ_DEFAULT_PASS']).encode()).decode()

        def consultar(ruta):
            req = urllib.request.Request('http://127.0.0.1:15672/api/' + ruta, headers={'Authorization': 'Basic ' + auth})
            with urllib.request.urlopen(req, timeout=15) as respuesta:
                return json.load(respuesta)

        nodos = consultar('nodes')
        comprobar(len(nodos) == 2 and all(n['running'] and not n.get('partitions') for n in nodos), 'Clúster incompleto o particionado')
        exchanges = {e['name']: e for e in consultar('exchanges/digitalfix')}
        for nombre, tipo in [('cmd.direct', 'direct'), ('cmd.topic', 'topic'), ('cmd.dead.dlx', 'direct')]:
            comprobar(exchanges.get(nombre, {}).get('type') == tipo and exchanges[nombre]['durable'], 'Exchange incorrecto: ' + nombre)
        colas = {q['name']: q for q in consultar('queues/digitalfix')}
        bindings = {(b['source'], b['destination'], b['routing_key']) for b in consultar('bindings/digitalfix')}
        for tipo, directa, topic in [('email', 'email.send', 'email.*'), ('dispatch', 'dispatch.ticket', 'dispatch.#'), ('report', 'report.gen', 'report.*')]:
            nombre = 'q.cmd.' + tipo
            for cola in (nombre, nombre + '.dlq'):
                comprobar(cola in colas and colas[cola]['durable'] and colas[cola]['type'] == 'classic', 'Cola incorrecta: ' + cola)
            argumentos = colas[nombre]['arguments']
            comprobar(argumentos.get('x-dead-letter-exchange') == 'cmd.dead.dlx' and argumentos.get('x-dead-letter-routing-key') == tipo + '.dead', 'DLQ incorrecta')
            for enlace in [('cmd.direct', nombre, directa), ('cmd.topic', nombre, topic), ('cmd.dead.dlx', nombre + '.dlq', tipo + '.dead')]:
                comprobar(enlace in bindings, 'Falta binding: ' + str(enlace))
            comprobar(colas[nombre]['consumers'] <= 1, 'Consumidores duplicados: ' + nombre)
            if colas[nombre]['consumers'] == 0:
                pendientes.append('consumidor ausente: ' + nombre)
        for nombre in ('notify-data', 'report-data'):
            volumen = cfg['volumes'][nombre]['name']
            comando('docker', 'volume', 'inspect', volumen, '--format', '{{.Name}}')
            activos = comando('docker', 'ps', '--filter', 'volume=' + volumen, '--format', '{{.ID}}').split()
            comprobar(len(activos) <= 1, 'Volumen compartido por múltiples contenedores activos: ' + nombre)
        permisos = {p['user']: p for p in consultar('vhosts/digitalfix/permissions')}
        esperados = [
            (r'^$', r'^cmd\.(direct|topic)$', r'^$'),
            (r'^(cmd\.(direct|topic|dead\.dlx)|q\.cmd\.(email|dispatch)(\.dlq)?)$', r'^(cmd\.dead\.dlx|q\.cmd\.(email|dispatch)(\.dlq)?)$', r'^(cmd\.(direct|topic|dead\.dlx)|q\.cmd\.(email|dispatch))$'),
            (r'^(cmd\.(direct|topic|dead\.dlx)|q\.cmd\.report(\.dlq)?)$', r'^(cmd\.dead\.dlx|q\.cmd\.report(\.dlq)?)$', r'^(cmd\.(direct|topic|dead\.dlx)|q\.cmd\.report(\.dlq)?)$'),
            (r'^(?!(amq\.|rabbitmq\.|cmd\.|q\.cmd\.))[A-Za-z0-9][A-Za-z0-9._-]*$',) * 3,
        ]
        for usuario, esperado in zip(usuarios, esperados):
            actual = permisos.get(usuario, {})
            if tuple(actual.get(k) for k in ('configure', 'write', 'read')) != esperado:
                pendientes.append('crear/verificar permisos de cuenta: ' + usuario)
        print('OK: dos nodos, tres exchanges, seis colas classic durables, nueve bindings, DLQ y volúmenes existentes; sin duplicados')
        print('Mensajes actuales:', sum(q.get('messages', 0) for q in colas.values()))
    if args.exigir_listo and not args.broker:
        pendientes.append('--exigir-listo requiere --broker')
    for pendiente in pendientes:
        print('PENDIENTE:', pendiente)
    if args.exigir_listo and pendientes:
        return 1
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except urllib.error.HTTPError as error:
        print('ERROR: Management API respondió HTTP', error.code, '(revisar disponibilidad y credenciales de mq/.env)')
        raise SystemExit(1)
    except Exception as error:
        # No volcar errores HTTP ni configuraciones que puedan contener secretos.
        print('ERROR:', str(error) if isinstance(error, RuntimeError) else type(error).__name__)
        raise SystemExit(1)
