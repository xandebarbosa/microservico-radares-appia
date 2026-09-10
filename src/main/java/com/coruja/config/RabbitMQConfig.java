package com.coruja.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange.name:radares_exchange}")
    private String exchangeName;

    // Declara a Exchange (equivalente ao mp.messaging.outgoing...exchange.name)
    @Bean
    public DirectExchange radaresExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    // Configura o conversor para transformar objetos Java em JSON nativamente
    @Bean
    @SuppressWarnings({"deprecation", "removal"})
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // Aplica o conversor JSON ao RabbitTemplate
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
