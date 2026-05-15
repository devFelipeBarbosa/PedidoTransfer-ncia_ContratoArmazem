package br.com.oasis.transf.service;

import br.com.sankhya.extensions.actionbutton.QueryExecutor;
import br.com.sankhya.extensions.regrasnegocio.ContextoRegra;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ContratoArmazemService {

    private static final int    CODEMP         = 1;
    private static final int    CODPARC        = 4;
    private static final String ATIVO          = "S";
    private static final String CIF_FOB        = "F";
    private static final int    PADCLASS       = 88;
    private static final int    CODMOEDA       = 0;
    private static final int    CODCONTATO     = 1;
    private static final String EXIGEPEDIDOPES = "S";
    private static final String MODALIDADE     = "C";
    private static final String TIPOARM        = "A";
    private static final String TIPO           = "M";
    private static final String COBPROPORCAR   = "E";

    private ContratoArmazemService() {}

    public static Map<String, Object> criar(ContextoRegra contexto,
                                             Map<String, Object> pedido,
                                             BigDecimal codProdPA) throws Exception {
        BigDecimal numContrato = inserirTcscon(contexto, pedido);
        inserirTcspsc(contexto, numContrato, codProdPA);
        return buildTcsconMap(numContrato, pedido, codProdPA);
    }

    private static BigDecimal inserirTcscon(ContextoRegra contexto,
                                             Map<String, Object> pedido) throws Exception {
        BigDecimal numContrato;
        QueryExecutor qSeq = contexto.getQuery();
        try {
            qSeq.nativeSelect("SELECT NVL(MAX(NUMCONTRATO), 0) + 1 AS NUMCONTRATO FROM TCSCON");
            numContrato = qSeq.next() ? qSeq.getBigDecimal("NUMCONTRATO") : BigDecimal.ONE;
        } finally {
            qSeq.close();
        }

        Timestamp now = new Timestamp(new Date().getTime());
        BigDecimal codusu = contexto.getUsuarioLogado();
        QueryExecutor q = contexto.getQuery();
        try {
            q.setParam("NUMCONTRATO",    numContrato);
            q.setParam("CODEMP",         new BigDecimal(CODEMP));
            q.setParam("CODPARC",        new BigDecimal(CODPARC));
            q.setParam("ATIVO",          ATIVO);
            q.setParam("CIF_FOB",        CIF_FOB);
            q.setParam("PADCLASS",       new BigDecimal(PADCLASS));
            q.setParam("CODMOEDA",       new BigDecimal(CODMOEDA));
            q.setParam("CODCONTATO",     new BigDecimal(CODCONTATO));
            q.setParam("EXIGEPEDIDOPES", EXIGEPEDIDOPES);
            q.setParam("MODALIDADE",     MODALIDADE);
            q.setParam("TIPOARM",        TIPOARM);
            q.setParam("TIPO",           TIPO);
            q.setParam("COBPROPORCAR",   COBPROPORCAR);
            q.setParam("SITCONT",        "A");
            q.setParam("DTCONTRATO",     now);
            q.setParam("CODUSU",         codusu);
            q.setParam("CODSAF",         toBD(pedido.get("AD_CODSAF")));
            q.setParam("UNICONVSC",      toBD(pedido.get("AD_UNICONVSC")));
            q.setParam("CODTIPVENDA",    toBD(pedido.get("AD_CODTIPVENDA_CT")));
            q.setParam("TIPOCONTRATO",   toStr(pedido.get("AD_TIPOCONTRATO")));
            q.setParam("QTDNEG",         toBD(pedido.get("AD_QTDNEG_SC")));
            q.setParam("VALNEGSC",       toBD(pedido.get("AD_VALNEGSC")));
            q.setParam("DTINIENTREGA",   toTs(pedido.get("AD_DTINIENTREGA")));
            q.setParam("DTTERMINO",      toTs(pedido.get("AD_DTTERMINO")));
            q.setParam("PERCTOLEXCED",   toBD(pedido.get("AD_PERCTOLEXCED")));
            q.setParam("TIPOTITULO",     toBD(pedido.get("AD_TIPOTITULO_CT")));
            q.setParam("CODNAT",         toBD(pedido.get("CODNAT")));
            q.setParam("CODCENCUS",      toBD(pedido.get("CODCENCUS")));

            q.update(
                "INSERT INTO TCSCON (" +
                "  NUMCONTRATO, CODEMP, CODPARC, ATIVO, CIF_FOB, PADCLASS, CODMOEDA," +
                "  CODCONTATO, EXIGEPEDIDOPES, MODALIDADE, TIPOARM, TIPO, COBPROPORCAR," +
                "  SITCONT, DTCONTRATO, CODUSU, CODSAF, UNICONVSC, CODTIPVENDA, TIPOCONTRATO," +
                "  QTDNEG, VALNEGSC, DTINIENTREGA, DTTERMINO, PERCTOLEXCED, TIPOTITULO," +
                "  CODNAT, CODCENCUS" +
                ") VALUES (" +
                "  {NUMCONTRATO}, {CODEMP}, {CODPARC}, {ATIVO}, {CIF_FOB}, {PADCLASS}, {CODMOEDA}," +
                "  {CODCONTATO}, {EXIGEPEDIDOPES}, {MODALIDADE}, {TIPOARM}, {TIPO}, {COBPROPORCAR}," +
                "  {SITCONT}, {DTCONTRATO}, {CODUSU}, {CODSAF}, {UNICONVSC}, {CODTIPVENDA}, {TIPOCONTRATO}," +
                "  {QTDNEG}, {VALNEGSC}, {DTINIENTREGA}, {DTTERMINO}, {PERCTOLEXCED}, {TIPOTITULO}," +
                "  {CODNAT}, {CODCENCUS}" +
                ")"
            );
        } finally {
            q.close();
        }
        return numContrato;
    }

    private static void inserirTcspsc(ContextoRegra contexto,
                                       BigDecimal numContrato,
                                       BigDecimal codProdPA) throws Exception {
        Timestamp now = new Timestamp(new Date().getTime());
        QueryExecutor q = contexto.getQuery();
        try {
            q.setParam("NUMCONTRATO",  numContrato);
            q.setParam("CODPROD",      codProdPA);
            q.setParam("SITPROD",      "A");
            q.setParam("IMPRNOTA",     "S");
            q.setParam("IMPROS",       "N");
            q.setParam("LIMITANTE",    "N");
            q.setParam("PRODPRINC",    "N");
            q.setParam("QTDEPREVISTA", BigDecimal.ZERO);
            q.setParam("VLRUNIT",      BigDecimal.ZERO);
            q.setParam("CODUSUALTREG", BigDecimal.ZERO);
            q.setParam("DHALTREG",     now);

            q.update(
                "INSERT INTO TCSPSC (" +
                "  NUMCONTRATO, CODPROD, SITPROD, IMPRNOTA, IMPROS, LIMITANTE, PRODPRINC," +
                "  QTDEPREVISTA, VLRUNIT, CODUSUALTREG, DHALTREG" +
                ") VALUES (" +
                "  {NUMCONTRATO}, {CODPROD}, {SITPROD}, {IMPRNOTA}, {IMPROS}, {LIMITANTE}, {PRODPRINC}," +
                "  {QTDEPREVISTA}, {VLRUNIT}, {CODUSUALTREG}, {DHALTREG}" +
                ")"
            );
        } finally {
            q.close();
        }
    }

    private static Map<String, Object> buildTcsconMap(BigDecimal numContrato,
                                                       Map<String, Object> pedido,
                                                       BigDecimal codProdPA) {
        Map<String, Object> m = new HashMap<>();
        m.put("NUMCONTRATO",    numContrato);
        m.put("CODPROD",        codProdPA);
        m.put("CODEMP",         CODEMP);
        m.put("CODPARC",        CODPARC);
        m.put("ATIVO",          ATIVO);
        m.put("CIF_FOB",        CIF_FOB);
        m.put("PADCLASS",       PADCLASS);
        m.put("CODMOEDA",       CODMOEDA);
        m.put("CODCONTATO",     CODCONTATO);
        m.put("EXIGEPEDIDOPES", EXIGEPEDIDOPES);
        m.put("MODALIDADE",     MODALIDADE);
        m.put("TIPOARM",        TIPOARM);
        m.put("TIPO",           TIPO);
        m.put("COBPROPORCAR",   COBPROPORCAR);
        m.put("SITCONT",        "A");
        m.put("CODSAF",         pedido.get("AD_CODSAF"));
        m.put("UNICONVSC",      pedido.get("AD_UNICONVSC"));
        m.put("CODTIPVENDA",    pedido.get("AD_CODTIPVENDA_CT"));
        m.put("TIPOCONTRATO",   pedido.get("AD_TIPOCONTRATO"));
        m.put("QTDNEG",         pedido.get("AD_QTDNEG_SC"));
        m.put("VALNEGSC",       pedido.get("AD_VALNEGSC"));
        m.put("DTINIENTREGA",   pedido.get("AD_DTINIENTREGA"));
        m.put("DTTERMINO",      pedido.get("AD_DTTERMINO"));
        m.put("PERCTOLEXCED",   pedido.get("AD_PERCTOLEXCED"));
        m.put("TIPOTITULO",     pedido.get("AD_TIPOTITULO_CT"));
        m.put("CODNAT",         pedido.get("CODNAT"));
        m.put("CODCENCUS",      pedido.get("CODCENCUS"));
        return m;
    }

    private static BigDecimal toBD(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        String s = v.toString().trim();
        return s.isEmpty() ? null : new BigDecimal(s);
    }

    private static String toStr(Object v) {
        return v == null ? null : v.toString();
    }

    private static Timestamp toTs(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp) return (Timestamp) v;
        if (v instanceof Date) return new Timestamp(((Date) v).getTime());
        return null;
    }
}
