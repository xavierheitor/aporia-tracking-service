package software.aporia.tracking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Define a política HTTP dos endpoints de gerenciamento quando um perfil operacional está ativo.
 *
 * <p>
 * Os endpoints de health e das probes são públicos para o orquestrador. Prometheus e info exigem autenticação básica
 * com o papel {@code MONITORING}; qualquer outra rota é negada. As credenciais vêm de {@code spring.security.user} em
 * {@code application.yml} e devem ser fornecidas por variáveis de ambiente.
 * </p>
 *
 * <p>
 * A cadeia é criada somente nos perfis {@code gateway}, {@code worker} e {@code monitor}. Ela não substitui a
 * autorização de comandos do gateway, que é validada no fluxo TCP.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile({ "gateway", "worker", "monitor" })
public class SecurityConfiguration {

        /**
         * Cria a cadeia de filtros que protege os endpoints expostos.
         *
         * @param http construtor de segurança HTTP fornecido pelo Spring
         * @return cadeia configurada, aplicada apenas aos perfis operacionais
         * @throws Exception se a configuração da cadeia falhar
         */
        @Bean
        public SecurityFilterChain securityFilterChain(
                        HttpSecurity http) throws Exception {

                // Health e probes ficam públicos; métricas e info exigem MONITORING; o restante é negado.
                return http
                                .authorizeHttpRequests(
                                                auth -> auth
                                                                .requestMatchers(
                                                                                "/actuator/health",
                                                                                "/actuator/health/liveness",
                                                                                "/actuator/health/readiness")
                                                                .permitAll()
                                                                .requestMatchers(
                                                                                "/actuator/prometheus",
                                                                                "/actuator/info")
                                                                .hasRole("MONITORING")
                                                                .anyRequest()
                                                                .denyAll())
                                .httpBasic(Customizer.withDefaults())
                                .build();
        }
}