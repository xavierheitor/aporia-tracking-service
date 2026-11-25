package software.aporia.tracking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile({ "gateway", "worker", "monitor" })
public class SecurityConfiguration {

        @Bean
        public SecurityFilterChain securityFilterChain(
                        HttpSecurity http) throws Exception {

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