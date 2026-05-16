package br.com.oasis.transf.service;

import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Acesso à fila async AD_GERAPEDMATRIZ.
 * State machine: P -> X -> S | (E|R).
 * Idempotência via UNIQUE(NUMCONTRATO).
 */
public final class FilaPedidoMatrizService {

    public static final String STATUS_PENDENTE     = "P";
    public static final String STATUS_PROCESSANDO  = "X";
    public static final String STATUS_SUCESSO      = "S";
    public static final String STATUS_ERRO         = "E";
    public static final String STATUS_REPROCESSAR  = "R";

    private FilaPedidoMatrizService() {}

    /** DTO de linha pendente lida pelo Lançador. */
    public static final class Pendente {
        public final BigDecimal nufila;
        public final BigDecimal numContrato;
        public final BigDecimal nunotaOrig;
        public final int tentativas;
        public final int maxTentativas;
        public final String uuid;
        public final BigDecimal codusu;

        public Pendente(BigDecimal nufila, BigDecimal numContrato, BigDecimal nunotaOrig,
                        int tentativas, int maxTentativas, String uuid, BigDecimal codusu) {
            this.nufila        = nufila;
            this.numContrato   = numContrato;
            this.nunotaOrig    = nunotaOrig;
            this.tentativas    = tentativas;
            this.maxTentativas = maxTentativas;
            this.uuid          = uuid;
            this.codusu        = codusu;
        }
    }

    /**
     * Insere linha na fila (chamado pela Regra após criar TCSCON).
     * UUID gerado server-side pra rastro fim-a-fim.
     */
    public static void inserir(JdbcWrapper jdbc,
                                BigDecimal nunotaOrig,
                                BigDecimal numContrato,
                                BigDecimal codusu) throws Exception {
        NativeSql sql = new NativeSql(jdbc);
        try {
            sql.appendSql(
                "INSERT INTO AD_GERAPEDMATRIZ (" +
                "  NUFILA, NUNOTAORIG, NUMCONTRATO, STATUS, " +
                "  TENTATIVAS, MAX_TENTATIVAS, DHCRIACAO, UUIDFILA, CODUSU" +
                ") VALUES (" +
                "  SEQ_AD_GERAPEDMATRIZ.NEXTVAL, ?, ?, 'P', " +
                "  0, 5, SYSTIMESTAMP, ?, ?" +
                ")"
            );
            sql.addParameter(nunotaOrig);
            sql.addParameter(numContrato);
            sql.addParameter(UUID.randomUUID().toString());
            sql.addParameter(codusu);
            sql.executeUpdate();
        } finally {
            NativeSql.releaseResources(sql);
        }
    }

    /**
     * Lock pessimista de pendentes (STATUS=P|R com tentativas<max).
     * Oracle FOR UPDATE SKIP LOCKED -> cluster-safe.
     */
    public static List<Pendente> selecionarPendentesParaProcessar(JdbcWrapper jdbc, int limite) throws Exception {
        NativeSql sql = new NativeSql(jdbc);
        ResultSet rs;
        List<Pendente> lista = new ArrayList<>();
        try {
            sql.appendSql(
                "SELECT NUFILA, NUMCONTRATO, NUNOTAORIG, TENTATIVAS, MAX_TENTATIVAS, UUIDFILA, CODUSU " +
                "  FROM AD_GERAPEDMATRIZ " +
                " WHERE STATUS IN ('P','R') " +
                "   AND TENTATIVAS < MAX_TENTATIVAS " +
                "   AND ROWNUM <= ? " +
                " ORDER BY DHCRIACAO " +
                " FOR UPDATE SKIP LOCKED"
            );
            sql.addParameter(new BigDecimal(limite));
            rs = sql.executeQuery();
            while (rs != null && rs.next()) {
                lista.add(new Pendente(
                    rs.getBigDecimal("NUFILA"),
                    rs.getBigDecimal("NUMCONTRATO"),
                    rs.getBigDecimal("NUNOTAORIG"),
                    rs.getInt("TENTATIVAS"),
                    rs.getInt("MAX_TENTATIVAS"),
                    rs.getString("UUIDFILA"),
                    rs.getBigDecimal("CODUSU")
                ));
            }
        } finally {
            NativeSql.releaseResources(sql);
        }
        return lista;
    }

    /** Marca como Processando + incrementa tentativas. Deve ser COMMITado antes de chamar helper. */
    public static void marcarProcessando(JdbcWrapper jdbc, BigDecimal nufila) throws Exception {
        NativeSql sql = new NativeSql(jdbc);
        try {
            sql.appendSql(
                "UPDATE AD_GERAPEDMATRIZ " +
                "   SET STATUS='X', TENTATIVAS=TENTATIVAS+1, DHPROC=SYSTIMESTAMP " +
                " WHERE NUFILA=? AND STATUS IN ('P','R','X')"
            );
            sql.addParameter(nufila);
            sql.executeUpdate();
        } finally {
            NativeSql.releaseResources(sql);
        }
    }

    public static void marcarSucesso(JdbcWrapper jdbc, BigDecimal nufila, BigDecimal nunotaMatriz) throws Exception {
        NativeSql sql = new NativeSql(jdbc);
        try {
            sql.appendSql(
                "UPDATE AD_GERAPEDMATRIZ " +
                "   SET STATUS='S', NUNOTAMATRIZ=?, MSG_ERRO=NULL, DHPROC=SYSTIMESTAMP " +
                " WHERE NUFILA=?"
            );
            sql.addParameter(nunotaMatriz);
            sql.addParameter(nufila);
            sql.executeUpdate();
        } finally {
            NativeSql.releaseResources(sql);
        }
    }

    /**
     * Marca como R (reprocessar) se ainda há tentativas, ou E (erro fatal).
     * tentativasAtual já foi incrementado em marcarProcessando.
     */
    public static void marcarFalha(JdbcWrapper jdbc, BigDecimal nufila,
                                    int tentativasAtual, int maxTentativas,
                                    String msgErro) throws Exception {
        String novoStatus = tentativasAtual + 1 >= maxTentativas ? STATUS_ERRO : STATUS_REPROCESSAR;
        NativeSql sql = new NativeSql(jdbc);
        try {
            sql.appendSql(
                "UPDATE AD_GERAPEDMATRIZ " +
                "   SET STATUS=?, MSG_ERRO=?, DHPROC=SYSTIMESTAMP " +
                " WHERE NUFILA=?"
            );
            sql.addParameter(novoStatus);
            sql.addParameter(truncar(msgErro, 4000));
            sql.addParameter(nufila);
            sql.executeUpdate();
        } finally {
            NativeSql.releaseResources(sql);
        }
    }

    private static String truncar(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
