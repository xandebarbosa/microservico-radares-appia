package com.coruja.controllers;

import com.coruja.config.RadarCacheScheduler;
import com.coruja.dto.*;
import com.coruja.entity.Radars;
import com.coruja.service.RadarsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "${cors.origins}")
@RestController
@RequestMapping(value = "/radares")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Radares (Appia)", description = "Endpoints para consulta, mapa e persistência de radares")
public class RadarsController {

    private final RadarsService radarsService;
    private final RadarCacheScheduler radarCacheScheduler;

    // ═══════════════════════════════════════════════════════════════
    //  METADADOS E FILTROS DE RODOVIAS
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Força a atualização manual do cache de rodovias/kms")
    @PostMapping("/rodovias/atualizar-cache")
    public ResponseEntity<Void> atualizarCacheRodovias() {
        radarCacheScheduler.forcarAtualizacao();
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Lista todas as rodovias disponíveis")
    @GetMapping("/rodovias")
    public ResponseEntity<List<RodoviaDTO>> listarRodovias() {
        log.info("🛣️ [APPIA] Listando rodovias");

        List<RodoviaDTO> rodovias = radarsService.listarRodovias();

        if (rodovias.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        log.info("✅ [APPIA] Retornando {} rodovias", rodovias.size());
        return ResponseEntity.ok(rodovias);
    }

    @Operation(summary = "Lista os KMs disponíveis. Pode ser filtrado por rodovia.")
    @GetMapping("/kms")
    public ResponseEntity<List<String>> listarKms(@RequestParam(value = "rodovia", required = false) String rodovia) {
        log.info("📍 [APPIA] Listando KMs da rodovia: {}", rodovia != null ? rodovia : "Todas");

        List<String> kms = radarsService.listarKmsPorRodovia(rodovia);

        if (kms.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        log.info("✅ [APPIA] Retornando {} KMs", kms.size());
        return ResponseEntity.ok(kms);
    }

    @Operation(summary = "Lista os KMs de uma rodovia pelo ID")
    @GetMapping("/rodovias/{rodoviaId}/kms")
    public ResponseEntity<?> listarKmsPorId(@PathVariable Long rodoviaId) {
        String nomeRodovia = radarsService.getRodoviaById(rodoviaId);

        if (nomeRodovia == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", 404, "erro", "Rodovia com ID " + rodoviaId + " não encontrada."));
        }

        log.info("📍 [APPIA] Buscando KMs para rodovia ID={} → '{}'", rodoviaId, nomeRodovia);

        List<String> kms = radarsService.listarKmsPorRodovia(nomeRodovia);

        List<KmRodoviaDTO> resultado = new ArrayList<>();
        for (int i = 0; i < kms.size(); i++) {
            resultado.add(KmRodoviaDTO.builder()
                    .id((long) (i + 1))
                    .valor(kms.get(i))
                    .rodoviaId(rodoviaId)
                    .build());
        }

        if (resultado.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        log.info("✅ [APPIA] Retornando {} KMs (rodoviaId={})", resultado.size(), rodoviaId);
        return ResponseEntity.ok(resultado);
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONSULTAS PRINCIPAIS
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Busca paginada de radares com filtros dinâmicos")
    @GetMapping("/busca-local")
    public ResponseEntity<RadarPageDTO> buscarComFiltros(
            @Parameter(description = "Placa do veículos (exata)")
            @RequestParam(value = "placa", required = false) String placa,

            @Parameter(description = "Nome ou trecho da rodovia")
            @RequestParam(value = "rodovia", required = false) String rodovia,

            @Parameter(description = "Quilômetro exato")
            @RequestParam(value = "km", required = false) String km,

            @Parameter(description = "Sentido da via")
            @RequestParam(value = "sentido", required = false) String sentido,

            @Parameter(description = "Data da passagem (ISO: yyyy-MM-dd)")
            @RequestParam(value = "data", required = false) String dataStr,

            @Parameter(description = "Hora inicial do intervalo (ISO: HH:mm:ss)")
            @RequestParam(value = "horaInicial", required = false) String horaInicialStr,

            @Parameter(description = "Hora final do intervalo (ISO: HH:mm:ss)")
            @RequestParam(value = "horaFinal", required = false) String horaFinalStr,

            @Parameter(description = "Número da página (0-indexed)")
            @RequestParam(value = "page", defaultValue = "0") int page,

            @Parameter(description = "Tamanho da página")
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        LocalDate data = parseDate(dataStr);
        LocalTime horaInicial = parseTime(horaInicialStr);
        LocalTime horaFinal = parseTime(horaFinalStr);

        RadarPageDTO result = radarsService.buscarComFiltros(
                placa, rodovia, km, sentido, data, horaInicial, horaFinal, page, size
        );

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Busca os radares capturados mais recentemente")
    @GetMapping("/ultimos")
    public ResponseEntity<List<RadarsDTO>> buscarUltimos(
            @RequestParam(value = "limite", defaultValue = "20") int limite
    ) {
        log.info("⏳ [APPIA] Buscando os últimos {} radares", limite);
        List<RadarsDTO> ultimos = radarsService.buscarUltimos(limite);

        if (ultimos == null || ultimos.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(ultimos);
    }

    @Operation(summary = "Busca o histórico paginado de uma placa específica")
    @GetMapping("/busca-placa")
    public ResponseEntity<RadarPageDTO> buscaPorPlaca(
            @RequestParam(value = "placa") String placa,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        log.info("🔎 [APPIA] Buscando histórico para a placa: {}", placa);
        RadarPageDTO result = radarsService.buscarPorPlaca(placa, page, size);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Gera um resumo consolidado das passagens de uma placa")
    @GetMapping("/placa/{placa}/resumo")
    public ResponseEntity<PlacaResumoDTO> resumoPorPlaca(@PathVariable String placa) {
        RadarPageDTO pageDTO = radarsService.buscarPorPlaca(placa, 0, 1);
        long totalRegistros = pageDTO.getPageMetadata().getTotalElements();

        if (totalRegistros == 0) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(radarsService.montarResumo(placa, totalRegistros));
    }

    // ═══════════════════════════════════════════════════════════════
    //  GEOGRAFIA E MAPA
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Retorna localizações de radares dentro de um raio geográfico")
    @GetMapping("/geo-search")
    public ResponseEntity<RadarPageDTO> buscarPorLocalizacao(
            @RequestParam("latitude") Double latitude,
            @RequestParam("longitude") Double longitude,
            @RequestParam(value = "raio", required = false, defaultValue = "15000.0") Double raioMetros,
            @RequestParam(value = "data", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(value = "horaInicial", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(value = "horaFinal", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        log.info("🌍 [APPIA] Busca geoespacial | Lat: {} | Long: {} | Raio: {}m", latitude, longitude, raioMetros);

        RadarPageDTO result = radarsService.buscaGeografica(
                latitude, longitude, raioMetros, data, horaInicial, horaFinal, page, size
        );

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Retorna todas as localizações únicas para popular o mapa global")
    @GetMapping("/all-locations")
    public ResponseEntity<List<RadarLocationDTO>> getRadarLocations() {
        log.info("🗺️ [APPIA] Buscando todas as localizações estruturadas");

        List<RadarLocationDTO> locations = radarsService.carregarCoordenadasAgrupadas();

        if (locations.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        log.info("✅ [APPIA] Retornando {} localizações", locations.size());
        return ResponseEntity.ok(locations);
    }

    // ═══════════════════════════════════════════════════════════════
    //  PERSISTÊNCIA (RABBITMQ E MONGODB)
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Salva uma lista de radares no banco e publica no RabbitMQ")
    @PostMapping("")
    public ResponseEntity<Void> salvarRadares(@RequestBody List<Radars> radars) {
        log.info("📥 [APPIA] Recebendo lote de {} radares para persistência", radars.size());
        radarsService.salvarRadares(radars);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  MÉTODOS PRIVADOS AUXILIARES
    // ═══════════════════════════════════════════════════════════════

    private LocalDate parseDate(String str) {
        if (str == null || str.isBlank()) return null;
        try { return LocalDate.parse(str); }
        catch (Exception e) { return null; }
    }

    private LocalTime parseTime(String str) {
        if (str == null || str.isBlank()) return null;
        try { return LocalTime.parse(str); }
        catch (Exception e) { return null; }
    }
}
