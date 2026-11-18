/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.RequiredArgsConstructor;

/**
 * Classe principal que inicializa a aplicação Spring Boot da Calculadora de
 * Tributos.
 */
@RequiredArgsConstructor
@SpringBootApplication
public class CalculadoraTributoApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(CalculadoraTributoApplication.class, args);
    }
}
