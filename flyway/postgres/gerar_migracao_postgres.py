"""Gera as migrações Flyway do PostgreSQL a partir do banco SQLite da calculadora.

O upstream (Receita Federal) publica a base de regras como migrações em dialeto
SQLite (flyway/sql). Em vez de traduzir cada migração, este script lê o banco
SQLite já montado por elas e gera, de forma determinística:

  src/main/resources/db/postgres/V1__schema.sql                 tabelas, chaves primárias, NOT NULL, DEFAULT e CHECK
  src/main/resources/db/postgres/V2__dados.sql                  todos os registros
  src/main/resources/db/postgres/V3__indices_chaves_estrangeiras.sql  índices, UNIQUE e chaves estrangeiras

Mapeamento de tipos:
  INTEGER / INT     -> integer   (consultas nativas fazem cast para Integer)
  REAL              -> numeric   (sem perda de precisão; lido como BigDecimal)
  VARCHAR(n)        -> varchar(n)
  TEXT com datas    -> date      (colunas *_VIGENCIA, *_DATA e mapeadas como LocalDate)
  TEXT              -> text

Uso (a partir da raiz do repositório, depois de gerar o banco SQLite como no README):
  python -I flyway/postgres/gerar_migracao_postgres.py calculadora/db/calculadora-pro.db
"""
import os
import re
import sqlite3
import sys
from datetime import date

RAIZ = os.path.dirname(os.path.abspath(__file__))
SAIDA = os.path.join(RAIZ, "..", "..", "src", "main", "resources", "db", "postgres")
ENTIDADES = os.path.join(RAIZ, "..", "..", "src", "main", "java", "br", "gov", "serpro", "rtc", "domain", "model", "entity")
IGNORAR = re.compile(r"^(sqlite_|flyway_schema_history$)")
DATA_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
# Data com hora: a entidade lê LocalDate, então só a parte da data é aproveitada.
DATA_HORA_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})[ T]\d{2}:\d{2}(:\d{2}(\.\d+)?)?$")
LINHAS_POR_INSERT = 500

CABECALHO = """-- =====================================================================
-- GERADO AUTOMATICAMENTE por flyway/postgres/gerar_migracao_postgres.py
-- a partir do banco SQLite (versão da base: {versao}). Não edite à mão:
-- altere as migrações SQLite em flyway/sql e gere novamente.
-- =====================================================================

"""


def q(s):
    return "'" + s.replace("'", "''") + "'"


def colunas_localdate_das_entidades():
    """Colunas mapeadas como LocalDate nas entidades JPA."""
    nomes = set()
    if not os.path.isdir(ENTIDADES):
        return nomes
    for arq in os.listdir(ENTIDADES):
        if arq.endswith(".java"):
            src = open(os.path.join(ENTIDADES, arq), encoding="utf-8").read()
            for m in re.finditer(r'@Column\(\s*name\s*=\s*"(\w+)"[^)]*\)\s*(?:@\w+(?:\([^)]*\))?\s*)*private\s+LocalDate\s', src):
                nomes.add(m.group(1).upper())
    return nomes


def extrair_checks(sql):
    checks = []
    for m in re.finditer(r"CHECK\s*\(", sql, re.I):
        i, nivel = m.end(), 1
        while nivel:
            nivel += {"(": 1, ")": -1}.get(sql[i], 0)
            i += 1
        expr = re.sub(r"\s+", " ", sql[m.end():i - 1]).strip()
        checks.append(re.sub(r'"(\w+)"', r"\1", expr))
    return checks


