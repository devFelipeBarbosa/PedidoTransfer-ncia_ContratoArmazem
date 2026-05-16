# Geração Automática de Contrato de Armazém + Pedido de Compra (Matriz)

Personalização Sankhya ERP que, ao confirmar um **Pedido de Transferência** na Filial,
gera automaticamente:

1. **Contrato de Armazenagem** (`TCSCON` + `TCSPSC`) na Matriz — sincronamente
2. **Pedido de Compra de Comercialização** (`TGFCAB`/`TGFITE`) vinculado ao contrato — **assincronamente** (Lançador agendado)

A confirmação responde em **<1s**. O Pedido Matriz é gerado em background, com latência de 15-30s.

---

## Versão Sankhya

- **Build:** `4.35b491`
- **JAPE:** `4.36b32`
- **WS / Cuckoo:** `sanws-4.36b43`
- **Banco:** Oracle 12c+
- **App server:** WildFly + tinyejb

---

## Arquitetura

```
[Confirmação Pedido Filial — Central de Notas]
       │
       ▼
[Regra GerarContratoTransferencia — síncrona, <500ms]
   • valida pré-condições (CODEMP=4, flag AD_GERCONTRTRANSF='S', sem contrato já gerado)
   • mapeia composição (processo 40 — Beneficiamento Filial)
   • ContratoArmazemService.criar()  → cria TCSCON + TCSPSC via EntityFacade
   • UPDATE TGFCAB Filial: AD_NUMCONTRATO_TRANSF
   • INSERT AD_GERAPEDMATRIZ (STATUS='P')
   • COMMIT, retorna mensagem ao usuário
       │
       ▼
[Resposta ao usuário em <1s]

─────────────── async (Cuckoo / ScheduledAction, a cada 15s) ───────────────

[GerarPedidoMatrizLancador.onTime]
   • abre JapeSession + EntityFacade (gerenciado pelo Cuckoo)
   • SELECT FOR UPDATE SKIP LOCKED da fila (lote de 10)
   • por item:
       - JapeSessionContext.putProperty("usuario_logado", codusu)
       - registra ServiceContext mock no ThreadLocal (evita NPE em CACHelper.gerarArquivoEDI)
       - ArmazensGeraisHelper.criarNotaComercializacao()
       - UPDATE TGFCAB Matriz: CODVEND=9 (Comprador)
       - UPDATE AD_GERAPEDMATRIZ: STATUS='S', NUNOTAMATRIZ
     em catch:
       - tentativas+1 < max → STATUS='R' (reprocessar)
       - senão              → STATUS='E' (erro fatal)
       - fallback: se nota Matriz foi criada apesar do erro pós-confirmaNota, marca como sucesso
```

---

## Estrutura do Projeto

```
src/main/java/br/com/oasis/transf/
├── rules/
│   └── GerarContratoTransferencia.java      Regra (adapter — ponto de entrada Filial)
├── service/
│   ├── ContratoArmazemService.java          Cria TCSCON + TCSPSC via EntityFacade
│   ├── FilaPedidoMatrizService.java         Manipula AD_GERAPEDMATRIZ (P/X/S/E/R)
│   └── GerarPedidoService.java              ArmazensGeraisHelper + UPDATE CODVEND
├── lancador/
│   └── GerarPedidoMatrizLancador.java       ScheduledAction (Cuckoo) — gera pedido async
└── util/
    ├── TLogCatcher.java                     Logger arquivo
    ├── TLogConfiguration.java               ThreadLocal path/fileName
    └── TLogType.java                        Enum INFO/ERROR

db/
└── AD_GERAPEDMATRIZ.sql                     DDL tabela fila + sequence + index

build.gradle                                  Java 8, encoding UTF-8
libs/                                         JARs Sankhya (gitignored)
```

---

## Tabela de Fila — `AD_GERAPEDMATRIZ`

State machine: `P` → `X` → (`S` | `E` | `R` → reprocessa → `S` | `E`)

