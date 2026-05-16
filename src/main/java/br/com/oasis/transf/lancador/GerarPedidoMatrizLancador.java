package br.com.oasis.transf.lancador;

import br.com.oasis.transf.service.FilaPedidoMatrizService;
import br.com.oasis.transf.service.FilaPedidoMatrizService.Pendente;
import br.com.oasis.transf.service.GerarPedidoService;
import br.com.oasis.transf.util.TLogCatcher;
import br.com.oasis.transf.util.TLogConfiguration;
import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.util.JapeSessionContext;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;
import br.com.sankhya.modelcore.util.SWRepositoryUtils;
import br.com.sankhya.ws.ServiceContext;

import org.cuckoo.core.ScheduledAction;
import org.cuckoo.core.ScheduledActionContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lancador agendado Sankhya (Cuckoo). Cadastrado em Configuracoes > Tarefas Agendadas.
 *
 * Cuckoo (DefaultSchedulerEnvironment.runWithTransaction) ja abre TX antes de onTime.
 * Logo: NAO chamar execWithTX/beginTransaction aqui (PersistenceError "Ja existe TX").
 * Toda a operacao roda na TX do Cuckoo, commitada no final do onTime.
 *
 * Isolamento entre Lancadores concorrentes (cluster): FOR UPDATE SKIP LOCKED no
 * selecionarPendentesParaProcessar. Lock liberado no commit final.
 */
public class GerarPedidoMatrizLancador implements ScheduledAction {

    private static final int LOTE = 10;

    @Override
    public void onTime(ScheduledActionContext ctx) {
        TLogConfiguration.setFileName("GerarPedidoMatrizLancador");
        TLogConfiguration.setPath(SWRepositoryUtils.getBaseFolder() + "/personalizacao");

        try {
            EntityFacade dwf = EntityFacadeFactory.getDWFFacade();
            JdbcWrapper jdbc = dwf.getJdbcWrapper();

            List<Pendente> pendentes = FilaPedidoMatrizService.selecionarPendentesParaProcessar(jdbc, LOTE);
            if (pendentes.isEmpty()) return;

            // Marca todas como X antes de processar (incrementa tentativas).
            for (Pendente p : pendentes) {
                FilaPedidoMatrizService.marcarProcessando(jdbc, p.nufila);
            }

            for (Pendente p : pendentes) {
                processarItem(jdbc, p);
            }
        } catch (Exception e) {
            TLogCatcher.logError("Erro fatal em GerarPedidoMatrizLancador.onTime", e);
        } finally {
            limparServiceContextMock();
            TLogConfiguration.clear();
        }
    }

    private void processarItem(JdbcWrapper jdbc, Pendente p) {
        BigDecimal codusu = p.codusu != null ? p.codusu : BigDecimal.ZERO;
        prepararContextoSankhya(codusu);
        try {
            BigDecimal nunotaMatriz = GerarPedidoService.gerar(p.numContrato);
            FilaPedidoMatrizService.marcarSucesso(jdbc, p.nufila, nunotaMatriz);
        } catch (Exception e) {
            // Fallback: gerarArquivoEDI da NPE em background pq contexto HTTP nulo.
            // Verifica se a nota Matriz foi de fato criada antes do erro.
            BigDecimal nunotaMatriz = tentarRecuperarNotaMatriz(jdbc, p.numContrato);
            if (nunotaMatriz != null) {
                try {
                    FilaPedidoMatrizService.marcarSucesso(jdbc, p.nufila, nunotaMatriz);
                    TLogCatcher.logError(
                        "Nota Matriz NUNOTA=" + nunotaMatriz +
                        " criada apesar de erro pos-confirmacao (EDI/contexto). NUFILA=" + p.nufila +
                        " UUID=" + p.uuid, e
                    );
                } catch (Exception exMark) {
                    TLogCatcher.logError("Falha marcarSucesso pos-recuperacao NUFILA=" + p.nufila, exMark);
                }
                return;
            }

            TLogCatcher.logError(
                "Falha geracao Pedido Matriz NUFILA=" + p.nufila +
                " NUMCONTRATO=" + p.numContrato +
                " TENTATIVA=" + (p.tentativas + 1) + "/" + p.maxTentativas +
                " UUID=" + p.uuid, e
            );
            try {
                FilaPedidoMatrizService.marcarFalha(
                    jdbc, p.nufila, p.tentativas, p.maxTentativas,
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                );
            } catch (Exception ex) {
                TLogCatcher.logError("Falha marcarFalha NUFILA=" + p.nufila, ex);
            }
        }
    }

