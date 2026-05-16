package br.com.oasis.transf.service;

import br.com.sankhya.armazem.model.helper.ArmazensGeraisHelper;
import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class GerarPedidoService {

    private static final BigDecimal COD_TIP_OPER = new BigDecimal(3006);
    private static final BigDecimal COD_LOCAL    = new BigDecimal(3030100);
    private static final BigDecimal CODVEND      = new BigDecimal(9);
    private static final String     SERIE        = "";
    private static final String     CONTROLE     = " ";
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private GerarPedidoService() {}

    /**
     * Gera Pedido de Compra de Comercializacao chamando direto
     * ArmazensGeraisHelper.criarNotaComercializacao -- mesmo metodo interno
     * que ContratosArmazemGeralSPBean.gerarPedidoComercializacao invoca.
     * Sem HTTP, sem ServiceContext, sem PlatformService -- JVM nativa.
     * Cotacao nao se aplica a esta rotina -> ZERO (helper aceita).
     *
     * @param numContrato NUMCONTRATO do TCSCON recem-criado
     * @return            NUNOTA do Pedido de Compra gerado
     */
    public static BigDecimal gerar(BigDecimal numContrato) throws Exception {
        EntityFacade dwf = EntityFacadeFactory.getDWFFacade();
        JdbcWrapper jdbc = dwf.getJdbcWrapper();

        String dtEmissao = LocalDate.now().format(DT_FMT);

        Map<String, Object> params = new HashMap<>();
        params.put("DTEMISSAO",  dtEmissao);
        params.put("CODTIPOPER", COD_TIP_OPER);
        params.put("SERIE",      SERIE);
        params.put("CODLOCAL",   COD_LOCAL);
        params.put("CONTROLE",   CONTROLE);

        BigDecimal nunota = ArmazensGeraisHelper.criarNotaComercializacao(
            jdbc, dwf, numContrato, params, null, BigDecimal.ZERO
        );

        if (nunota == null) {
            throw new Exception("criarNotaComercializacao retornou null para NUMCONTRATO=" + numContrato);
        }

        atualizarCodVend(jdbc, nunota);
        return nunota;
    }

    /** Seta CODVEND=9 (Comprador) no Pedido Matriz. Helper nativo nao recebe esse param. */
    private static void atualizarCodVend(JdbcWrapper jdbc, BigDecimal nunota) throws Exception {
        NativeSql sql = new NativeSql(jdbc);
        try {
            sql.appendSql("UPDATE TGFCAB SET CODVEND = ? WHERE NUNOTA = ?");
            sql.addParameter(CODVEND);
            sql.addParameter(nunota);
            sql.executeUpdate();
        } finally {
            NativeSql.releaseResources(sql);
        }
    }
}