| Coluna | Tipo | Notas |
|---|---|---|
| `NUFILA` | NUMBER(10) PK | gerado por `SEQ_AD_GERAPEDMATRIZ.NEXTVAL` |
| `NUNOTAORIG` | NUMBER(10) NOT NULL | NUNOTA Pedido Filial |
| `NUMCONTRATO` | NUMBER(10) NOT NULL **UNIQUE** | idempotência forte — 1 contrato = 1 linha |
| `NUNOTAMATRIZ` | NUMBER(10) NULL | preenchido após sucesso |
| `STATUS` | VARCHAR2(1) NOT NULL | `P`endente / Processando (`X`) / `S`ucesso / `E`rro fatal / `R`eprocessar |
| `TENTATIVAS` | NUMBER(3) DEFAULT 0 | |
| `MAX_TENTATIVAS` | NUMBER(3) DEFAULT 5 | |
| `MSG_ERRO` | VARCHAR2(4000) NULL | |
| `DHCRIACAO` | TIMESTAMP | enfileiramento |
| `DHPROC` | TIMESTAMP NULL | última tentativa |
| `UUIDFILA` | VARCHAR2(36) NOT NULL | rastro fim-a-fim |
| `CODUSU` | NUMBER(10) | quem confirmou Filial |

Index `IDX_AD_GERAPEDMATRIZ_STATUS (STATUS, DHCRIACAO)` acelera SELECT pendentes do Lançador.

---

## Pré-condições da Regra (Pedido Filial)

| Campo `TGFCAB` | Tipo | Função |
|---|---|---|
| `CODEMP` | NUMBER | Deve ser **4** (Filial) |
| `AD_GERCONTRTRANSF` | VARCHAR(1) | Flag liga/desliga (`'S'` = gera) |
| `AD_NUMCONTRATO_TRANSF` | NUMBER | Vazio — preenchido após geração (proteção contra duplicata) |
| `AD_CODSAF` | NUMBER | Código da Safra |
| `AD_UNICONVSC` | NUMBER | Unidade de Conversão SC |
| `AD_CODTIPVENDA_CT` | NUMBER | Tipo de Venda do Contrato |
| `AD_TIPOCONTRATO` | VARCHAR | Exclusivo / Não Exclusivo |
| `AD_TIPCON` | VARCHAR(1) | `V`/`C`/`F`/`B` |
| `AD_QTDNEG_SC` | NUMBER | Quantidade Negociada |
| `AD_VALNEGSC` | NUMBER | Valor Negociado por SC |
| `AD_DTINIENTREGA` | DATE | Data Início Entrega |
| `AD_DTTERMINO` | DATE | Data Término |
| `AD_PERCTOLEXCED` | NUMBER | % Tolerância Excedente |
| `AD_TIPOTITULO_CT` | NUMBER | Tipo de Título |

---

## Decisões Técnicas

### 1. Processamento async via `ScheduledAction` (Cuckoo)

**Antes:** Regra síncrona chamava `ArmazensGeraisHelper.criarNotaComercializacao` direto.
Tempo total da confirmação: **~7s** (4500ms "Personalizado" no medidor do Sankhya).

**Agora:** Regra só cria TCSCON + insere fila. Pedido Matriz roda em Lançador Cuckoo.
Tempo da confirmação: **<1s**. Tempo do helper migra pra "Nativo" no medidor.

Padrão enterprise: tabela própria, state machine, lock pessimista, retry, idempotência.

### 2. Criação de TCSCON via `EntityFacade.createEntity`

Em vez de INSERT PL/SQL manual:
- `EntityFacade.createEntity("ContratoArmazenagemGeral", vo)` — Sankhya gera `NUMCONTRATO` via TGFNUM atomicamente
- Setar `CODPROD` + `QTDEPREVISTA` no VO do contrato → `ContratoArmazenagemGeralListener.afterInsert` chama `insertAlteraCodProd(vo)` que **cria TCSPSC automaticamente** com `PRODPRINC='S'`

### 3. Lock pessimista cluster-safe

```sql
SELECT NUFILA, ... FROM AD_GERAPEDMATRIZ
 WHERE STATUS IN ('P','R') AND TENTATIVAS < MAX_TENTATIVAS
   AND ROWNUM <= 10
 ORDER BY DHCRIACAO
 FOR UPDATE SKIP LOCKED        -- Oracle 12c+
```

2 Lançadores em nós diferentes → pegam linhas disjuntas, sem duplicar pedido.

### 4. ServiceContext mock — workaround NPE de EDI

`ConfirmacaoNotaHelper.confirmaNota` chama `ServiceContext.getCurrent()` que retorna `null` em thread Cuckoo. Helper passa null pra `CACHelper.gerarArquivoEDI(Collection, ServiceContext)` → `ctx.getBodyElement()` → **NPE**.

**Bloco EDI não pula por property JapeSession** (bytecode confirmado: pula só se nota for NFE/NFSE/CTE/NFCom).

