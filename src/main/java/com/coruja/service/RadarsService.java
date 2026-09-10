package com.coruja.service;

import com.coruja.dto.*;
import com.coruja.entity.Radars;
import com.coruja.messaging.RadarMqPublisher;
import com.coruja.repository.RadarsRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Serviço principal de radares do microserviço Appia.
 *
 * Responsabilidades:
 *  - Consulta com filtros dinâmicos no MongoDB (via MongoTemplate e Criteria)
 *  - Caching distribuído via Redis (@Cacheable)
 *  - Conversão de documentos para DTO
 *  - Cálculos geoespaciais (Haversine)
 */
@Slf4j
@Service
public class RadarsService {

    private static final DateTimeFormatter MONGO_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            MONGO_DATE_FMT,
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );
    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm")
    );

    // Regex para extrair "SP 310" e "240.40" de "Rodovia: SP 310 KM:240.40"
    private static final Pattern LOCAL_PATTERN = Pattern.compile("(?i)Rodovia:\\s*(.*?)\\s*KM:\\s*([0-9]+(?:[.,][0-9]+)?)");

    // Janela de dias para usar o índice por DATA em agregações de metadados
    private static final int JANELA_DIAS_LOCAL = 7;
    private static final String COLLECTION_NAME = "APPIA";

    private final RadarsRepository radarsRepository;
    private final RadarMqPublisher mqPublisher;
    private final MongoTemplate mongoTemplate;

    public RadarsService(RadarsRepository radarsRepository, RadarMqPublisher mqPublisher, MongoTemplate mongoTemplate) {
        this.radarsRepository = radarsRepository;
        this.mqPublisher = mqPublisher;
        this.mongoTemplate = mongoTemplate;
    }

    // ─── Startup Assíncrono ───────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void inicializarCacheAssincrono() {
        CompletableFuture.runAsync(() -> {
            log.info("🔥 Iniciando pré-aquecimento de cache no Redis (background)...");
            try {
                listarKmsAgrupados();
                carregarCoordenadasAgrupadas();
                log.info("✅ Cache pré-aquecido com sucesso.");
            } catch (Exception e) {
                log.error("⚠️ Falha no pré-aquecimento do cache: {}", e.getMessage(), e);
            }
        });
    }

    // ─── Utilitário de Extração (Record Java 16+) ─────────────────────────────

    private record RodoviaKm(String rodovia, String km) {}

    private RodoviaKm extrairRodoviaKm(String localBruto) {
        if (localBruto == null || localBruto.isBlank()) return new RodoviaKm("", "");
        Matcher matcher = LOCAL_PATTERN.matcher(localBruto);
        if (matcher.find()) {
            return new RodoviaKm(matcher.group(1).trim(), matcher.group(2).trim());
        }
        return new RodoviaKm(localBruto.trim(), "");
    }

    // ═══════════════════════════════════════════════════════════════
    //  EXTRAÇÃO GLOBAL DE RODOVIAS E KMS (SEM FILTRO DE DATA)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extrai todas as rodovias distintas de toda a base de dados.
     */
    @Cacheable(value = "todas-rodovias-appia")
    public List<String> listarRodovias() {
        log.info("🔍 Extraindo todas as rodovias distintas do banco de dados...");

        List<String> locaisUnicos = mongoTemplate.findDistinct(new Query(), "LOCAL", COLLECTION_NAME, String.class);

        return locaisUnicos.stream()
                .filter(local -> local != null && !local.isBlank())
                .map(this::extrairRodoviaKm)
                .map(RodoviaKm::rodovia)
                .filter(rodovia -> !rodovia.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * Extrai todos os KMs distintos.
     * Se a rodovia for informada, filtra os KMs apenas daquela rodovia.
     */
    @Cacheable(value = "todos-kms-appia", key = "#rodovia != null ? #rodovia.toUpperCase().trim() : 'TODOS'")
    public List<String> listarKmsPorRodovia(String rodovia) {
        log.info("🔍 Extraindo KMs distintos (Rodovia: {})...", rodovia != null ? rodovia : "Todas");

        Query query = new Query();
        if (rodovia != null && !rodovia.isBlank()) {
            // Aplica filtro usando regex para buscar apenas os "LOCAL" que contêm esta rodovia
            query.addCriteria(Criteria.where("LOCAL").regex("(?i)Rodovia:\\s*" + Pattern.quote(rodovia.trim()) + ".*"));
        }

        List<String> locaisUnicos = mongoTemplate.findDistinct(query, "LOCAL", COLLECTION_NAME, String.class);

        return locaisUnicos.stream()
                .filter(local -> local != null && !local.isBlank())
                .map(this::extrairRodoviaKm)
                .map(RodoviaKm::km)
                .filter(km -> !km.isBlank())
                .distinct()
                // Comparador customizado para ordenar KMs numericamente (ex: 20,500 antes de 100,000)
                .sorted((km1, km2) -> {
                    try {
                        Double val1 = Double.parseDouble(km1.replace(",", "."));
                        Double val2 = Double.parseDouble(km2.replace(",", "."));
                        return val1.compareTo(val2);
                    } catch (NumberFormatException e) {
                        return km1.compareToIgnoreCase(km2); // Fallback caso o KM seja string corrompida
                    }
                })
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════
    //  OPÇÕES DE FILTRO (AGREGAÇÃO DINÂMICA VIA REDIS)
    // ═══════════════════════════════════════════════════════════════

    @Cacheable(value = "opcoes-filtro-appia")
    public Map<String, List<String>> listarKmsAgrupados() {
        log.info("Consultando Locais via aggregation (janela de {} dias)...", JANELA_DIAS_LOCAL);
        long inicio = System.currentTimeMillis();

        List<String> datasRecentes = buildDateRange(JANELA_DIAS_LOCAL);

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("DATA").in(datasRecentes)),
                Aggregation.group("LOCAL"),
                Aggregation.match(Criteria.where("_id").ne(null)),
                Aggregation.sort(Sort.Direction.ASC, "_id")
        );

        AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, COLLECTION_NAME, Document.class);

        Map<String, Set<String>> kmsPorRodoviaTemp = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (Document doc : results.getMappedResults()) {
            RodoviaKm rk = extrairRodoviaKm(doc.getString("_id"));
            if (!rk.rodovia().isBlank()) {
                kmsPorRodoviaTemp.computeIfAbsent(rk.rodovia(), k -> new TreeSet<>()).add(rk.km());
            }
        }

        Map<String, List<String>> processados = new LinkedHashMap<>();
        kmsPorRodoviaTemp.forEach((rodovia, kms) -> processados.put(rodovia, new ArrayList<>(kms)));

        log.info("Agregação finalizada em {}ms — {} LOCALs distintos extraídos.",
                System.currentTimeMillis() - inicio, results.getMappedResults().size());

        return processados;
    }

    // ═══════════════════════════════════════════════════════════════
    //  GEOGRAFIA E MAPEAMENTO DTO
    // ═══════════════════════════════════════════════════════════════

    @Cacheable(value = "mapa-radares-appia")
    public List<RadarLocationDTO> carregarCoordenadasAgrupadas() {
        log.info("🗺️ Extraindo coordenadas únicas dos radares via Aggregation...");
        long inicio = System.currentTimeMillis();

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("LATITUDE").exists(true).ne(null).ne("")
                        .and("LONGITUDE").exists(true).ne(null).ne("")),
                Aggregation.group("LOCAL")
                        .first("LATITUDE").as("latitude")
                        .first("LONGITUDE").as("longitude"),
                Aggregation.sort(Sort.Direction.ASC, "_id")
        );

        AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, COLLECTION_NAME, Document.class);
        List<RadarLocationDTO> locations = new ArrayList<>();

        for (Document doc : results.getMappedResults()) {
            String local = doc.getString("_id");
            if (local == null) continue;

            RodoviaKm rk = extrairRodoviaKm(local);

            RadarLocationDTO dto = new RadarLocationDTO();
            dto.setId((long) Math.abs(local.hashCode()));
            dto.setConcessionaria("APPIA");
            dto.setRodovia(rk.rodovia());
            dto.setKm(rk.km());
            dto.setSentido("Ambos");

            try {
                // Trata a vírgula vinda do MongoDB e converte para Double
                dto.setLatitude(Double.parseDouble(doc.getString("latitude").replace(",", ".")));
                dto.setLongitude(Double.parseDouble(doc.getString("longitude").replace(",", ".")));
                locations.add(dto);
            } catch (NumberFormatException ignored) {}
        }

        log.info("Extração finalizada em {}ms — {} localizações consolidadas.", System.currentTimeMillis() - inicio, locations.size());
        return locations;
    }

    // ═══════════════════════════════════════════════════════════════
    //  BUSCA GEOGRÁFICA COM RECORTE TEMPORAL
    // ═══════════════════════════════════════════════════════════════

    @Cacheable(
            value = "locais-radares-bff",
            key = "{#latCentro, #lngCentro, #raioMetros, #data, #horaInicial, #horaFinal, #page, #size}"
    )
    public RadarPageDTO buscaGeografica(Double latCentro, Double lngCentro, Double raioMetros,
                                        LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
                                        int page, int size) {

        double raio = (raioMetros != null) ? raioMetros : 15000.0;
        List<String> locaisNoRaio = new ArrayList<>();

        for (RadarLocationDTO coord : carregarCoordenadasAgrupadas()) {
            if (coord.getLatitude() == null || coord.getLongitude() == null) continue;

            double distancia = calcularDistanciaHaversine(latCentro, lngCentro, coord.getLatitude(), coord.getLongitude());
            if (distancia <= raio) {
                locaisNoRaio.add("RODOVIA: " + coord.getRodovia() + " KM:" + coord.getKm());
            }
        }

        if (locaisNoRaio.isEmpty()) {
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(page, size, 0, 0));
        }

        Query query = buildQuery(null, null, null, null, data, horaInicial, horaFinal);
        query.addCriteria(Criteria.where("LOCAL").in(locaisNoRaio));

        long totalElements = mongoTemplate.count(query, Radars.class);

        // ORDENAÇÃO CRONOLÓGICA REAL UTILIZANDO O TIMESTAMP DO MONGODB
        query.with(Sort.by(Sort.Direction.DESC, "CRIADOEM"));

        if (size > 0) query.with(PageRequest.of(page, size));

        List<RadarsDTO> content = mongoTemplate.find(query, Radars.class).stream()
                .map(this::converterParaDTO)
                .peek(dto -> injetarCoordenadas(dto, carregarCoordenadasAgrupadas()))
                .collect(Collectors.toList());

        return new RadarPageDTO(content, new PageMetadata(page, size, totalElements, size == 0 ? 0 : (int) Math.ceil((double) totalElements / size)));
    }

    private void injetarCoordenadas(RadarsDTO dto, List<RadarLocationDTO> coords) {
        coords.stream()
                .filter(c -> c.getKm().equals(dto.getKm()) && c.getRodovia().equalsIgnoreCase(dto.getRodovia()))
                .findFirst()
                .ifPresent(c -> {
                    dto.setLatitude(c.getLatitude());
                    dto.setLongitude(c.getLongitude());
                });
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONSULTA COM FILTROS E PLACA
    // ═══════════════════════════════════════════════════════════════

    @Cacheable(
            value = "radars-search-appia",
            key = "{#placa, #rodovia, #km, #sentido, #data, #horaInicial, #horaFinal, #page, #size}"
    )
    public RadarPageDTO buscarComFiltros(String placa, String rodovia, String km, String sentido,
                                         LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
                                         int page, int size) {
        Query query = buildQuery(placa, rodovia, km, sentido, data, horaInicial, horaFinal);
        return executarBuscaPaginada(query, page, size);
    }

    @Cacheable(value = "radars-placa-appia", key = "#placa != null ? #placa.toUpperCase().trim() + '-' + #page + '-' + #size : 'empty'")
    public RadarPageDTO buscarPorPlaca(String placa, int page, int size) {
        if (placa == null || placa.isBlank()) {
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(page, size, 0, 0));
        }
        Query query = new Query(Criteria.where("PLACA").is(placa.toUpperCase().trim()));
        return executarBuscaPaginada(query, page, size);
    }

    private RadarPageDTO executarBuscaPaginada(Query query, int page, int size) {
        long total = mongoTemplate.count(query, Radars.class);
        if (total == 0) return new RadarPageDTO(new ArrayList<>(), new PageMetadata(page, size, 0, 0));

        // ORDENAÇÃO CRONOLÓGICA REAL UTILIZANDO O TIMESTAMP DO MONGODB
        query.with(Sort.by(Sort.Direction.DESC, "CRIADOEM"));
        if (size > 0) query.with(PageRequest.of(page, size));

        List<RadarsDTO> content = mongoTemplate.find(query, Radars.class).stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new RadarPageDTO(content, new PageMetadata(page, size, total, totalPages));
    }

    public PlacaResumoDTO montarResumo(String placa, long total) {
        Query queryPlaca = new Query(Criteria.where("PLACA").is(placa));

        List<String> locaisUnicos = findDistinct("LOCAL", queryPlaca);
        List<String> rodovias = locaisUnicos.stream()
                .map(this::extrairRodoviaKm)
                .map(RodoviaKm::rodovia)
                .filter(r -> !r.isBlank())
                .distinct()
                .collect(Collectors.toList());

        List<String> sentidos = findDistinct("SENTIDO", queryPlaca);

        // BUSCA SEGURA BASEADA NO TIMESTAMP PARA IDENTIFICAR A PRIMEIRA E A ÚLTIMA PASSAGEM
        Query qPrimeiro = new Query(Criteria.where("PLACA").is(placa)).with(Sort.by(Sort.Direction.ASC, "CRIADOEM")).limit(1);
        Query qUltimo = new Query(Criteria.where("PLACA").is(placa)).with(Sort.by(Sort.Direction.DESC, "CRIADOEM")).limit(1);

        Radars primeiro = mongoTemplate.findOne(qPrimeiro, Radars.class);
        Radars ultimo = mongoTemplate.findOne(qUltimo, Radars.class);

        return PlacaResumoDTO.builder()
                .totalPassagens(total)
                .primeiraPassagemData(primeiro != null ? primeiro.getData() : null)
                .ultimaPassagemData(ultimo != null ? ultimo.getData() : null)
                .rodovias(rodovias)
                .kms(locaisUnicos)
                .sentidos(sentidos)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  SALVAR + PUBLICAR NO RABBITMQ
    // ═══════════════════════════════════════════════════════════════

    public void salvarRadares(List<Radars> radares) {
        if (radares == null || radares.isEmpty()) return;
        radarsRepository.saveAll(radares);
        log.info("{} registros submetidos para persistência.", radares.size());

        radares.forEach(mqPublisher::publicar);
    }

    public List<RadarsDTO> buscarUltimos(int limite) {
        int[] janelas = {7, 30, 90, 365};

        for (int janela : janelas) {
            List<String> datas = buildDateRange(janela);

            // BUSCA ORDENADA UTILIZANDO O TIMESTAMP DO MONGODB
            Query query = new Query(Criteria.where("DATA").in(datas))
                    .with(Sort.by(Sort.Direction.DESC, "CRIADOEM"))
                    .limit(limite);

            List<Radars> resultados = mongoTemplate.find(query, Radars.class, COLLECTION_NAME);

            if (!resultados.isEmpty()) {
                log.info("✅ Últimos {} registros encontrados na janela de {} dias.", resultados.size(), janela);
                return resultados.stream().map(this::converterParaDTO).collect(Collectors.toList());
            }
        }

        log.warn("⚠️ Nenhum registro encontrado em nenhuma janela de datas.");
        return new ArrayList<>();
    }

    // ═══════════════════════════════════════════════════════════════
    //  MÉTODOS PRIVADOS AUXILIARES
    // ═══════════════════════════════════════════════════════════════

    private Query buildQuery(String placa, String rodovia, String km, String sentido,
                             LocalDate data, LocalTime horaInicial, LocalTime horaFinal) {
        Query query = new Query();

        if (data != null) query.addCriteria(Criteria.where("DATA").is(data.format(MONGO_DATE_FMT)));

        if (horaInicial != null || horaFinal != null) {
            Criteria horaCriteria = Criteria.where("HORA");
            if (horaInicial != null) horaCriteria.gte(horaInicial.toString());
            if (horaFinal != null) horaCriteria.lte(horaFinal.toString());
            query.addCriteria(horaCriteria);
        }

        if (placa != null && !placa.isBlank()) {
            query.addCriteria(Criteria.where("PLACA").regex("(?i).*" + Pattern.quote(placa.trim()) + ".*"));
        }

        if (rodovia != null && !rodovia.isBlank()) {
            String regexLocal = (km != null && !km.isBlank())
                    ? "(?i)Rodovia:\\s*" + Pattern.quote(rodovia.trim()) + ".*KM:\\s*" + Pattern.quote(km.trim())
                    : "(?i)Rodovia:\\s*" + Pattern.quote(rodovia.trim()) + ".*";
            query.addCriteria(Criteria.where("LOCAL").regex(regexLocal));
        } else if (km != null && !km.isBlank()) {
            query.addCriteria(Criteria.where("LOCAL").regex("(?i).*KM:\\s*" + Pattern.quote(km.trim()) + ".*"));
        }

        if (sentido != null && !sentido.isBlank()) {
            String s = sentido.trim().toLowerCase();
            query.addCriteria(Criteria.where("SENTIDO").is(s.substring(0, 1).toUpperCase() + s.substring(1)));
        }

        return query;
    }

    private List<String> findDistinct(String field, Query query) {
        query.addCriteria(Criteria.where(field).ne(null));
        return mongoTemplate.findDistinct(query, field, Radars.class, String.class).stream()
                .filter(s -> !s.isBlank())
                .sorted()
                .collect(Collectors.toList());
    }

    private RadarsDTO converterParaDTO(Radars r) {
        RodoviaKm rk = extrairRodoviaKm(r.getLocal());

        LocalDate dataConvertida = LocalDate.now();
        if (r.getData() != null && !r.getData().isBlank()) {
            for (DateTimeFormatter fmt : DATE_FORMATTERS) {
                try {
                    dataConvertida = LocalDate.parse(r.getData(), fmt);
                    break;
                } catch (DateTimeParseException ignored) {}
            }
        }

        LocalTime horaConvertida = LocalTime.MIDNIGHT;
        if (r.getHora() != null && !r.getHora().isBlank()) {
            for (DateTimeFormatter fmt : TIME_FORMATTERS) {
                try {
                    horaConvertida = LocalTime.parse(r.getHora(), fmt);
                    break;
                } catch (DateTimeParseException ignored) {}
            }
        }

        return RadarsDTO.builder()
                .id(Math.abs(UUID.randomUUID().getMostSignificantBits()))
                .data(dataConvertida)
                .hora(horaConvertida)
                .placa(r.getPlaca())
                .concessionaria("APPIA")
                .praca("")
                .rodovia(rk.rodovia())
                .km(rk.km())
                .sentido(r.getSentido() != null ? r.getSentido().toUpperCase() : null)
                .build();
    }

    private List<String> buildDateRange(int dias) {
        List<String> datas = new ArrayList<>();
        LocalDate hoje = LocalDate.now();
        for (int i = 0; i < dias; i++) {
            datas.add(hoje.minusDays(i).format(MONGO_DATE_FMT));
        }
        return datas;
    }

    private double calcularDistanciaHaversine(double lat1, double lon1, double lat2, double lon2) {
        final int RAIO_TERRA_KM = 6371;
        double latDist = Math.toRadians(lat2 - lat1);
        double lonDist = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDist / 2) * Math.sin(latDist / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDist / 2) * Math.sin(lonDist / 2);
        return RAIO_TERRA_KM * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))) * 1000;
    }

}
