package com.coruja.config;

import com.coruja.service.RadarsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mantém o cache de rodovias/KMs atualizado sem depender do TTL do Redis
 * expirar no meio de uma requisição de usuário.
 *
 * Os valores de rodovia/km praticamente não mudam de um dia para o outro,
 * então não faz sentido recalcular a cada TTL curto — o refresh acontece
 * 1x por mês, em background, fora do horário de pico. Fica em uma classe
 * própria (em vez de um @Scheduled dentro do RadarsService) para garantir
 * que a chamada aos métodos @Cacheable passe pelo proxy do Spring — uma
 * chamada interna (this.metodo()) dentro da própria classe do serviço
 * bypassa o proxy e o @Cacheable seria ignorado.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RadarCacheScheduler {

    private final RadarsService radarsService;
    private final CacheManager cacheManager;

    private static final List<String> CACHES_RODOVIAS_KMS = List.of(
            "todas-rodovias-appia",
            "todos-kms-appia",
            "opcoes-filtro-appia"
    );

    /**
     * Todo dia 1 de cada mês, às 03h. cron: seg min hora diaDoMes mes diaDaSemana
     */
    @Scheduled(cron = "0 0 3 1 * *")
    public void atualizarCacheMensal() {
        log.info("🗓️ Job mensal: atualizando cache de rodovias/kms (Appia)...");
        try {
            limparCaches();
            // Repopula proativamente — fora do horário de pico, então o
            // próximo usuário do dia já encontra o cache quente.
            radarsService.listarRodovias();
            radarsService.listarKmsPorRodovia(null);
            radarsService.listarKmsAgrupados();
            log.info("✅ Cache de rodovias/kms (Appia) atualizado com sucesso.");
        } catch (Exception e) {
            log.error("❌ Falha ao atualizar cache de rodovias/kms (Appia) no job mensal: {}", e.getMessage(), e);
        }
    }

    /**
     * Força a atualização fora do ciclo mensal — útil após uma carga em
     * lote de dados novos, quando não se quer esperar o próximo dia 1.
     */
    public void forcarAtualizacao() {
        log.info("🔄 Atualização manual do cache de rodovias/kms (Appia) solicitada.");
        atualizarCacheMensal();
    }

    private void limparCaches() {
        for (String nomeCache : CACHES_RODOVIAS_KMS) {
            Cache cache = cacheManager.getCache(nomeCache);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
