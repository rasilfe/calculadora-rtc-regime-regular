# Implantação no k3s

Pipeline orientado a eventos e **multiempresa**:

```
notas:
empresa --nota.<CNPJ>--> rtc.calculo.in --nota.*--> rtc.calculo.entrada --> worker
worker --resultado.<CNPJ>--> rtc.calculo.out --> rtc.calculo.saida.<CNPJ> --> empresa

base de cálculo unificada (um item por mensagem):
empresa --base.<CNPJ>--> rtc.calculo.in --base.*--> rtc.basecalculo.entrada --> worker
worker --resultado-base.<CNPJ>--> rtc.calculo.out --> rtc.basecalculo.saida.<CNPJ> --> empresa
```

Cada fila de entrada é compartilhada entre as empresas (um ponto de escalonamento para o
KEDA); as de saída são uma por empresa. A base de cálculo tem fila própria para não
esperar atrás de uma rodada de notas. Mensagens ilegíveis ou que falham repetidamente vão para
`rtc.dlx` → `*.dlq`.

## Ambientes

| Diretório | Para quê |
|---|---|
| `k8s/` (manifestos em `k8s/base/`) | Cluster de **3 nós**: RabbitMQ com 3 réplicas em nós diferentes, worker de 2 a 12 pods, PostgreSQL externo |
| `k8s/ambientes/vps` + `k8s/ambientes/vps/banco` | **Nó único** (VPS de 4 vCPU e 16 GB): RabbitMQ com 1 réplica, worker de 1 a 2 pods, PostgreSQL no próprio cluster. Ver [VPS de nó único](#vps-de-nó-único) |
| `k8s/ambientes/enxuto` + `k8s/ambientes/enxuto/banco` | **Nó único de ~6 GB** (ex.: VM num Proxmox que divide o host com outras VMs): como o vps, mas com 1 worker e limites de memória menores. Ver [Nó enxuto](#nó-enxuto-6-gb) |

Os overlays partem de `k8s/base/`, e não de `k8s/`: o kustomize 5.8+ (o do kubectl do k3s)
recusa um overlay que fica dentro da raiz da própria base.

## Pré-requisitos no cluster

- RabbitMQ Cluster Operator e Messaging Topology Operator (este exige o cert-manager)
- KEDA 2.x
- StorageClass `local-path` (padrão do k3s) para o RabbitMQ
- PostgreSQL acessível pelo cluster (no ambiente VPS, ele roda no próprio cluster)
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
| `TopicPermission` em `rtc.calculo.in` | só publica com routing key `nota.<CNPJ>` ou `base.<CNPJ>` |
| `Permission` | escreve só em `rtc.calculo.in`; lê só `rtc.calculo.saida.<CNPJ>` e `rtc.basecalculo.saida.<CNPJ>` |
| `Queue` `rtc.calculo.saida.<CNPJ>` + `Binding` `resultado.<CNPJ>` | recebe só os próprios resultados de notas |
| `Queue` `rtc.basecalculo.saida.<CNPJ>` + `Binding` `resultado-base.<CNPJ>` | recebe só os próprios resultados de base de cálculo |

O script é idempotente: rodá-lo de novo para uma empresa já cadastrada acrescenta o que
faltar (por exemplo, a fila de base de cálculo das empresas cadastradas antes dela) sem
trocar a senha.

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

### Base de cálculo unificada

Calcula as três bases de um item (regime geral IBS/CBS, Imposto Seletivo e Simples
Nacional), como o antigo `POST /calculadora/base-calculo/base-calculo-unificada`.

**Entrada** (exchange `rtc.calculo.in`, routing key `base.<CNPJ>`, header `x-cnpj`, mesmas
propriedades da nota). O `payload` é o JSON do antigo endpoint, com os componentes na raiz:

```json
{
  "schemaVersion": "1",
  "correlationId": "0b6f1c2e-5d4a-4e3b-9c8d-7a6b5c4d3e2f",
  "idCalculo": "pedido-123-item-1",
  "loteId": "opcional",
  "produzidoEm": "2026-11-18T10:00:00-03:00",
  "payload": {
    "anoFatoGerador": 2026, "tipoDocumento": 55, "natureza": "bem",
    "vProd": 1000.00, "vFrete": 50.00, "vDesc": 50.00, "vICMS": 180.00
  }
}
```

**Saída** (fila `rtc.basecalculo.saida.<CNPJ>`, routing key `resultado-base.<CNPJ>`):
`ResultadoBaseCalculo`, com `status` `SUCESSO` (e `resultado` com `baseRG`, `baseIS`,
`baseSN` e `avisos` sobre campos ignorados) ou `ERRO` (e `erros` no mesmo formato dos erros
de nota: campo obrigatório ausente, valor negativo, ou `REG-024` para `tipoDocumento`
diferente de 55 e 65). Cabeçalhos: `message_id` e `correlation_id` = correlationId,
`x-cnpj`, `x-status` e `x-id-calculo` (se informado).

Diferenças em relação à nota: não há idempotência no Redis (o cálculo é puro e leva
milissegundos; uma reentrega recalcula e publica de novo) e não há rejeição por
`correlationId` repetido. Deduplique pelo `correlationId`. Mensagens ilegíveis ou com
empresa inválida vão para `rtc.basecalculo.entrada.dlq`.

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

## VPS de nó único

Para uma VPS com **4 vCPU e 16 GB de RAM** (Debian 12 ou 13). Tudo roda na mesma máquina:
k3s, RabbitMQ (1 réplica), PostgreSQL, Redis, worker (1 a 2 pods) e, opcionalmente, o runner
do GitHub.

| Componente | Reserva (CPU / RAM) | Limite de RAM |
|---|---|---|
| Sistema (k3s, operadores, KEDA, cert-manager) | ~0,5 / ~2 GB (estimativa) | — |
| RabbitMQ | 500m / 1 GiB | 2 GiB |
| PostgreSQL | 250m / 512 MiB | 1 GiB |
| Redis | 100m / 128 MiB | 256 MiB |
| Worker (por pod, até 2) | 1 / 1,5 GiB | 2 GiB |

O `AmbienteVpsTest` falha se os recursos do overlay deixarem de caber na VPS.

**Vazão esperada:** o cálculo usa cerca de 0,5 ms de CPU por item, então uma rodada de
70.000 itens (≈35 s de CPU) deve levar na faixa de **12 a 20 s** com ~3 vCPU livres para os
workers. É estimativa; meça na VPS com uma rodada real.

### 1. k3s

```bash
sudo apt update && sudo apt install -y curl ca-certificates chrony
sudo swapoff -a && sudo sed -i '/\sswap\s/s/^/#/' /etc/fstab
echo 'fs.file-max = 1000000' | sudo tee /etc/sysctl.d/90-k3s.conf && sudo sysctl --system
sudo mkdir -p /etc/rancher/k3s && printf 'disable:\n  - traefik\nwrite-kubeconfig-mode: "0600"\ntls-san:\n  - <ip-ou-dns-da-vps>\n' | sudo tee /etc/rancher/k3s/config.yaml
curl -sfL https://get.k3s.io | INSTALL_K3S_CHANNEL=stable sh -s - server
```

Com um nó só não é preciso `cluster-init`: o banco embutido padrão do k3s é mais leve que o etcd.

**Firewall:** exponha só SSH e, se precisar administrar de fora, a porta 6443 restrita ao seu IP.
O RabbitMQ fica interno ao cluster (veja "Acesso das empresas" abaixo).

### 2. Ferramentas e preparação

Instale o cert-manager, os operadores do RabbitMQ e o KEDA como na instalação geral e depois:

```bash
kubectl apply -f k8s/00-namespace.yaml -f k8s/deploy/rbac.yaml

# senhas do PostgreSQL do cluster (o script de inicialização cria rtc_migracao e rtc_leitura)
kubectl -n rtc create secret generic rtc-postgres \
  --from-literal=senha-admin=<forte> --from-literal=senha-migracao=<forte-1> --from-literal=senha-leitura=<forte-2>

# mesmas credenciais, no formato que o worker e o Job de migração esperam
kubectl -n rtc create secret generic rtc-db-migracao --from-literal=host=rtc-postgres.rtc.svc \
  --from-literal=name=calculadora_pro_v0059 --from-literal=user=rtc_migracao --from-literal=password=<forte-1>
kubectl -n rtc create secret generic rtc-db --from-literal=host=rtc-postgres.rtc.svc \
  --from-literal=name=calculadora_pro_v0059 --from-literal=user=rtc_leitura --from-literal=password=<forte-2>
```

O nome do banco (`calculadora_pro_v0059`) precisa ser o mesmo do ConfigMap em
`k8s/ambientes/vps/banco/postgres.yaml`. O script de usuários só roda na primeira
inicialização; para uma nova versão da base, crie o banco novo à mão:

```bash
kubectl -n rtc exec -it rtc-postgres-0 -- psql -U postgres -c 'CREATE DATABASE calculadora_pro_v0060 OWNER rtc_migracao' \
  -c 'REVOKE ALL ON DATABASE calculadora_pro_v0060 FROM PUBLIC' \
  -c 'GRANT CONNECT ON DATABASE calculadora_pro_v0060 TO rtc_migracao, rtc_leitura'
kubectl -n rtc exec -it rtc-postgres-0 -- psql -U postgres -d calculadora_pro_v0060 \
  -c 'ALTER DEFAULT PRIVILEGES FOR ROLE rtc_migracao IN SCHEMA public GRANT SELECT ON TABLES TO rtc_leitura'
```

### 3. Deploy pelo GitHub Actions

No environment (ex.: `homologacao`), em *Settings → Environments → Variables*:

| Variável | Valor |
|---|---|
| `K8S_MANIFESTOS` | `k8s/ambientes/vps` |
| `K8S_PRE_REQUISITOS` | `k8s/ambientes/vps/banco` |

O deploy aplica o PostgreSQL primeiro, espera ficar pronto, roda o Job de migração e só então
aplica o restante. O runner self-hosted pode rodar na própria VPS (serviço systemd), com o
kubeconfig da ServiceAccount `rtc-deployer` apontando para `https://127.0.0.1:6443`.

Deploy manual, sem o GitHub:

```bash
kubectl apply -k k8s/ambientes/vps/banco && kubectl -n rtc rollout status statefulset/rtc-postgres
```

```bash
kubectl apply -k k8s/ambientes/vps
```

### Limitações deste ambiente

- **Sem redundância:** se a VPS cair, tudo para. As mensagens persistidas no RabbitMQ e os
  dados do PostgreSQL voltam com ela; faça backup do PostgreSQL e dos snapshots do k3s fora da VPS.
- **Acesso das empresas:** neste overlay o RabbitMQ não é exposto para fora do cluster. No
  overlay enxuto, as empresas chegam por um Traefik externo que termina o TLS (ver
  [Acesso das empresas pelo Traefik](#acesso-das-empresas-pelo-traefik)).

## Nó enxuto (6 GB)

Para uma VM de 4 vCPU e cerca de 6 GB de RAM, por exemplo num host Proxmox de 16 GB que
também roda outras VMs. O preparo é o mesmo da [VPS de nó único](#vps-de-nó-único);
mudam só os diretórios:

| Variável do environment | Valor |
|---|---|
| `K8S_MANIFESTOS` | `k8s/ambientes/enxuto` |
| `K8S_PRE_REQUISITOS` | `k8s/ambientes/enxuto/banco` |

Diferenças em relação ao vps:

- **1 pod do worker** (KEDA de 1 a 1), com limite de 1,5 GiB (heap de cerca de 1 GiB);
- **rollout sem pod extra** (`maxSurge: 0`): durante o deploy o worker fica fora por
  alguns segundos e as notas esperam na fila;
- RabbitMQ com limite de 1 GiB e disco de 5 GiB; PostgreSQL com limite de 768 MiB e
  `max_connections=30`.

Com 3 consumidores num pod só, a vazão é de cerca de um terço da do vps. Serve para
homologação e volumes pequenos.

No k3s, reserve memória para o sistema, para o kubelet despejar pods antes de o kernel
matar processos (`/etc/rancher/k3s/config.yaml`):

```yaml
kubelet-arg:
  - "system-reserved=cpu=250m,memory=512Mi"
  - "kube-reserved=cpu=250m,memory=512Mi"
  - "eviction-hard=memory.available<200Mi"
```

### Acesso das empresas pelo Traefik

As empresas conectam com AMQP sobre TLS (`amqps://rtc-homogacao.casa.lan:5671`, vhost
`rtc`). O Traefik da vm-infra termina o TLS e repassa AMQP puro para o k3s:

```
empresa --TLS :5671--> Traefik (vm-infra, 192.168.5.112) --AMQP :5672--> k3s-1 (192.168.5.120)
```

- **k3s:** o Service `rtc-rabbit-lan` (`k8s/ambientes/enxuto/rabbitmq-lan.yaml`) abre a 5672
  no IP do nó pelo ServiceLB e aceita só a origem 192.168.5.112. Ele não tem NodePort
  (`allocateLoadBalancerNodePorts: false`), que ficaria aberta para qualquer origem.
  Se a vm-infra mudar de IP, ajuste `loadBalancerSourceRanges`.
- **Traefik:** acrescente o entrypoint `amqps` de `docs/traefik/traefik-estatico.yml` à
  configuração estática (com `readTimeout: 0s`, senão o Traefik v3 derruba conexões AMQP
  ociosas após 60 s), publique a porta 5671 e coloque `docs/traefik/rtc-amqps.yml` no
  diretório do provider file.
- **Certificado:** `.lan` não tem Let's Encrypt. Use a CA interna (ou mkcert) e entregue o
  `ca.crt` às empresas; com um wildcard `*.casa.lan` já configurado no Traefik, remova o
  bloco `certificates` do arquivo.
- **DNS:** `rtc-homogacao.casa.lan` deve resolver para 192.168.5.112.

Teste a partir de uma máquina da rede (com o `ca.crt`):

```bash
openssl s_client -connect rtc-homogacao.casa.lan:5671 -servername rtc-homogacao.casa.lan -CAfile ca.crt </dev/null | grep -E "Verify return|subject="
```

A rede entre o Traefik e o k3s leva AMQP sem criptografia, inclusive as senhas das empresas.
Isso é aceitável numa rede local de homologação. Para produção, use TLS também nesse trecho
(`spec.tls` no `RabbitmqCluster` e `serversTransport` no Traefik) ou TLS passthrough.
