# Implantação no k3s

Pipeline orientado a eventos e **multiempresa**:

```
empresa --nota.<CNPJ>--> rtc.calculo.in --nota.*--> rtc.calculo.entrada --> worker
worker --resultado.<CNPJ>--> rtc.calculo.out --> rtc.calculo.saida.<CNPJ> --> empresa
```

A fila de entrada é compartilhada (um único ponto de escalonamento para o KEDA); a de
saída é uma por empresa. Mensagens ilegíveis ou que falham repetidamente vão para
`rtc.dlx` → `*.dlq`.

## Pré-requisitos no cluster

- RabbitMQ Cluster Operator e Messaging Topology Operator
- KEDA 2.x
- StorageClass `local-path` (padrão do k3s) para o RabbitMQ
- PostgreSQL acessível pelo cluster (gerenciado ou operador de sua escolha)
- Opcional: coletor OTLP/Jaeger em `jaeger-collector.observability.svc:4318` e Prometheus

## Preparação do cluster (uma vez, pelo administrador)

```bash
# namespace e permissões do runner de deploy
kubectl apply -f k8s/00-namespace.yaml -f k8s/deploy/rbac.yaml

# banco da versão atual da base de regras (crie o database antes) e os dois secrets
kubectl -n rtc create secret generic rtc-db-migracao \
  --from-literal=host=<host> --from-literal=name=calculadora_pro_v0059 \
  --from-literal=user=<usuario_escrita> --from-literal=password=<senha>
kubectl -n rtc create secret generic rtc-db \
  --from-literal=host=<host> --from-literal=name=calculadora_pro_v0059 \
  --from-literal=user=<usuario_leitura> --from-literal=password=<senha>

# só se o pacote no GHCR for privado (token com escopo read:packages)
kubectl -n rtc create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io --docker-username=<usuario> --docker-password=<token>
```

Depois disso, o deploy é feito pelo GitHub Actions (seção abaixo). O Topology Operator
cria os secrets `rtc-worker-user-credentials` e `rtc-keda-user-credentials`.

## CI/CD (GitHub Actions)

| Workflow | Quando | O que faz |
|---|---|---|
| `ci.yml` | PR para a `main` | migrações PostgreSQL em dia com o upstream; testes e cobertura (comentário no PR); manifestos validados com kubeconform (inclui CRDs do RabbitMQ e do KEDA) e ShellCheck; build da imagem sem publicar |
| `deploy.yml` | push na `main` | CI → imagem no GHCR (`sha-…`, `homologacao`) → deploy em **homologação** |
| `deploy.yml` | tag `vX.Y.Z` | CI → confere a tag com a versão do `pom.xml` → imagem (`X.Y.Z`, `X.Y`) → deploy em **produção** após aprovação |

O deploy (`.github/actions/deploy-k3s`) roda no runner do ambiente e:

1. confere o acesso ao cluster e a existência dos secrets `rtc-db` e `rtc-db-migracao`;
2. fixa a imagem **por digest** (o que foi testado é exatamente o que sobe);
3. roda o Job de migração e espera concluir. Se as migrações foram regeneradas para uma nova
   versão da base contra o mesmo banco, o Flyway recusa e o deploy para antes de mexer nos
   workers: crie o banco novo e atualize os dois secrets primeiro;
4. aplica `k8s/` e espera o rollout do `rtc-worker`.

### Configuração no GitHub

1. **Environments** (Settings → Environments): `homologacao` e `producao`. Em `producao`,
   configure *Required reviewers* e restrinja a *deployment branches and tags* a `v*`.
2. **Runners self-hosted** (Settings → Actions → Runners), um na rede de cada cluster, com os
   labels `k3s,homologacao` ou `k3s,producao`, e `kubectl` instalado.
3. **kubeconfig do runner** com o token da ServiceAccount `rtc-deployer` (só o namespace `rtc`):

   ```bash
   TOKEN=$(kubectl -n rtc get secret rtc-deployer-token -o jsonpath='{.data.token}' | base64 -d)
   kubectl config set-cluster k3s --server=https://<api-do-k3s>:6443 \
     --certificate-authority=/var/lib/rancher/k3s/server/tls/server-ca.crt --embed-certs=true
   kubectl config set-credentials rtc-deployer --token="$TOKEN"
   kubectl config set-context rtc --cluster=k3s --user=rtc-deployer --namespace=rtc
   kubectl config use-context rtc
   ```

4. **Pacote no GHCR**: criado na primeira publicação (`ghcr.io/<dono>/calculadora-rtc-worker`).
   Se ficar privado, crie o secret `ghcr-pull` no cluster (acima).

### Release em produção

```bash
# 1. ajuste <version> no pom.xml (ex.: 1.7.0), faça o merge na main e valide em homologação
# 2. crie a tag da mesma versão
git tag v1.7.0 && git push origin v1.7.0
# 3. aprove o deploy no environment "producao" (Actions → Deploy)
```

Deploy manual de um commit: *Actions → Deploy → Run workflow*, escolhendo o ambiente.

## Cadastrar uma empresa

```bash
./k8s/empresas/gerar-empresa.sh 12.ABC.345/01DE-35 | kubectl apply -f -
kubectl -n rtc get secret emp-12abc34501de35-user-credentials -o yaml   # entregar à empresa
```

O script aceita CNPJ numérico e alfanumérico, confere os dígitos verificadores e cria,
para a empresa:

| Recurso | Efeito |
|---|---|
| `User` `emp-<cnpj>` | credencial própria da empresa |
| `TopicPermission` em `rtc.calculo.in` | só publica com routing key `nota.<CNPJ>` |
| `Permission` | escreve só em `rtc.calculo.in`; lê só `rtc.calculo.saida.<CNPJ>` |
| `Queue` `rtc.calculo.saida.<CNPJ>` + `Binding` `resultado.<CNPJ>` | recebe só os próprios resultados |

