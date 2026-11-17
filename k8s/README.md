# Implantação no k3s

Pipeline orientado a eventos: **produtor → `rtc.calculo.in` → `rtc.calculo.entrada` →
worker → `rtc.calculo.out` → `rtc.calculo.saida` → consumidor**. Mensagens ilegíveis
ou que falham repetidamente vão para `rtc.dlx` → `*.dlq`.

## Pré-requisitos no cluster

- RabbitMQ Cluster Operator e Messaging Topology Operator
- KEDA 2.x
- StorageClass `local-path` (padrão do k3s) para o RabbitMQ
- PostgreSQL acessível pelo cluster (gerenciado ou operador de sua escolha)
- Opcional: coletor OTLP/Jaeger em `jaeger-collector.observability.svc:4318` e Prometheus

## Ordem de implantação

```bash
# 1. imagem (mesma para worker e migração)
docker build -t registry.local/calculadora-rtc-worker:1.6.1 .
docker push registry.local/calculadora-rtc-worker:1.6.1

# 2. banco: crie o database da versão da base e os dois secrets
kubectl create namespace rtc
kubectl -n rtc create secret generic rtc-db-migracao \
  --from-literal=host=<host> --from-literal=name=calculadora_pro_v0059 \
  --from-literal=user=<usuario_escrita> --from-literal=password=<senha>
kubectl -n rtc apply -f k8s/migracao/job.yaml
kubectl -n rtc wait --for=condition=complete job/rtc-migracao-base --timeout=10m
kubectl -n rtc create secret generic rtc-db \
  --from-literal=host=<host> --from-literal=name=calculadora_pro_v0059 \
  --from-literal=user=<usuario_leitura> --from-literal=password=<senha>

# 3. RabbitMQ, topologia, Redis, worker e KEDA
kubectl apply -k k8s/
```

O Topology Operator cria os secrets `rtc-worker-user-credentials`,
`rtc-produtor-user-credentials`, `rtc-consumidor-user-credentials` e
`rtc-keda-user-credentials`. Entregue os do produtor e do consumidor aos sistemas externos.

## Contrato das mensagens

**Entrada** (exchange `rtc.calculo.in`, routing key `nota`, `delivery_mode=2`, publisher confirms):

```json
{
  "schemaVersion": "1",
  "correlationId": "7f3c2a7e-1b8e-4c0e-9d7a-1f2b3c4d5e6f",
  "idNota": "35251112345678000190550010000012341000012345",
  "loteId": "rodada-2026-11-17-01",
  "produzidoEm": "2026-11-17T10:00:00-03:00",
  "payload": { "...": "mesmo JSON do antigo POST /calculadora/regime-geral" }
}
```

Datas com precisão de segundos (sem fração). Reenviar o mesmo `correlationId` com
conteúdo diferente manda a mensagem para a DLQ.

**Saída** (exchange `rtc.calculo.out`, routing key `resultado`): `ResultadoNota`, com
`status` (`SUCESSO`, `PARCIAL`, `ERRO`), `versaoBase`, `errosNota` (erro da nota inteira)
e `resultado.objetos[]` com `estadoItem` e `erros` por item. Cabeçalhos: `message_id` e
`correlation_id` = correlationId, `x-id-nota`, `x-status`, `x-versao-base`.

A entrega é **pelo menos uma vez**: o consumidor deve gravar com chave única
(`idNota`, `correlationId`).

## Garantias do worker

| Situação | Ação |
|---|---|
| Cálculo concluído | publica, espera a confirmação do broker, marca concluída no Redis e só então dá `ack` |
| Erro de negócio (nota ou item) | faz parte do resultado publicado; não é falha |
| JSON ilegível, sem `correlationId`, ou id reutilizado com outro conteúdo | `reject` sem requeue → DLQ |
| Falha ao publicar, erro técnico (`CAL-999`) ou banco indisponível | `nack` com requeue; após 5 entregas → DLQ |
| Nota já concluída (reenvio) | `ack` sem recalcular |
| Worker caiu no meio | mensagem sem `ack` volta; o próximo worker assume o lease |
| Redis indisponível | processa assim mesmo (o cálculo é determinístico) e incrementa `rtc.worker.idempotencia.indisponivel` |

## Observabilidade

- Logs JSON (ECS) com `correlationId` e `idNota` no MDC.
- Tracing OTLP: o `traceparent` segue do produtor ao worker e à mensagem de saída.
- Métricas em `:9101/metrics`:
  - `rtc_worker_notas_total{resultado,status}`: publicadas, duplicadas, rejeitadas e devolvidas;
  - `rtc_worker_nota_duracao_seconds`: cálculo + publicação;
  - `cache_gets_total{result}`: acertos do Caffeine;
  - profundidade das filas: plugin `rabbitmq_prometheus` do cluster (porta 15692).
- Alertas sugeridos: DLQ com mais de 0 mensagens; fila de entrada crescendo por mais de 5 min;
  `rtc_worker_idempotencia_indisponivel_total` crescendo.

## Reprocessar a DLQ

Corrija a causa e mova as mensagens de volta, por exemplo com a shovel dinâmica do
RabbitMQ, de `rtc.calculo.entrada.dlq` para a exchange `rtc.calculo.in` com routing key `nota`.
