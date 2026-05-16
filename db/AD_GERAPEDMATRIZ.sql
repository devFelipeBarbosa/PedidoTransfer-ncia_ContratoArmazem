-- Tabela de fila para geração assíncrona de Pedido Matriz.
-- Aplicar em homologação primeiro. Oracle 12c+.
--
-- State machine STATUS:
--   P = Pendente (inserido pela Regra, aguardando Lançador)
--   X = Processando (Lançador pegou linha, antes de chamar helper)
--   S = Sucesso (Pedido Matriz criado, NUNOTAMATRIZ preenchido)
--   E = Erro fatal (excedeu MAX_TENTATIVAS)
--   R = Reprocessar (erro transitório, próxima janela retenta)

CREATE TABLE AD_GERAPEDMATRIZ (
    NUFILA          NUMBER(10)      NOT NULL,
    NUNOTAORIG      NUMBER(10)      NOT NULL,
    NUMCONTRATO     NUMBER(10)      NOT NULL,
    NUNOTAMATRIZ    NUMBER(10)      NULL,
    STATUS          VARCHAR2(1)     NOT NULL,
    TENTATIVAS      NUMBER(3)       DEFAULT 0 NOT NULL,
    MAX_TENTATIVAS  NUMBER(3)       DEFAULT 5 NOT NULL,
    MSG_ERRO        VARCHAR2(4000)  NULL,
    DHCRIACAO       TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    DHPROC          TIMESTAMP       NULL,
    UUIDFILA        VARCHAR2(36)    NOT NULL,
    CODUSU          NUMBER(10)      NULL,
    CONSTRAINT PK_AD_GERAPEDMATRIZ      PRIMARY KEY (NUFILA),
    CONSTRAINT UK_AD_GERAPEDMATRIZ_CTR  UNIQUE      (NUMCONTRATO),
    CONSTRAINT CK_AD_GERAPEDMATRIZ_ST   CHECK       (STATUS IN ('P','X','S','E','R'))
);

-- Acelera SELECT do Lançador (pendentes ordem cronológica)
CREATE INDEX IDX_AD_GERAPEDMATRIZ_STATUS ON AD_GERAPEDMATRIZ (STATUS, DHCRIACAO);

-- Sequência da PK (uso pelo INSERT)
CREATE SEQUENCE SEQ_AD_GERAPEDMATRIZ START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

-- Validação pós-aplicação:
-- SELECT TABLE_NAME, NUM_ROWS FROM USER_TABLES WHERE TABLE_NAME = 'AD_GERAPEDMATRIZ';
-- SELECT SEQUENCE_NAME, LAST_NUMBER FROM USER_SEQUENCES WHERE SEQUENCE_NAME = 'SEQ_AD_GERAPEDMATRIZ';
