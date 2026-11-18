# Monitoramento da homologação

Prometheus e Grafana em Docker na **vm-apps** (`/data/tools/monitoramento`), coletando o
k3s-1. O Grafana é publicado pelo Traefik da vm-infra em **http://grafana.casa.lan**.

```
Prometheus (vm-apps) --> 192.168.5.120:9101   métricas do worker   (Service rtc-worker-metricas-lan)
                     --> 192.168.5.120:15692  métricas do RabbitMQ (Service rtc-rabbit-metricas-lan)
                     --> 192.168.5.120:9100   node-exporter do k3s-1 (pacote prometheus-node-exporter)
Grafana   (vm-apps :3000) <-- Traefik (vm-infra) <-- grafana.casa.lan
```

Os Services de `k8s/ambientes/enxuto/metricas-lan.yaml` só aceitam a origem 192.168.5.50.
Se o Prometheus mudar de máquina, ajuste `loadBalancerSourceRanges` lá.

## Instalação

```bash
cd /data/tools/monitoramento
cp .env.exemplo .env && chmod 600 .env   # troque a senha
mkdir -p dados/prometheus dados/grafana && sudo chown 65534:65534 dados/prometheus && sudo chown 472:472 dados/grafana
docker compose up -d
```

No k3s-1:

```bash
sudo apt-get install -y prometheus-node-exporter
```

Na vm-infra, copie `docs/traefik/grafana.yml` para o diretório do provider file do Traefik.

## Uso

- **Grafana:** http://grafana.casa.lan, usuário `admin`, senha em `/data/tools/monitoramento/.env`.
  O dashboard **RTC – Calculadora (homologação)** abre como página inicial.
- **Prometheus:** só local na vm-apps (`127.0.0.1:9090`). Para a interface:

```bash
ssh -L 9090:localhost:9090 rasilfe@192.168.5.50
```

Depois abra http://localhost:9090 (alvos em *Status → Targets*, alertas em *Alerts*).

- **Recarregar** regras ou alvos depois de editar `prometheus/`:

```bash
curl -X POST http://localhost:9090/-/reload
```

## Alertas (`prometheus/regras.yml`)

| Alerta | Quando |
|---|---|
| `RtcWorkerFora` | worker sem responder às coletas por 2 min |
| `RtcFilaSemConsumidores` | fila de entrada de notas ou de base de cálculo sem consumidores por 2 min |
| `RtcMensagensNaDlq` | qualquer mensagem numa DLQ |
| `RtcFilaEntradaAcumulando` | mais de 1.000 notas esperando por 10 min |
| `RtcErroTecnicoNoCalculo` | nota devolvida à fila por erro técnico |
| `RtcRedisIndisponivel` | idempotência funcionando sem Redis |
| `RtcRabbitAlarmeMemoria` | RabbitMQ bloqueando publicadores por memória |
| `RtcEmpresaNaoConsome` | mais de 5.000 resultados não lidos por 30 min numa fila de empresa (notas ou base) |
| `RtcNoK3sMemoria` | k3s-1 com menos de 10% de memória livre |

Não há Alertmanager: os alertas aparecem no Grafana (*Alerting → Alert rules*, fonte
Prometheus) e no Prometheus, mas não enviam notificação. Para receber avisos, configure um
*contact point* no Grafana ou acrescente um Alertmanager.

## Retenção

30 dias ou 5 GB, o que vier primeiro (`/data` fica no disco de dados do host).