Solução: registrar `ServiceContext` mock no ThreadLocal interno via reflection:
```java
Constructor<?> c = ServiceContext.class.getDeclaredConstructor(HttpServletRequest.class);
c.setAccessible(true);
Object mock = c.newInstance(new Object[]{ null });
Field field = ServiceContext.class.getDeclaredField("current");
field.setAccessible(true);
((ThreadLocal<Object>) field.get(null)).set(mock);
```

O construtor `ServiceContext(null)` já inicializa `bodyElement = new Element("responseBody")` — não-null. Reflection usada pra evitar dependência `javax.servlet` no compile-time.

`finally` chama `limparServiceContextMock()` para evitar vazamento entre execuções.

### 5. CODVEND=9 na nota Matriz

`criarNotaComercializacao` não recebe CODVEND como param. UPDATE direto após criação:
```sql
UPDATE TGFCAB SET CODVEND = 9 WHERE NUNOTA = ?
```

### 6. Logging — padrão `TLogCatcher`

`TLogConfiguration` armazena path/fileName em ThreadLocal. `TLogCatcher.logInfo/logError` grava em `<repo>/personalizacao/log<YYYY-MM-DD>-<nome>.txt`.

Logs separados Regra (`GerarContratoTransferencia`) vs Lançador (`GerarPedidoMatrizLancador`).

### 7. Encoding UTF-8

`compileJava.options.encoding = 'UTF-8'` evita mojibake em acentos.

---

## Constantes

| Constante | Valor | Onde |
|---|---|---|
| CODEMP Matriz (contrato/pedido) | `1` | `ContratoArmazemService` / `GerarPedidoService` |
| CODPARC Matriz | `4` | `ContratoArmazemService` |
| CODVEND Pedido Matriz | `9` (Comprador) | `GerarPedidoService` |
| CODTIPOPER Pedido Matriz | `3006` | `GerarPedidoService` |
| CODLOCAL Pedido Matriz | `3030100` | `GerarPedidoService` |
| PADCLASS | `88` | `ContratoArmazemService` |
| TIPOARM | `'A'` | `ContratoArmazemService` |
| MODALIDADE | `'C'` | `ContratoArmazemService` |
| COBPROPORCAR | `'E'` | `ContratoArmazemService` |
| CIF_FOB | `'F'` | `ContratoArmazemService` |
| CODEMP Filial (dispara regra) | `4` | `GerarContratoTransferencia` |
| CODPRC composição | `40` | `GerarContratoTransferencia` |
| LOTE Lançador | `10` itens / ciclo | `GerarPedidoMatrizLancador` |
| MAX_TENTATIVAS | `5` | DDL default |

---

## Setup Inicial (homologação / produção)

1. **Aplicar DDL** (em ambiente Sankhya):
   ```sql
   @db/AD_GERAPEDMATRIZ.sql
   ```

2. **Build e copiar JAR**:
   ```bash
   ./gradlew clean jar
   # build/libs/oasis-transf-contrato-1.0.0.jar
   ```
   Subir no **Repositório de Arquivos Sankhya**.

3. **Cadastrar Tarefa Agendada** (tela `Configurações > Tarefas Agendadas`, Cuckoo):
   - **Classe**: `br.com.oasis.transf.lancador.GerarPedidoMatrizLancador`
   - **Intervalo**: 15 segundos
   - **Ativa**: S

4. **Hot reload do módulo Java** (ou restart WildFly se necessário).

---

## Verificação (Smoke Test)

### Caminho feliz
1. Confirmar Pedido Filial (CODEMP=4, TOP=1307, AD_GERCONTRTRANSF='S')
2. Tempo de resposta deve ser **<1s**. Mensagem: "Contrato nº X criado. Pedido de Compra (Matriz) será gerado em background."
3. `SELECT * FROM AD_GERAPEDMATRIZ ORDER BY DHCRIACAO DESC` → STATUS='P'
4. Aguardar 15-30s
5. Re-SELECT → STATUS='S', NUNOTAMATRIZ preenchido
6. Verificar Pedido Matriz na Central: CODEMP=1, CODVEND=9, confirmado

### Caminho falha (validação retry)
1. Forçar erro (ex: apagar TCSPSC antes do Lançador rodar)
2. STATUS='R' após 1ª falha → retry automático na próxima janela
3. Após 5 falhas → STATUS='E', `MSG_ERRO` populado

### Logs

- Regra: `<repo>/personalizacao/log<YYYY-MM-DD>-GerarContratoTransferencia.txt`
- Lançador: `<repo>/personalizacao/log<YYYY-MM-DD>-GerarPedidoMatrizLancador.txt`

---

## Operação — manutenção da fila

