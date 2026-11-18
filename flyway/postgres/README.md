# Base de regras em PostgreSQL

A calculadora lê a base de regras (alíquotas, classificações, vigências etc.) de um
PostgreSQL. As migrações ficam em `src/main/resources/db/postgres` e são **geradas**
a partir das migrações SQLite publicadas pelo upstream em `flyway/sql`.

## Por que gerar em vez de traduzir

O upstream mantém dezenas de migrações em dialeto SQLite, várias delas recriando
tabelas inteiras (o SQLite não tem `ALTER COLUMN`). Em vez de traduzir cada uma,
o gerador monta o banco SQLite com essas migrações e o converte em três scripts
PostgreSQL determinísticos:

| Arquivo | Conteúdo |
|---|---|
| `V1__schema.sql` | tabelas, chaves primárias, `NOT NULL`, `DEFAULT` e `CHECK` |
| `V2__dados.sql` | todos os registros |
| `V3__indices_chaves_estrangeiras.sql` | índices, `UNIQUE` e chaves estrangeiras (criados depois da carga) |

Mapeamento de tipos: `INTEGER` → `integer`, `REAL` → `numeric` (sem perda de
precisão), datas em `TEXT` (`*_VIGENCIA` e colunas mapeadas como `LocalDate`) →
`date`, demais `TEXT` → `text`. O gerador interrompe a conversão se algum valor
não couber no tipo de destino.

## Atualizar a base após uma release do upstream

```bash
mkdir -p calculadora/db
rm -f calculadora/db/calculadora-pro.db
./mvnw flyway:migrate -Dflyway.configFiles=./flyway/flyway-pro.conf --batch-mode --errors
python -I flyway/postgres/gerar_migracao_postgres.py calculadora/db/calculadora-pro.db
./mvnw test
```

Os scripts são regenerados por completo a cada release. Por isso, cada versão da
base deve ser aplicada em um **banco novo** (por exemplo `calculadora_pro_v0060`),
trocando `DB_NAME` no deploy. Isso evita conflito de checksum no histórico do
Flyway e permite voltar para a versão anterior apenas apontando para o banco antigo.

## Aplicar as migrações

Os workers usam um usuário somente leitura e **não** migram o banco
(`DB_MIGRAR_NA_INICIALIZACAO=false`). Para criar um banco novo, rode a aplicação
uma vez (por exemplo em um `Job` do k3s) com um usuário de escrita:

```bash
DB_MIGRAR_NA_INICIALIZACAO=true DB_SOMENTE_LEITURA=false \
DB_HOST=... DB_NAME=calculadora_pro_v0060 DB_USER=... DB_PASSWORD=... \
java -jar target/api-regime-geral.jar --spring.profiles.active=offline
```

## Variáveis de ambiente

| Variável | Padrão | Uso |
|---|---|---|
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | servidor PostgreSQL |
| `DB_NAME` | `calculadora_pro` | banco da versão da base em uso |
| `DB_USER` / `DB_PASSWORD` | `calculadora` / vazio | credenciais |
| `DB_POOL_MAX` | `8` | conexões por pod (quase tudo vem do cache Caffeine) |
| `DB_SOMENTE_LEITURA` | `true` | conexões somente leitura |
| `DB_MIGRAR_NA_INICIALIZACAO` | `false` | executa o Flyway ao iniciar |

## Testes

Os testes sobem um PostgreSQL embarcado (Zonky Embedded Postgres, binários
baixados pelo Maven, sem Docker) e aplicam estas migrações automaticamente.