    /**
     * Seta propriedades JapeSession exigidas pelos listeners Sankhya em contexto background.
     * CabecalhoNotaListener.beforeInsert chama getRequiredProperty("usuario_logado").
     *
     * Bloco EDI em ConfirmacaoNotaHelper.confirmaNota nao tem flag por property -- so pula
     * se TipoOperacao for NFE/NFSE/CTE/NFCom (bytecode). Pedido de Compra entra sempre.
     * NPE vem de ServiceContext.getCurrent()=null no Lancador -- precisa mock ServiceContext
     * (depende de JAR com br.com.sankhya.ws.ServiceContext que nao esta nos libs).
     */
    private void prepararContextoSankhya(BigDecimal codusu) {
        JapeSessionContext.putProperty("usuario_logado", codusu);
        registrarServiceContextMock();
    }

    /**
     * Cria ServiceContext mock (httpRequest=null) e registra no ThreadLocal interno
     * via reflection. Necessario pq ServiceContext.getCurrent() retorna null em Lancador
     * e ConfirmacaoNotaHelper.confirmaNota chama CACHelper.gerarArquivoEDI(ctx) que
     * dereferencia null -> NPE.
     *
     * Construtor ServiceContext(HttpServletRequest) aceita null e ja inicializa
     * bodyElement = new Element("responseBody"), evitando o NPE em getBodyElement().
     */
    private void registrarServiceContextMock() {
        try {
            Constructor<?>[] ctors = ServiceContext.class.getDeclaredConstructors();
            Object mock = null;
            for (Constructor<?> c : ctors) {
                if (c.getParameterCount() == 1) {
                    c.setAccessible(true);
                    mock = c.newInstance(new Object[]{ null });
                    break;
                }
            }
            if (mock == null) {
                TLogCatcher.logError("Construtor 1-arg de ServiceContext nao encontrado", new IllegalStateException());
                return;
            }
            Field field = ServiceContext.class.getDeclaredField("current");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<Object> tl = (ThreadLocal<Object>) field.get(null);
            tl.set(mock);
        } catch (Exception e) {
            TLogCatcher.logError("Falha registrarServiceContextMock", e);
        }
    }

    private void limparServiceContextMock() {
        try {
            Field field = ServiceContext.class.getDeclaredField("current");
            field.setAccessible(true);
            ((ThreadLocal<?>) field.get(null)).set(null);
        } catch (Exception ignored) {}
    }

    /**
     * Se gerarArquivoEDI falhou apos confirmaNota, a nota Matriz pode ja existir.
     * Procura nota nao processada (STATUSNOTA in L/A) vinculada ao contrato.
     */
    private BigDecimal tentarRecuperarNotaMatriz(JdbcWrapper jdbc, BigDecimal numContrato) {
        br.com.sankhya.jape.sql.NativeSql sql = new br.com.sankhya.jape.sql.NativeSql(jdbc);
        java.sql.ResultSet rs;
        try {
            sql.appendSql(
                "SELECT MAX(NUNOTA) AS NUNOTA " +
                "  FROM TGFCAB " +
                " WHERE NUMCONTRATO = ? AND CODEMP = 1 AND CODTIPOPER = 3006"
            );
            sql.addParameter(numContrato);
            rs = sql.executeQuery();
            if (rs != null && rs.next()) {
                return rs.getBigDecimal("NUNOTA");
            }
        } catch (Exception e) {
            TLogCatcher.logError("Falha tentarRecuperarNotaMatriz NUMCONTRATO=" + numContrato, e);
        } finally {
            br.com.sankhya.jape.sql.NativeSql.releaseResources(sql);
        }
        return null;
    }
}
