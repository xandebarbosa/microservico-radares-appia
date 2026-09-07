package com.coruja.controllers;

import com.coruja.dto.*;
import com.coruja.entity.Radars;
import com.coruja.service.RadarsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "${cors.origins}")
@RestController
@RequestMapping(value = "/radares")
@RequiredArgsConstructor // O Lombok cria o construtor injetando o RadarsService automaticamente
@Slf4j
@Tag(name = "Radares (Appia)", description = "Endpoints para consulta, mapa e persistência de radares")
public class RadarsController {

    private final RadarsService radarsService;


    @Operation(summary = "Lista todas as rodovias disponíveis")
    @GetMapping("/rodovias")
    public ResponseEntity<List<RodoviaDTO>> listarRodovias() {
        log.info("🛣️ [APPIA] Listando rodovias");
            return ResponseEntity.ok(radarsService.listarRodovias());
    }

    @Operation(summary = "Lista os KMs pertencentes a uma rodovia específica")
    @GetMapping("/rodovas/{rodoviaId}/kms")
    public ResponseEntity<List<KmRodoviaDTO>> listarKmRodovias(@PathVariable Long rodoviaId) {
        log.info("📍 [Cart] Listando KMs da rodovia ID: {}", rodoviaId);
        List<KmRodoviaDTO> kmRodoviaDTOS = radarsService.listarKmsPorRodovia(rodoviaId);
        log.info("✅ [Cart] Retornando {} KMs",  kmRodoviaDTOS.size());
        return ResponseEntity.ok(kmRodoviaDTOS);
    }

    /**
     * ✅ BUSCA POR FILTROS (Local)
     * Endpoint para consulta operacional (Dia, Rodovia, Km, Hora).
     * 'Data' é obrigatória para performance (cai na partição correta).
     */
    @Operation(summary = "Busca paginada de radares com filtros dinâmicos")
    @GetMapping("/busca-local")
    public ResponseEntity<RadarPageDTO> buscarComFiltros(
            @RequestParam(required = false) String placa,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
            ){
        RadarPageDTO result = radarsService.buscarComFiltros(
                placa, rodovia, km, sentido, data, horaInicial, horaFinal, page, size
        );
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Busca os radares capturados mais recentemente")
    @GetMapping("/ultimos")
    public Response buscarUltimos(
            @RequestParam(defaultValue = "20") int limite
    ) {
        List<RadarsDTO> ultimos = radarsService.buscarUltimos(limite);
        if (ultimos == null || ultimos.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", 404, "erro", "Nenhum registro encontrado."))
                    .build();
        }
        log.info("Ultimos radares carregados da concessionária APPIA {}", ultimos.size());
        return Response.ok(ultimos).build();
    }

    /**
     * ✅ BUSCA POR PLACA
     * Endpoint específico e otimizado para histórico completo de uma placa.
     */
    @Operation(summary = "Busca o histórico paginado de uma placa específica")
    @GetMapping("/busca-placa")
    public ResponseEntity<RadarPageDTO> buscaPorPlaca(
                @PathVariable String placa,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "20") int size
            ) {
                log.info("Buscando por placa: {}", placa);
                return ResponseEntity.ok(radarsService.buscarPorPlaca(placa, page, size));
    }

    @Operation(summary = "Gera um resumo consolidado das passagens de uma placa")
    @GetMapping("/placa/{placa}/resumo")
    public ResponseEntity<PlacaResumoDTO> resumoPorPlaca(@PathVariable String placa) {
        // Solução Arquitetural: O método de resumo no service pede o "total" de passagens.
        // Em vez de obrigar o frontend a mandar esse número, fazemos uma busca super leve (size=1)
        // apenas para aproveitar o count() ultra-rápido do MongoDB e repassamos o total.
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
    @Operation(summary = "Retorna todas as localizações únicas de radares para popular o mapa")
    @GetMapping("/geo-search")
    public ResponseEntity<RadarPageDTO> buscarPorLocalizacao(
            @RequestParam("latitude") Double latitude,
            @RequestParam("longitude") Double longitude,
            @RequestParam(value = "raio", required = false, defaultValue = "15000.0") Double raioMetros,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        RadarPageDTO result = radarsService.buscaGeografica(
                latitude, longitude, raioMetros, data, horaInicial, horaFinal, page, size
        );
        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════════
    //  PERSISTÊNCIA (RABBITMQ E MONGODB)
    // ═══════════════════════════════════════════════════════════════
    @Operation(summary = "Salva uma lista de radares no banco e publica no RabbitMQ")
    @PostMapping("")
    public ResponseEntity<Void> salvarRadares(List<Radars> radars) {
        radarsService.salvarRadares(radars);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ==================================================================================
    // 3. COMPATIBILIDADE / LEGADO (MAPA)
    // ==================================================================================
    /*@GetMapping("/all-locations")
    public ResponseEntity<List<LocalizacaoRadarProjection>> getRadarLocations() {
        log.info("🗺️ [APPIA] Buscando todas as localizações");

        List<LocalizacaoRadarProjection> locations = radarsService.listarTodasLocalizacoes();

        log.info("✅ [APPIA] Retornando {} localizações",  locations.size());

        return ResponseEntity.of(locations);
    }*/

}
