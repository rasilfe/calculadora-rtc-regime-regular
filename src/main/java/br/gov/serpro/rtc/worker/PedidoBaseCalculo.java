package br.gov.serpro.rtc.worker;

import java.util.UUID;

/**
 * Pedido lido da fila {@code rtc.basecalculo.entrada}: base unificada ou base da NFS-e.
 */
public sealed interface PedidoBaseCalculo permits BaseCalculoEntrada, BaseCalculoNfseEntrada {

    TipoBaseCalculo tipo();

    UUID correlationId();

    String cnpj();

    Object payload();

    /** Cópia com o CNPJ já validado do envelope AMQP. */
    PedidoBaseCalculo comCnpj(String cnpjValidado);
}