### Reprocessar item específico (após corrigir causa raiz):
```sql
UPDATE AD_GERAPEDMATRIZ SET STATUS='R', TENTATIVAS=0, MSG_ERRO=NULL
 WHERE NUFILA = :id;
COMMIT;
```

### Listar pendentes / erros:
```sql
SELECT NUFILA, NUNOTAORIG, NUMCONTRATO, STATUS, TENTATIVAS, MSG_ERRO, DHCRIACAO
  FROM AD_GERAPEDMATRIZ
 WHERE STATUS IN ('P','X','R','E')
 ORDER BY DHCRIACAO DESC;
```

### Cancelar item manualmente:
```sql
UPDATE AD_GERAPEDMATRIZ SET STATUS='E', MSG_ERRO='Cancelado manualmente'
 WHERE NUFILA = :id;
COMMIT;
```

---

## Tabelas Envolvidas

| Tabela | Papel |
|---|---|
| `TGFCAB` | Pedido Filial (origem) + Pedido Matriz (criado pelo Lançador) |
| `TGFITE` | Itens dos pedidos |
| `TCSCON` | Contrato de Armazenagem (criado pela Regra) |
| `TCSPSC` | Produtos/Serviços do contrato (criado pelo listener) |
| `TGFNUM` | Numerador (NUMCONTRATO sequência — gerenciado pelo Sankhya) |
| `TPRLMP` / `TPRATV` / `TPRPRC` | Composição do produto (processo 40) |
| `AD_GERAPEDMATRIZ` | Fila assíncrona (nova) |

---

## JARs do Classpath (compileOnly)

- `SankhyaW-extensions.jar` — `RegraNegocioJava`, `ContextoRegra`, `QueryExecutor`, `org.cuckoo.core.ScheduledAction`
- `jape-4.36b32.jar` — `EntityFacade`, `JdbcWrapper`, `NativeSql`, `JapeSession`, `JapeSessionContext`
- `mge-modelcore-4.35b491.jar` — `EntityFacadeFactory`, `SWRepositoryUtils`
- `mgearmazem-model-4.35b491.jar` — `ArmazensGeraisHelper.criarNotaComercializacao`
- `sanws-4.36b43.jar` — `br.com.sankhya.ws.ServiceContext` (necessário pra mock do ThreadLocal)

JARs não são versionados (ver `.gitignore`). Copiados de:
- `<wildfly>/standalone/deployments/sankhyaw.ear/lib/` (sanws, jape)
- `<wildfly>/standalone/deployments/erpcore.ear/web/mgearmazem-*.war` (mgearmazem)
- Biblioteca interna `4.35b491` (mge-modelcore, mgearmazem-model)

---

## Próximos Passos

- **Junho/2026 — Homologação com cliente.**
  - Validar fluxo fim-a-fim com dados reais em homologação Turquesa
  - Stress test: confirmar N pedidos Filial em paralelo e verificar fila + ausência de duplicação
  - Confirmar latência aceitável do Lançador (15s padrão; ajustar se UX exigir <10s)
  - Validar comportamento de retry quando contrato/pedido Matriz tem inconsistência (ex: parceiro sem CGC, TOP fora do ar)

- **Fase 2 (após homologação)**
  - Tela "Monitor Fila Pedido Matriz" pra suporte/operação (Construtor de Telas em cima de `AD_GERAPEDMATRIZ`)
  - Notificação Sankhya ao usuário origem quando NUFILA atinge STATUS='E'
  - Métricas/dashboard via BIA: latência média P50/P95, taxa de erro, throughput

- **Eventual**
  - Migrar `SELECT FOR UPDATE SKIP LOCKED` pra abstração caso projeto vá pra SQL Server (hoje hardcoded Oracle)
  - Considerar Kafka/RabbitMQ se volume crescer (>1000 itens/min) — atualmente fila Oracle é suficiente

---

## Histórico de Mudanças Relevantes

- **v1.0** (2026-05-15→16) — versão síncrona inicial
- **v1.1** — refactor TCSCON via `EntityFacade.createEntity` (Sankhya gera NUMCONTRATO + TCSPSC via listener)
- **v1.2** — otimizações performance (1 query cabeçalho+itens, remoção logInfo, cotação simplificada)
- **v1.3** — CODVEND=9 (Comprador) na Matriz
- **v2.0** — **arquitetura async** com `ScheduledAction` + fila `AD_GERAPEDMATRIZ` + ServiceContext mock pra EDI

---

## Autoria

Felipe Barbosa — Oasis Alimentos
