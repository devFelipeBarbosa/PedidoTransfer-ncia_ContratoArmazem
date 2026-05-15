package br.com.oasis.transf.service;

import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.vo.EntityVO;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ContratoArmazemService {

    // Valores fixos de negócio (confirmados no INSERT capturado no monitor)
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

    /**
     * Cria TCSCON + TCSPSC na Matriz (CODEMP=1) via EntityFacade JAPE.
     *
     * @param pedido    Campos do Pedido da Filial (AD_* + CODNAT + CODCENCUS)
     * @param codProdPA CODPRODPA mapeado via ComposicaoUtil
     * @return          Map com NUMCONTRATO e campos do TCSCON (payload para GerarPedidoService)
     */
    public static Map<String, Object> criar(Map<String, Object> pedido,
                                             BigDecimal codProdPA) throws Exception {
        BigDecimal numContrato = inserirTcscon(pedido);
        inserirTcspsc(numContrato, codProdPA);
        return buildTcsconMap(numContrato, pedido, codProdPA);
    }

    private static BigDecimal inserirTcscon(Map<String, Object> pedido) throws Exception {
        EntityFacade facade = EntityFacade.getInstance("TCSCON");
        EntityVO voEntity = facade.getDefaultValueObjectInstance("TCSCON");
        DynamicVO vo = (DynamicVO) voEntity;

        // Campos fixos
        vo.setProperty("CODEMP",         new BigDecimal(CODEMP));
        vo.setProperty("CODPARC",        new BigDecimal(CODPARC));
        vo.setProperty("ATIVO",          ATIVO);
        vo.setProperty("CIF_FOB",        CIF_FOB);
        vo.setProperty("PADCLASS",       new BigDecimal(PADCLASS));
        vo.setProperty("CODMOEDA",       new BigDecimal(CODMOEDA));
        vo.setProperty("CODCONTATO",     new BigDecimal(CODCONTATO));
        vo.setProperty("EXIGEPEDIDOPES", EXIGEPEDIDOPES);
        vo.setProperty("MODALIDADE",     MODALIDADE);
        vo.setProperty("TIPOARM",        TIPOARM);
        vo.setProperty("TIPO",           TIPO);
        vo.setProperty("COBPROPORCAR",   COBPROPORCAR);
        vo.setProperty("SITCONT",        "A");
        vo.setProperty("DTCONTRATO",     new Timestamp(new Date().getTime()));

        // Campos vindos dos AD_* do Pedido da Filial
        vo.setProperty("CODSAF",       toBD(pedido.get("AD_CODSAF")));
        vo.setProperty("UNICONVSC",    toBD(pedido.get("AD_UNICONVSC")));
        vo.setProperty("CODTIPVENDA",  toBD(pedido.get("AD_CODTIPVENDA_CT")));
        vo.setProperty("TIPOCONTRATO", toStr(pedido.get("AD_TIPOCONTRATO")));
        vo.setProperty("QTDNEG",       toBD(pedido.get("AD_QTDNEG_SC")));
        vo.setProperty("VALNEGSC",     toBD(pedido.get("AD_VALNEGSC")));
        vo.setProperty("DTINIENTREGA", toTs(pedido.get("AD_DTINIENTREGA")));
        vo.setProperty("DTTERMINO",    toTs(pedido.get("AD_DTTERMINO")));
        vo.setProperty("PERCTOLEXCED", toBD(pedido.get("AD_PERCTOLEXCED")));
        vo.setProperty("TIPOTITULO",   toBD(pedido.get("AD_TIPOTITULO_CT")));

        // Herdados do cabeçalho do pedido
        vo.setProperty("CODNAT",    toBD(pedido.get("CODNAT")));
        vo.setProperty("CODCENCUS", toBD(pedido.get("CODCENCUS")));

        facade.createEntity("TCSCON", voEntity);
        return vo.asBigDecimal("NUMCONTRATO");
    }

    private static void inserirTcspsc(BigDecimal numContrato, BigDecimal codProdPA) throws Exception {
        EntityFacade facade = EntityFacade.getInstance("TCSPSC");
        EntityVO voEntity = facade.getDefaultValueObjectInstance("TCSPSC");
        DynamicVO vo = (DynamicVO) voEntity;

        vo.setProperty("NUMCONTRATO",  numContrato);
        vo.setProperty("CODPROD",      codProdPA);
        vo.setProperty("SITPROD",      "A");
        vo.setProperty("IMPRNOTA",     "S");
        vo.setProperty("IMPROS",       "N");
        vo.setProperty("LIMITANTE",    "N");
        vo.setProperty("PRODPRINC",    "N");
        vo.setProperty("QTDEPREVISTA", BigDecimal.ZERO);
        vo.setProperty("VLRUNIT",      BigDecimal.ZERO);
        vo.setProperty("CODUSUALTREG", BigDecimal.ZERO);
        vo.setProperty("DHALTREG",     new Timestamp(new Date().getTime()));

        facade.createEntity("TCSPSC", voEntity);
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