def main(caminho_sqlite):
    con = sqlite3.connect(caminho_sqlite)
    con.text_factory = str
    versao = con.execute("SELECT VRBD_VERSAO_BASE_DADO FROM VERSAO_BASE_DADO ORDER BY VRBD_ID DESC LIMIT 1").fetchone()
    versao = versao[0] if versao else "desconhecida"
    entidades_data = colunas_localdate_das_entidades()

    tabelas = [r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name") if not IGNORAR.match(r[0])]

    tipos = {}  # (tabela, coluna) -> tipo postgres
    ddl, dados, extras = [], [], []
    problemas = []
    avisos = {}  # coluna -> valores data/hora truncados para data

    for t in tabelas:
        sql_criacao = con.execute("SELECT sql FROM sqlite_master WHERE name=?", (t,)).fetchone()[0]
        cols = con.execute(f'PRAGMA table_info("{t}")').fetchall()
        linhas_col, pk = [], []
        for _, nome, tipo, notnull, default, ordem_pk in cols:
            tipo_u = (tipo or "").upper()
            valores = [r[0] for r in con.execute(f'SELECT "{nome}" FROM "{t}" WHERE "{nome}" IS NOT NULL')]
            if tipo_u in ("INTEGER", "INT"):
                pg = "integer"
            elif tipo_u == "REAL":
                pg = "numeric"
            elif tipo_u.startswith("VARCHAR"):
                pg = tipo_u.lower()
            elif tipo_u == "BOOLEAN":
                pg = "boolean"
            elif tipo_u == "TEXT":
                eh_data = (nome.upper().endswith("_VIGENCIA") or nome.upper() in entidades_data
                           or (valores and all(isinstance(v, str) and DATA_RE.match(v) for v in valores)))
                pg = "date" if eh_data else "text"
            else:
                problemas.append(f"{t}.{nome}: tipo SQLite sem mapeamento: {tipo!r}")
                pg = "text"
            # Tipagem dinâmica do SQLite: confere se os valores cabem no tipo de destino.
            for v in valores:
                if pg == "integer" and not isinstance(v, int):
                    problemas.append(f"{t}.{nome}: valor não inteiro {v!r}")
                    break
                if pg == "numeric" and not isinstance(v, (int, float)):
                    problemas.append(f"{t}.{nome}: valor não numérico {v!r}")
                    break
                if pg == "date" and isinstance(v, str) and DATA_HORA_RE.match(v):
                    avisos[f"{t}.{nome}"] = avisos.get(f"{t}.{nome}", 0) + 1
                    continue
                if pg == "date" and not (isinstance(v, str) and DATA_RE.match(v)):
                    problemas.append(f"{t}.{nome}: valor não é data {v!r}")
                    break
                if pg == "integer" and abs(v) > 2**31 - 1:
                    problemas.append(f"{t}.{nome}: inteiro fora de int4 {v!r}")
                    break
            tipos[(t, nome)] = pg

            definicao = f"    {nome} {pg}"
            if notnull:
                definicao += " NOT NULL"
            if default is not None and default.upper() != "NULL":
                if default.lower().startswith("strftime(") and pg == "date":
                    definicao += " DEFAULT CURRENT_DATE"
                elif default.lower().startswith("strftime("):
                    definicao += """ DEFAULT to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS.MS-0300')"""
                else:
                    definicao += f" DEFAULT {default}"
            linhas_col.append(definicao)
            if ordem_pk:
                pk.append((ordem_pk, nome))
        if pk:
            linhas_col.append("    PRIMARY KEY (" + ", ".join(n for _, n in sorted(pk)) + ")")
        for expr in extrair_checks(sql_criacao):
            linhas_col.append(f"    CHECK ({expr})")
        ddl.append(f"CREATE TABLE {t} (\n" + ",\n".join(linhas_col) + "\n);\n")

        # Dados
        nomes = [c[1] for c in cols]
        registros = con.execute(f'SELECT * FROM "{t}" ORDER BY rowid').fetchall()
        for i in range(0, len(registros), LINHAS_POR_INSERT):
            lote = registros[i:i + LINHAS_POR_INSERT]
            valores_sql = []
            for reg in lote:
                partes = []
                for nome, v in zip(nomes, reg):
                    pg = tipos[(t, nome)]
                    if v is None:
                        partes.append("NULL")
                    elif pg == "date":
                        m = DATA_HORA_RE.match(v)
                        v = m.group(1) if m else v
                        date.fromisoformat(v)  # valida
                        partes.append(q(v))
                    elif pg == "numeric":
                        partes.append(repr(float(v)) if isinstance(v, float) else str(v))
                    elif pg in ("integer",):
                        partes.append(str(v))
                    elif pg == "boolean":
                        partes.append("TRUE" if v else "FALSE")
                    else:
                        partes.append(q(str(v)))
                valores_sql.append("(" + ", ".join(partes) + ")")
            dados.append(f"INSERT INTO {t} ({', '.join(nomes)}) VALUES\n" + ",\n".join(valores_sql) + ";\n")

    # Índices e UNIQUE (antes das FKs: o PostgreSQL exige UNIQUE/PK na coluna referenciada)
    unicas = set()  # (tabela, (colunas))
    for t in tabelas:
        for _, nome_idx, unico, origem, parcial in con.execute(f'PRAGMA index_list("{t}")').fetchall():
            colunas = tuple(r[2] for r in con.execute(f'PRAGMA index_info("{nome_idx}")'))
            if origem == "pk":
                unicas.add((t, colunas))
                continue
            if origem == "u":
                extras.append(f"ALTER TABLE {t} ADD UNIQUE ({', '.join(colunas)});\n")
                unicas.add((t, colunas))
                continue
            sql_idx = con.execute("SELECT sql FROM sqlite_master WHERE name=?", (nome_idx,)).fetchone()[0]
            sql_idx = re.sub(r'"(\w+)"', r"\1", re.sub(r"\s+", " ", sql_idx)).strip()
            if re.search(r"COLLATE|GLOB", sql_idx, re.I):
                problemas.append(f"índice com sintaxe específica do SQLite: {sql_idx}")
            extras.append(sql_idx.rstrip(";") + ";\n")
            if unico and not parcial and None not in colunas:
                unicas.add((t, colunas))
    for t in tabelas:
        pk_cols = tuple(n for _, n in sorted((c[5], c[1]) for c in con.execute(f'PRAGMA table_info("{t}")') if c[5]))
        if pk_cols:
            unicas.add((t, pk_cols))

    # Chaves estrangeiras
    for t in tabelas:
        fks = {}
        for fid, seq, tabela_ref, de, para, *_ in con.execute(f'PRAGMA foreign_key_list("{t}")').fetchall():
            fks.setdefault(fid, (tabela_ref, []))[1].append((seq, de, para))
        for fid, (tabela_ref, pares) in sorted(fks.items()):
            pares.sort()
            origem = [p[1] for p in pares]
            destino = [p[2] for p in pares]
            if None in destino:
                destino = [n for _, n in sorted((c[5], c[1]) for c in con.execute(f'PRAGMA table_info("{tabela_ref}")') if c[5])]
            if (tabela_ref, tuple(destino)) not in unicas:
                extras.append(f"-- FK {t}({', '.join(origem)}) -> {tabela_ref}({', '.join(destino)}) omitida: "
                              "a coluna referenciada não é PK nem UNIQUE.\n")
                continue
            extras.append(f"ALTER TABLE {t} ADD CONSTRAINT fk_{t.lower()}_{fid} FOREIGN KEY ({', '.join(origem)}) "
                          f"REFERENCES {tabela_ref} ({', '.join(destino)});\n")
    extras.append("\nANALYZE;\n")

    for coluna, n in avisos.items():
        print(f"aviso: {coluna}: {n} valores com hora gravados só com a data (a entidade lê LocalDate)")
    if problemas:
        print("Conversão interrompida:\n  " + "\n  ".join(problemas), file=sys.stderr)
        sys.exit(1)

    os.makedirs(SAIDA, exist_ok=True)
    cab = CABECALHO.format(versao=versao)
    for nome, partes in (("V1__schema.sql", ddl), ("V2__dados.sql", dados),
                         ("V3__indices_chaves_estrangeiras.sql", extras)):
        with open(os.path.join(SAIDA, nome), "w", encoding="utf-8", newline="\n") as f:
            f.write(cab + "\n".join(partes))
    total = sum(con.execute(f'SELECT count(*) FROM "{t}"').fetchone()[0] for t in tabelas)
    print(f"{len(tabelas)} tabelas, {total} registros, versão da base {versao} -> {SAIDA}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
