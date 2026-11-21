package br.gov.serpro.rtc.worker;

import java.util.UUID;

/**
 * Pedido lido da fila {@code rtc.basecalculo.entrada}: base unificada, base da NFS-e ou base do Imposto
 * Seletivo sobre mercadorias.
 */
public sealed interface PedidoBaseCalculo permits BaseCalculoEntrada, BaseCalculoNfseEntrada,
        BaseCalculoISMercadoriasEntrada {

    TipoBaseCalculo tipo();

    UUID correlationId();

    String cnpj();

    Object payload();

    /** Cópia com o CNPJ já validado do envelope AMQP. */
    PedidoBaseCalculo comCnpj(String cnpjValidado);
}
