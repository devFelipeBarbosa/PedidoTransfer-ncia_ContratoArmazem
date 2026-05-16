package br.com.oasis.transf.service;

import br.com.oasis.transf.util.TLogCatcher;
import br.com.sankhya.extensions.regrasnegocio.ContextoRegra;
import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.vo.EntityVO;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ContratoArmazemService {

    private static final String ENTIDADE_CONTRATO = "ContratoArmazenagemGeral";

    private static final BigDecimal CODEMP         = BigDecimal.ONE;
    private static final BigDecimal CODPARC        = new BigDecimal(4);
    private static final String     ATIVO          = "S";  // default DD
    private static final String     CIF_FOB        = "F";
    private static final BigDecimal PADCLASS       = new BigDecimal(88);
    private static final BigDecimal CODCONTATO     = BigDecimal.ONE;
    private static final String     MODALIDADE     = "C";
    private static final String     TIPOARM        = "A";
    private static final String     COBPROPORCAR   = "E";
    private static final String     SITCONT        = "A";  // default DD

    private ContratoArmazemService() {}

    /**
     * Cria TCSCON via EntityFacade.createEntity. NUMCONTRATO gerado pelo Sankhya
     * (TGFNUM via DD). TCSPSC criado automaticamente por
     * ContratoArmazenagemGeralListener.afterInsert -> insertAlteraCodProd
     * quando CODPROD esta setado no VO do contrato.
     */
    public static Map<String, Object> criar(ContextoRegra contexto,
                                             Map<String, Object> pedido,
                                             BigDecimal codProdPA) throws Exception {
        Timestamp now    = new Timestamp(new Date().getTime());
        BigDecimal codusu = contexto.getUsuarioLogado();

        EntityFacade dwf = EntityFacadeFactory.getDWFFacade();

        BigDecimal qtdNeg = toBD(pedido.get("AD_QTDNEG_SC"));
        if (qtdNeg == null) qtdNeg = BigDecimal.ZERO;

        BigDecimal numContrato = inserirContrato(dwf, pedido, now, codusu, codProdPA, qtdNeg);

        return buildTcsconMap(numContrato, pedido, codProdPA);
    }

    private static BigDecimal inserirContrato(EntityFacade dwf,
                                               Map<String, Object> pedido,
                                               Timestamp now,
                                               BigDecimal codusu,
                                               BigDecimal codProdPA,
                                               BigDecimal qtdNeg) throws Exception {
        EntityVO entityVo = dwf.getDefaultValueObjectInstance(ENTIDADE_CONTRATO);
        DynamicVO vo = (DynamicVO) entityVo;
        vo.setProperty("CODEMP",         CODEMP);
        vo.setProperty("CODPARC",        CODPARC);
        vo.setProperty("CIF_FOB",        CIF_FOB);
        vo.setProperty("PADCLASS",       PADCLASS);
        vo.setProperty("CODCONTATO",     CODCONTATO);
        vo.setProperty("MODALIDADE",     MODALIDADE);
        vo.setProperty("TIPOARM",        TIPOARM);
        vo.setProperty("COBPROPORCAR",   COBPROPORCAR);
        vo.setProperty("DTCONTRATO",     now);
        vo.setProperty("DTBASEREAJ",     now);
        vo.setProperty("CODUSU",         codusu);
        vo.setProperty("CODSAF",         toBD(pedido.get("AD_CODSAF")));
        vo.setProperty("UNICONVSC",      toBD(pedido.get("AD_UNICONVSC")));
        vo.setProperty("CODTIPVENDA",    toBD(pedido.get("AD_CODTIPVENDA_CT")));
        vo.setProperty("TIPOCONTRATO",   toStr(pedido.get("AD_TIPOCONTRATO")));
        vo.setProperty("TIPCON",         toStr(pedido.get("AD_TIPCON")));
        vo.setProperty("QTDNEG",         qtdNeg);
        vo.setProperty("VALNEGSC",       toBD(pedido.get("AD_VALNEGSC")));
        vo.setProperty("DTINIENTREGA",   toTs(pedido.get("AD_DTINIENTREGA")));
        vo.setProperty("DTTERMINO",      toTs(pedido.get("AD_DTTERMINO")));
        vo.setProperty("PERCTOLEXCED",   toBD(pedido.get("AD_PERCTOLEXCED")));
        vo.setProperty("TIPOTITULO",     toBD(pedido.get("AD_TIPOTITULO_CT")));
        vo.setProperty("CODNAT",         toBD(pedido.get("CODNAT")));
        vo.setProperty("CODCENCUS",      toBD(pedido.get("CODCENCUS")));

        // CODPROD + QTDEPREVISTA: campos virtuais lidos por afterInsert listener
        // -> dispara insertAlteraCodProd que cria TCSPSC com PRODPRINC='S'.
        vo.setProperty("CODPROD",        codProdPA);
        vo.setProperty("QTDEPREVISTA",   qtdNeg);

        BigDecimal numContrato;
        try {
            dwf.createEntity(ENTIDADE_CONTRATO, entityVo);
            numContrato = vo.asBigDecimal("NUMCONTRATO");
            if (numContrato == null) {
                throw new Exception("createEntity(" + ENTIDADE_CONTRATO + ") nao gerou NUMCONTRATO");
            }
        } catch (Exception e) {
            TLogCatcher.logError("Erro em ContratoArmazemService.inserirContrato (TCSCON)", e);
            throw e;
        }
        return numContrato;
    }

    public static Map<String, Object> buildTcsconMap(BigDecimal numContrato,
                                                      Map<String, Object> pedido,
                                                      BigDecimal codProdPA) {
        Map<String, Object> m = new HashMap<>();
        m.put("NUMCONTRATO",  numContrato);
        m.put("CODPROD",      codProdPA);
        m.put("CODEMP",       CODEMP);
        m.put("CODPARC",      CODPARC);
        m.put("CODCENCUS",    pedido.get("CODCENCUS"));
        m.put("CODNAT",       pedido.get("CODNAT"));
        m.put("TIPCON",       pedido.get("AD_TIPCON"));
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
