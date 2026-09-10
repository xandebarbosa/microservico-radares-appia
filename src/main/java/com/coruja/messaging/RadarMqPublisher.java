package com.coruja.messaging;

import com.coruja.entity.Radars;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

@Component
@Slf4j
public class RadarMqPublisher {

    private final RabbitTemplate rabbitTemplate;
    @Value("${rabbitmq.exchange.name:radares_exchange}")
    private String exchange;

    @Value("${rabbitmq.routing.key:radares.appia}")
    private String routingKey;

    // Injeção de dependência via construtor com valores do application.properties
    public RadarMqPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Envia uma leitura de radar para o RabbitMQ de forma resiliente.
     * Falhas de envio são logadas mas NÃO lançam exceção (não deve
     * interromper o fluxo de persistência).
     */
    public void publicar(Radars radar) {

        if (!isValido(radar)) {
            log.warn(
                    "Dados incompletos para placa [{}] — mensagem não enviada.",
                    radar != null ? radar.getPlaca() : null
            );
            return;
        }

        String mensagem = formatarMensagem(radar);

        try {

            rabbitTemplate.convertAndSend(
                    exchange,
                    routingKey,
                    mensagem
            );

            log.info(
                    "Mensagem enviada ao RabbitMQ: {}",
                    mensagem
            );

        } catch (AmqpException e) {
            // AmqpException captura falhas específicas de conexão/envio do Spring AMQP
            log.warn(
                    "Falha ao enviar ao RabbitMQ — Placa: {} | Causa: {}",
                    radar.getPlaca(),
                    e.getMessage());

        } catch (Exception e) {
            log.warn(
                    "Erro inesperado ao enviar ao RabbitMQ — Placa: {} | Causa: {}",
                    radar.getPlaca(),
                    e.getMessage(),
                    e
            );
        }
    }

    private boolean isValido(Radars radar) {

        return radar != null
                && radar.getData()    != null && !radar.getData().isBlank()
                && radar.getHora()    != null && !radar.getHora().isBlank()
                && radar.getPlaca()   != null && !radar.getPlaca().isBlank()
                && radar.getLocal()   != null && !radar.getLocal().isBlank()
                && radar.getSentido() != null && !radar.getSentido().isBlank();
    }

    /**
     * Formato da mensagem:
     * APPIA|data|hora|placa|local|sentido
     */
    private String formatarMensagem(Radars radar) {

        return String.format(
                "APPIA|%s|%s|%s|%s|%s",
                radar.getData(),
                radar.getHora(),
                radar.getPlaca(),
                radar.getLocal(),
                radar.getSentido()
        );
    }
}
