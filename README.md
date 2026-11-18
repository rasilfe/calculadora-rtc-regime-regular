# Calculadora de Tributos — worker de filas

## Descrição do Projeto

Calcula a CBS, o IBS e o Imposto Seletivo (Reforma Tributária do Consumo — LC 214/2025) nas
operações do regime geral, em um **pipeline orientado a eventos** para alto volume e
**multiempresa**:

```
empresa --nota.<CNPJ>--> RabbitMQ (rtc.calculo.entrada) --> worker de cálculo
worker --resultado.<CNPJ>--> RabbitMQ (rtc.calculo.saida.<CNPJ>) --> empresa
```

- Uma mensagem = uma nota fiscal completa. Cada item é calculado de forma isolada: erro em um
  item não interrompe os demais (status da nota `SUCESSO`, `PARCIAL` ou `ERRO`).
- As regras de cálculo são as do projeto original da Receita Federal, sem alteração; a API HTTP
  foi removida e o cálculo é exposto pelo worker.
- A base de regras fica em **PostgreSQL**, com cache em memória (Caffeine).
- Escala horizontalmente no k3s com **KEDA**, pela profundidade da fila de entrada.

Referência de vazão (uma JVM, 16 CPUs, 8 consumidores): **8.000 notas / 70.000 itens em ~3 s**
(`CalculoNotaBenchmarkTest`).

---

## Documentação

| Assunto | Onde |
|---|---|
| Implantação no k3s, contrato das mensagens, cadastro de empresas, garantias e observabilidade | [k8s/README.md](k8s/README.md) |
| Rodar tudo em **uma VPS** (4 vCPU, 16 GB): overlay de nó único com PostgreSQL no cluster | [k8s/README.md#vps-de-nó-único](k8s/README.md#vps-de-nó-único) |
| Base de regras em PostgreSQL e atualização a cada release do upstream | [flyway/postgres/README.md](flyway/postgres/README.md) |

---

## Requisitos

- **Java 25** (LTS)
- **Maven**: não precisa ser instalado; use o Maven Wrapper (`mvnw` / `mvnw.cmd`)
- **Python 3**: só para regenerar as migrações PostgreSQL
- Para executar: PostgreSQL, RabbitMQ 4.x e Redis (opcional)

Principais tecnologias: Spring Boot 3.5, Spring AMQP, PostgreSQL + Flyway, Caffeine, Redis,
Micrometer (Prometheus e OpenTelemetry).

---

## Compilar e testar

```bash
./mvnw clean test jacoco:report
```

Os testes não precisam de serviços externos nem de Docker:

- **PostgreSQL embarcado** (Zonky): as migrações de `src/main/resources/db/postgres` são
  aplicadas automaticamente;
- **broker AMQP em memória** (rabbitmq-mock) no teste ponta a ponta do worker
  (`WorkerPontaAPontaTest`);
- os manifestos de `k8s/` são validados por `ManifestosK8sTest`.

Benchmark de vazão (fora do build padrão; relatório em `target/benchmark/calculo-nota.txt`):

```bash
./mvnw test -Dsurefire.excludedGroups=nenhum -Dgroups=benchmark
```

---

## Executar localmente

Com PostgreSQL, RabbitMQ e Redis acessíveis, o worker cria a topologia e migra o banco na
primeira execução:

```bash
./mvnw clean package -DskipTests
DB_HOST=localhost DB_NAME=calculadora_pro DB_USER=calculadora DB_PASSWORD=... \
DB_MIGRAR_NA_INICIALIZACAO=true DB_SOMENTE_LEITURA=false \
RABBITMQ_HOST=localhost RABBITMQ_VHOST=rtc WORKER_DECLARAR_TOPOLOGIA=true \
REDIS_HOST=localhost \
java -Dspring.profiles.active=offline -jar target/api-regime-geral.jar
```

Sem Redis, use `WORKER_IDEMPOTENCIA=nenhuma`. Com `WORKER_DECLARAR_TOPOLOGIA=true`, os
resultados de todas as empresas também chegam à fila `rtc.calculo.saida.dev` (só para
desenvolvimento). Health e métricas: `http://localhost:9101/health` e `/metrics`.

Imagem para o k3s:

```bash
docker build -t calculadora-rtc-worker:local .   # no CI/CD a imagem vai para o GHCR
```

---

## Relação com o projeto original

Este repositório deriva de
[ReceitaFederal/calculadora-rtc-regime-regular](https://github.com/ReceitaFederal/calculadora-rtc-regime-regular).
As migrações da base de regras publicadas pelo upstream (`flyway/sql`, dialeto SQLite)
continuam sendo a fonte; a versão PostgreSQL é gerada a partir delas, e o CI falha se as duas
divergirem.

## Referências

- [Code of conduct](https://www.contributor-covenant.org)
- [MIT license](https://choosealicense.com/licenses/mit/)
- [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/)
- [Semantic Versioning](https://semver.org/lang/pt-BR/)

## Licença

Este projeto é distribuído sob a licença especificada no arquivo [LICENSE](LICENSE).
