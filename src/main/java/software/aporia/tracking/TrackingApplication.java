package software.aporia.tracking;

import org.jspecify.annotations.NullMarked;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada Spring Boot do serviço de rastreamento. A varredura de componentes encontra as
 * configurações deste pacote e dos subpacotes. Os perfis ativos determinam quais componentes são criados:
 * {@code gateway} habilita o atendimento TCP, {@code worker} habilita o consumidor de pacotes e {@code
 * monitor} habilita a coleta de métricas das filas. A partida e a parada desses componentes são delegadas aos
 * respectivos lifecycles.
 *
 * <p>O perfil padrão é {@code none}, definido em {@code application.yml}; sem um perfil operacional
 * explícito, o contexto sobe sem listener TCP, sem consumidor e sem agendador de métricas.</p>
 */
@SpringBootApplication
public class TrackingApplication {
    /**
     * Cria e inicia o contexto Spring, que configura os beans e seus ciclos de vida.
     *
     * @param args argumentos repassados ao Spring Boot, incluindo opções de configuração e perfis
     */
    @NullMarked
    public static void main(String[] args) {
        // O contexto assume a configuração e o encerramento; não é necessário iniciar as threads aqui.
        SpringApplication.run(TrackingApplication.class, args);
    }
}
