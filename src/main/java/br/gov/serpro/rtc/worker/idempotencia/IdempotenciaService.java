package br.gov.serpro.rtc.worker.idempotencia;

import java.util.UUID;

/**
 * Controle de idempotência por empresa e {@code correlationId}: o mesmo id usado por
 * empresas diferentes não colide.
 *
 * <p>O cálculo é determinístico e não tem efeitos colaterais, então este controle
 * evita <em>trabalho</em> duplicado; a garantia final contra duplicatas é o
 * consumidor da Fila de Saída gravar por chave única ({@code idNota}, {@code correlationId}).
 */
public interface IdempotenciaService {

    enum Aquisicao {
        /** A nota pode ser processada por este worker. */
        ADQUIRIDA,
        /** A nota já foi processada e o resultado já foi publicado. */
        JA_CONCLUIDA,
        /**
         * Outra mensagem com o mesmo id está em processamento: é um envio duplicado
         * do produtor. A mensagem original segue no broker até ser confirmada.
         */
        EM_ANDAMENTO,
        /** O mesmo correlationId chegou com conteúdo diferente. */
        CONFLITO
    }

    /**
     * @param cnpj          empresa dona da nota
     * @param correlationId identificador da mensagem
     * @param hashConteudo  hash do corpo da mensagem, para detectar reuso indevido do id
     * @param reentrega     {@code true} se o broker está reentregando a mensagem. O
     *                      RabbitMQ não entrega a mesma mensagem a dois consumidores ao
     *                      mesmo tempo; então, numa reentrega, um lease ainda ativo é de
     *                      um worker que caiu, e este worker assume a nota.
     */
    Aquisicao adquirir(String cnpj, UUID correlationId, String hashConteudo, boolean reentrega);

    /** Marca a nota como concluída (resultado publicado e confirmado pelo broker). */
    void concluir(String cnpj, UUID correlationId, String hashConteudo);

    /** Libera a nota para nova tentativa (falha antes da publicação). */
    void liberar(String cnpj, UUID correlationId);
}
