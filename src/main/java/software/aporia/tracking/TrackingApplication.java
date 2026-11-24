package software.aporia.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada Spring Boot do serviço de rastreamento. A varredura de componentes encontra as configurações deste
 * pacote e dos subpacotes. Os perfis ativos determinam quais componentes são criados: {@code gateway} habilita o
 * atendimento TCP e {@code worker} habilita o consumidor de pacotes. A partida e a parada desses componentes são
 * delegadas aos respectivos lifecycles.
 */
@SpringBootApplication
public class TrackingApplication {
    /**
     * Cria e inicia o contexto Spring, que configura os beans e seus ciclos de vida.
     *
     * @param args argumentos repassados ao Spring Boot, incluindo opções de configuração e perfis
     */
    public static void main(String[] args) {
        // O contexto assume a configuração e o encerramento; não é necessário iniciar as threads aqui.
        SpringApplication.run(TrackingApplication.class, args);
    }
}
