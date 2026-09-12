package com.coruja.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;


import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * Instância isolada do ObjectMapper para o Redis.
     * Arquiteturalmente desenhado para evitar vulnerabilidades de Desserialização no Spring MVC.
     */
    private ObjectMapper createRedisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Tipagem polimórfica ativada APENAS para o contexto do Redis
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        return mapper;
    }

    /**
     * Configuração base do cache.
     * Supressão do alerta de depreciação aplicada intencionalmente até a consolidação
     * global do Jackson 3 no ecosssistema Spring.
     */
    @Bean
    @SuppressWarnings({"deprecation", "removal"})
    public RedisCacheConfiguration cacheConfiguration() {
        ObjectMapper redisMapper = createRedisObjectMapper();

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(redisMapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()
                        )
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer)
                );
    }

    /**
     * ✅ CUSTOMIZAÇÃO POR CACHE (Mapeando as consultas do Harpia/MongoDB)
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
            RedisCacheConfiguration defaultConfig) {

        return builder -> {
            Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

            // 1. Relacionado à query dinâmica com paginação (curto - dados mudam rápido)
            cacheConfigs.put("radars-search-appia",
                    defaultConfig.entryTtl(Duration.ofMinutes(5)));

            // 2. Relacionado ao índice PLACA_1_DATA_1_HORA_1 (médio)
            cacheConfigs.put("radars-placa-appia",
                    defaultConfig.entryTtl(Duration.ofMinutes(10)));

            // 3. Relacionado ao índice DATA_1_LOCAL_1 (Busca geográfica do Mapa)
            cacheConfigs.put("radars-geo-appia",
                    defaultConfig.entryTtl(Duration.ofMinutes(15)));

            // 4. Cache de filtros/metadata (agora atualizado 1x/mês via job
            // agendado — RadarCacheScheduler — em vez de depender do TTL.
            // TTL aqui é só rede de segurança, caso o job falhe.
            cacheConfigs.put("opcoes-filtro-appia",
                    defaultConfig.entryTtl(Duration.ofHours(24)));

            // 5. Lista de rodovias distintas (endpoint /radares/rodovias).
            // Nome alinhado com @Cacheable("todas-rodovias-appia") no
            // RadarsService — antes esse cache nem tinha entrada aqui e
            // caía no TTL padrão de 10 min.
            cacheConfigs.put("todas-rodovias-appia",
                    defaultConfig.entryTtl(Duration.ofHours(24)));

            // 6. KMs por rodovia (endpoint /radares/kms). Nome corrigido
            // para bater com @Cacheable("todos-kms-appia") — antes estava
            // como "kms-rodovia-appia" aqui e nunca era aplicado.
            cacheConfigs.put("todos-kms-appia",
                    defaultConfig.entryTtl(Duration.ofHours(24)));

            // 7. Cache das coordenadas JSON do mapa (muito longo - raramente mudam)
            cacheConfigs.put("mapa-radares-appia",
                    defaultConfig.entryTtl(Duration.ofHours(24)));

            // 8. Cache de busca geográfica (raio ao redor de um ponto).
            // Nome alinhado com @Cacheable("locais-radares-bff") — antes
            // estava como "locais-radares-bff-appia" aqui e nunca era
            // aplicado (caía no TTL padrão de 10 min do cache default).
            cacheConfigs.put("locais-radares-bff",
                    defaultConfig.entryTtl(Duration.ofHours(24)));

            builder.withInitialCacheConfigurations(cacheConfigs);
        };
    }

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedisCacheConfiguration cacheConfiguration) {

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration)
                .transactionAware() // ✅ Importante para consistência
                .build();
    }
}

