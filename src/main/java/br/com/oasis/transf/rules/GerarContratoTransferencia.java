package br.com.oasis.transf.rules;

import br.com.oasis.transf.service.ContratoArmazemService;
import br.com.oasis.transf.service.GerarPedidoService;
import br.com.oasis.transf.util.ComposicaoUtil;
import br.com.sankhya.extensions.actionbutton.QueryExecutor;
import br.com.sankhya.extensions.regrasnegocio.ContextoRegra;
import br.com.sankhya.extensions.regrasnegocio.RegraNegocioJava;
import br.com.sankhya.jape.core.JapeSession;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public class GerarContratoTransferencia implements RegraNegocioJava {

    private static String resolveHost() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName on = new ObjectName(
                "jboss.as:socket-binding-group=standard-sockets,socket-binding=http");
            Object boundPort = mbs.getAttribute(on, "boundPort");
            return "http://127.0.0.1:" + boundPort;
        } catch (Exception e) {
            return "http://127.0.0.1:8080";
        }
    }

    // Obtém mgeSession do request HTTP original via ThreadLocalWrapper (reflexão).
    private static String getMgeSession() {
        try {
            Class<?> tlw = Class.forName(
                "br.com.sankhya.dwf.controller.util.ThreadLocalWrapper");
            Object req = tlw.getMethod("getRequest").invoke(null);
            if (req != null) {
                String mge = (String) req.getClass()
                    .getMethod("getParameter", String.class)
                    .invoke(req, "mgeSession");
                if (mge != null && !mge.isEmpty()) return mge;
            }
        } catch (Exception ignored) {}
        try {
            JapeSession s = JapeSession.getCurrentSession();
            if (s != null) return s.getSessionGlobalID();
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public void executa(ContextoRegra contexto) throws Exception {

        BigDecimal nuNota = contexto.getNunota();
        if (nuNota == null) return;

        // [1] Ler campos do TGFCAB (flag + AD_*)
        Map<String, Object> cabecalho = lerCabecalho(contexto, nuNota);

        // [2] Verificar flag AD_GERCONTRTRANSF
        String flag = (String) cabecalho.get("AD_GERCONTRTRANSF");
        if (!"S".equalsIgnoreCase(flag)) return;

        // [3] Validar empresa = Filial (CODEMP=4)
        BigDecimal codEmp = (BigDecimal) cabecalho.get("CODEMP");
        if (codEmp == null || codEmp.intValue() != 4) {
            contexto.setSucesso(false);
            contexto.mostraErro("Geração de Contrato Transferência permitida apenas para CODEMP=4 (Filial).");
            return;
        }

        // [4] Buscar itens e mapear composição via processo 40
        Map<BigDecimal, BigDecimal> itensMapeados = mapearItens(contexto, nuNota);
        if (itensMapeados == null) return; // erro já setado no contexto

        BigDecimal codProdPA = itensMapeados.values().iterator().next();

        // [5] Criar TCSCON + TCSPSC via SQL direto
        Map<String, Object> tcsconData = ContratoArmazemService.criar(contexto, cabecalho, codProdPA);
        BigDecimal numContrato = (BigDecimal) tcsconData.get("NUMCONTRATO");

        // [6] Invocar gerarPedidoComercializacao via HTTP interno
        String mgeSession = getMgeSession();
        BigDecimal nuNotaMatriz = GerarPedidoService.gerar(resolveHost(), mgeSession, tcsconData);

        // [7] Gravar NUMCONTRATO no campo adicional do pedido
        atualizarNumContrato(contexto, nuNota, numContrato);

        // [8] Feedback para o usuário
        contexto.setMensagem(
            "Contrato de Armazém nº " + numContrato +
            " e Pedido de Compra (Matriz) nº " + nuNotaMatriz +
            " gerados com sucesso."
        );
    }

    private Map<String, Object> lerCabecalho(ContextoRegra contexto, BigDecimal nuNota) throws Exception {
        QueryExecutor q = contexto.getQuery();
        Map<String, Object> dados = new HashMap<>();
        try {
            q.setParam("NUNOTA", nuNota);
            q.nativeSelect(
                "SELECT CODEMP, CODNAT, CODCENCUS," +
                "  AD_GERCONTRTRANSF, AD_CODSAF, AD_UNICONVSC, AD_CODTIPVENDA_CT," +
                "  AD_TIPOCONTRATO, AD_QTDNEG_SC, AD_VALNEGSC, AD_DTINIENTREGA," +
                "  AD_DTTERMINO, AD_PERCTOLEXCED, AD_TIPOTITULO_CT" +
                " FROM TGFCAB WHERE NUNOTA = {NUNOTA}"
            );
            if (q.next()) {
                dados.put("CODEMP",            q.getBigDecimal("CODEMP"));
                dados.put("CODNAT",            q.getBigDecimal("CODNAT"));
                dados.put("CODCENCUS",         q.getBigDecimal("CODCENCUS"));
                dados.put("AD_GERCONTRTRANSF", q.getString("AD_GERCONTRTRANSF"));
                dados.put("AD_CODSAF",         q.getBigDecimal("AD_CODSAF"));
                dados.put("AD_UNICONVSC",      q.getBigDecimal("AD_UNICONVSC"));
                dados.put("AD_CODTIPVENDA_CT", q.getBigDecimal("AD_CODTIPVENDA_CT"));
                dados.put("AD_TIPOCONTRATO",   q.getString("AD_TIPOCONTRATO"));
                dados.put("AD_QTDNEG_SC",      q.getBigDecimal("AD_QTDNEG_SC"));
                dados.put("AD_VALNEGSC",       q.getBigDecimal("AD_VALNEGSC"));
                dados.put("AD_DTINIENTREGA",   q.getTimestamp("AD_DTINIENTREGA"));
                dados.put("AD_DTTERMINO",      q.getTimestamp("AD_DTTERMINO"));
                dados.put("AD_PERCTOLEXCED",   q.getBigDecimal("AD_PERCTOLEXCED"));
                dados.put("AD_TIPOTITULO_CT",  q.getBigDecimal("AD_TIPOTITULO_CT"));
            }
        } finally {
            q.close();
        }
        return dados;
    }

    private Map<BigDecimal, BigDecimal> mapearItens(ContextoRegra contexto,
                                                     BigDecimal nuNota) throws Exception {
        Map<BigDecimal, BigDecimal> mapa = new HashMap<>();
        QueryExecutor qItens = contexto.getQuery();
        try {
            qItens.setParam("NUNOTA", nuNota);
            qItens.nativeSelect("SELECT CODPROD FROM TGFITE WHERE NUNOTA = {NUNOTA}");
            while (qItens.next()) {
                BigDecimal codProdMP = qItens.getBigDecimal("CODPROD");
                QueryExecutor qComp = contexto.getQuery();
                BigDecimal codProdPA = ComposicaoUtil.buscarCodProdPA(qComp, codProdMP);
                if (codProdPA == null) {
                    contexto.setSucesso(false);
                    contexto.mostraErro(
                        "Composição não encontrada no processo 40 (Beneficiamento Filial)" +
                        " para o produto CODPROD=" + codProdMP + "."
                    );
                    return null;
                }
                mapa.put(codProdMP, codProdPA);
            }
        } finally {
            qItens.close();
        }
        return mapa;
    }

    private void atualizarNumContrato(ContextoRegra contexto,
                                       BigDecimal nuNota,
                                       BigDecimal numContrato) throws Exception {
        QueryExecutor q = contexto.getQuery();
        try {
            q.setParam("NUMCONTRATO", numContrato);
            q.setParam("NUNOTA",      nuNota);
            q.update(
                "UPDATE TGFCAB SET AD_NUMCONTRATO_TRANSF = {NUMCONTRATO}" +
                " WHERE NUNOTA = {NUNOTA}"
            );
        } finally {
            q.close();
        }
    }
}