Remover: `kubectl -n rtc delete user,permission,topicpermission,queue,binding -l rtc.serpro.gov.br/cnpj=<cnpj-minúsculo>`.

### Isolamento entre empresas

- **Publicação:** o broker recusa uma routing key de outro CNPJ (topic permission), e o
  worker confere o header `x-cnpj` com a routing key recebida. Divergência → DLQ.
- **Leitura:** cada empresa só tem permissão de leitura na própria fila de saída.
- **Idempotência:** a chave no Redis é `rtc:nota:<CNPJ>:<correlationId>`; o mesmo id em
  empresas diferentes não colide.
- **Justiça entre empresas:** a fila de entrada é FIFO e compartilhada; uma empresa com um
  lote grande atrasa as outras pelo tempo desse lote (8.000 notas ≈ 3 s com 8 consumidores).
  Se isso virar problema, a routing key `nota.<CNPJ>` permite separar filas por empresa
  ou por classe de cliente sem mudar os produtores.

## Contrato das mensagens

**Entrada** (exchange `rtc.calculo.in`, routing key `nota.<CNPJ>`, header `x-cnpj: <CNPJ>`,
`delivery_mode=2`, publisher confirms). O CNPJ pode ir com ou sem pontuação; o campo
`cnpj` do corpo é opcional e, se presente, precisa ser o mesmo do header:

```json
{
  "schemaVersion": "1",
  "correlationId": "7f3c2a7e-1b8e-4c0e-9d7a-1f2b3c4d5e6f",
  "cnpj": "12ABC34501DE35",
  "idNota": "35251112345678000190550010000012341000012345",
  "loteId": "rodada-2026-11-17-01",
  "produzidoEm": "2026-11-17T10:00:00-03:00",
  "payload": { "...": "mesmo JSON do antigo POST /calculadora/regime-geral" }
}
```

`produzidoEm` aceita qualquer data ISO-8601 com fuso (segundos e fração opcionais); as datas
da operação (`payload`) seguem o formato estrito da API original (`yyyy-MM-dd'T'HH:mm:ssXXX`).
`processadoEm` sai sempre como `yyyy-MM-dd'T'HH:mm:ssXXX`. Reenviar o mesmo `correlationId` com
conteúdo diferente manda a mensagem para a DLQ.

**Saída** (fila `rtc.calculo.saida.<CNPJ>`, routing key `resultado.<CNPJ>`): `ResultadoNota`,
com `cnpj`, `status` (`SUCESSO`, `PARCIAL`, `ERRO`), `versaoBase`, `errosNota` (erro da nota
inteira) e `resultado.objetos[]` com `estadoItem` e `erros` por item. Cabeçalhos:
`message_id` e `correlation_id` = correlationId, `x-cnpj`, `x-id-nota`, `x-status`,
`x-versao-base`. Se a empresa não tiver fila de saída, o resultado volta ao worker e a
nota fica na fila de entrada até o limite de entregas.

A entrega é **pelo menos uma vez**: o consumidor deve gravar com chave única
(`idNota`, `correlationId`).

## Garantias do worker

| Situação | Ação |
|---|---|
| Cálculo concluído | publica, espera a confirmação do broker, marca concluída no Redis e só então dá `ack` |
| Erro de negócio (nota ou item) | faz parte do resultado publicado; não é falha |
| JSON ilegível, sem `correlationId`, ou id reutilizado com outro conteúdo | `reject` sem requeue → DLQ |
| Sem header `x-cnpj`, CNPJ inválido, ou header diferente da routing key / do corpo | `reject` sem requeue → DLQ |
| Falha ao publicar, erro técnico (`CAL-999`) ou banco indisponível | `nack` com requeue; após 5 entregas → DLQ |
| Nota já concluída (reenvio) | `ack` sem recalcular |
| Worker caiu no meio | mensagem sem `ack` volta; o próximo worker assume o lease |
| Redis indisponível | processa assim mesmo (o cálculo é determinístico) e incrementa `rtc.worker.idempotencia.indisponivel` |

## Observabilidade

- Logs JSON (ECS) com `cnpj`, `correlationId` e `idNota` no MDC.
- Tracing OTLP: o `traceparent` segue do produtor ao worker e à mensagem de saída.
- Métricas em `:9101/metrics`:
  - `rtc_worker_notas_total{resultado,status}`: publicadas, duplicadas, rejeitadas
    (`rejeitada_empresa` para problemas de CNPJ) e devolvidas. O CNPJ não é tag de métrica,
    para não explodir a cardinalidade; use os logs para filtrar por empresa;
  - `rtc_worker_nota_duracao_seconds`: cálculo + publicação;
  - `cache_gets_total{result}`: acertos do Caffeine;
  - profundidade das filas: plugin `rabbitmq_prometheus` do cluster (porta 15692).
- Alertas sugeridos: DLQ com mais de 0 mensagens; fila de entrada crescendo por mais de 5 min;
  `rtc_worker_idempotencia_indisponivel_total` crescendo.

## Reprocessar a DLQ

Corrija a causa e mova as mensagens de volta, por exemplo com a shovel dinâmica do
RabbitMQ, de `rtc.calculo.entrada.dlq` para a exchange `rtc.calculo.in`, preservando a
routing key `nota.<CNPJ>` e o header `x-cnpj` originais. A `rtc.calculo.saida.dlq` reúne
resultados mortos de todas as empresas (routing key `resultado.<CNPJ>` preservada) e é
acessível só pela operação.
