package com.coruja.service;

import com.coruja.dto.*;
import com.coruja.entity.Radars;
import com.coruja.messaging.RadarMqPublisher;
import com.coruja.repository.RadarsRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
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
    private static final Pattern LOCAL_PATTERN = Pattern.compile("(?i)Rodovia:\\s*(.*?)\\s*KM:\\s*(?:KM)?\\s*([0-9]+(?:[.,][0-9]+)?)");

    // Janela de dias usada na aggregation de metadados (rodovias/kms).
    // Antes 7 dias fixo; agora configurável e ampliado para 45 dias, já
    // que o cache passa a ser atualizado 1x/mês (job agendado) em vez de
    // recalculado a cada TTL do Redis expirar — uma janela maior evita
    // que rodovia/km de baixo tráfego "sumam" do cache.
    @Value("${radares.cache.janela-dias:45}")
    private int janelaDiasLocal;
    private static final String COLLECTION_NAME = "Appia";

    private final RadarsRepository radarsRepository;
    private final RadarMqPublisher mqPublisher;
    private final MongoTemplate mongoTemplate;

    @org.springframework.beans.factory.annotation.Value("${spring.data.mongodb.uri:NENHUMA_URI_LIDA}")
    private String uriConfigurada;

    // Auto-referência via proxy: chamadas internas a métodos @Cacheable
    // (this.metodo()) NÃO passam pelo proxy AOP do Spring, então o
    // @Cacheable é silenciosamente ignorado nelas — nem lê nem escreve
    // no Redis, só recalcula toda vez. Usando "self" (injetado como
    // @Lazy para evitar dependência circular na criação do bean), as
    // chamadas internas passam pelo proxy normalmente.
    @Lazy
    @Autowired
    private RadarsService self;

    public RadarsService(RadarsRepository radarsRepository, RadarMqPublisher mqPublisher, MongoTemplate mongoTemplate) {
        this.radarsRepository = radarsRepository;
        this.mqPublisher = mqPublisher;
        this.mongoTemplate = mongoTemplate;
    }

    // ─── Startup Assíncrono ───────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void inicializarCacheAssincrono() {
        log.info("================================================================");
        log.info("🕵️ DIAGNÓSTICO DE CONEXÃO MONGODB");
        log.info("🔗 URI carregada pelo Spring: {}", uriConfigurada);
        log.info("📂 Banco de dados ativo no MongoTemplate: {}", mongoTemplate.getDb().getName());
        log.info("================================================================");

        CompletableFuture.runAsync(() -> {
            log.info("🔥 Iniciando pré-aquecimento de cache no Redis (background)...");
            try {
                // Chamadas via "self" — não "this." — para passar pelo
                // proxy do Spring e realmente popular o Redis. Antes o
                // warm-up chamava this.listarKmsAgrupados() diretamente:
                // computava o resultado e JOGAVA FORA, sem cachear nada.
                self.listarRodovias();
                self.listarKmsPorRodovia(null);
                self.listarKmsAgrupados();
                self.carregarCoordenadasAgrupadas();
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
            String rodovia = matcher.group(1).trim();
            // Substitui a vírgula (ou ponto, se houver) pelo sinal de mais (+)
            String km = matcher.group(2).trim().replace(",", "+").replace(".", "+");
            return new RodoviaKm(rodovia, km);
        }
        return new RodoviaKm(localBruto.trim(), "");
    }

    // ═══════════════════════════════════════════════════════════════
    //  METADADOS DE RODOVIAS E KMS (AGGREGATION COM JANELA — SEM SCAN FULL)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Monta o mapa rodovia → KMs distintos via UMA aggregation com janela
     * de dias (usa o índice por DATA em vez de varrer a coleção inteira).
     * Método privado, SEM @Cacheable: quem cacheia é quem chama —
     * listarRodovias(), listarKmsPorRodovia() e listarKmsAgrupados() cada
     * um guarda seu próprio resultado no Redis, sob sua própria chave.
     * (Chamar um método @Cacheable de dentro da própria classe não passa
     * pelo proxy do Spring, então o cache dele seria ignorado — por isso
     * a lógica fica aqui, fora de qualquer método anotado.)
     */
    private Map<String, List<String>> construirMapaRodoviasKms() {
        log.info("Consultando Locais via aggregation (janela de {} dias)...", janelaDiasLocal);
        long inicio = System.currentTimeMillis();

        List<String> datasRecentes = buildDateRange(janelaDiasLocal);

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("DATA").in(datasRecentes)),
                Aggregation.group("LOCAL"),
                Aggregation.match(Criteria.where("_id").ne(null)),
                Aggregation.sort(Sort.Direction.ASC, "_id")
        );

        AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, COLLECTION_NAME, Document.class);

        Map<String, Set<String>> kmsPorRodoviaTemp = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> locaisNaoReconhecidos = new ArrayList<>();

        for (Document doc : results.getMappedResults()) {
            String local = doc.getString("_id");
            RodoviaKm rk = extrairRodoviaKm(local);
            if (!rk.rodovia().isBlank()) {
                kmsPorRodoviaTemp.computeIfAbsent(rk.rodovia(), k -> new TreeSet<>()).add(rk.km());
            } else if (local != null && !local.isBlank()) {
                // LOCAL não bateu com o padrão "Rodovia: X KM: Y" — fica de
                // fora do cache. Logar ajuda a achar dado mal formatado na
                // origem em vez de simplesmente "sumir" para o front.
                locaisNaoReconhecidos.add(local);
            }
        }

        if (!locaisNaoReconhecidos.isEmpty()) {
            log.warn("⚠️ {} LOCAL(is) não reconheceram o padrão 'Rodovia: X KM: Y' e ficaram de fora do cache: {}",
                    locaisNaoReconhecidos.size(), locaisNaoReconhecidos);
        }

        Map<String, List<String>> processados = new LinkedHashMap<>();
        kmsPorRodoviaTemp.forEach((rodovia, kms) -> processados.put(rodovia, new ArrayList<>(kms)));

        log.info("=== RODOVIAS E KMs CARREGADOS (APPIA) ===");
        processados.forEach((rodovia, kms) ->
                log.info("🛣️ Rodovia: {} | 📍 Total de KMs: {} | Valores: {}", rodovia, kms.size(), kms));
        log.info("==========================================");

        log.info("Agregação finalizada em {}ms — {} rodovias, {} LOCALs distintos extraídos.",
                System.currentTimeMillis() - inicio, processados.size(), results.getMappedResults().size());

        return processados;
    }

    /**
     * Lista todas as rodovias distintas (dentro da janela de dias).
     * Antes fazia um findDistinct SEM filtro na coleção inteira — trocado
     * pela aggregation com janela, index-friendly.
     */
    @Cacheable(value = "todas-rodovias-appia")
    public List<RodoviaDTO> listarRodovias() {
        log.info("🔍 Extraindo rodovias distintas a partir da aggregation com janela...");

        List<String> nomesOrdenados = construirMapaRodoviasKms().keySet().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        List<RodoviaDTO> dtos = new ArrayList<>();
        for (int i = 0; i < nomesOrdenados.size(); i++) {
            dtos.add(RodoviaDTO.builder()
                    .id((long) (i + 1))
                    .nome(nomesOrdenados.get(i))
                    .build());
        }
        return dtos;
    }

    /**
     * Devolve o nome da rodovia a partir do ID estável gerado em
     * listarRodovias() (mesmo padrão do monitorasp). Necessário porque o
     * BFF chama /radares/rodovias/{id}/kms usando o id que recebeu de
     * /radares/rodovias — antes esse endpoint nem existia no Appia.
     */
    public String getRodoviaById(Long rodoviaId) {
        List<RodoviaDTO> rodovias = self.listarRodovias();
        return rodovias.stream()
                .filter(r -> r.getId().equals(rodoviaId))
                .map(RodoviaDTO::getNome)
                .findFirst()
                .orElse(null);
    }

    /**
     * Lista os KMs distintos (de uma rodovia específica, ou de todas).
     * Antes fazia um findDistinct com regex "(?i)" SEM filtro de data —
     * regex case-insensitive não usa índice, virava COLLSCAN na coleção
     * inteira. Trocado pela mesma aggregation windowed usada acima.
     */
    @Cacheable(value = "todos-kms-appia", key = "#rodovia != null ? #rodovia.toUpperCase().trim() : 'TODOS'")
    public List<String> listarKmsPorRodovia(String rodovia) {
        log.info("🔍 Extraindo KMs distintos (Rodovia: {})...", rodovia != null ? rodovia : "Todas");

        Map<String, List<String>> mapa = construirMapaRodoviasKms();

        List<String> kms;
        if (rodovia == null || rodovia.isBlank()) {
            kms = mapa.values().stream().flatMap(List::stream).distinct().toList();
        } else {
            String chave = mapa.keySet().stream()
                    .filter(r -> r.equalsIgnoreCase(rodovia.trim()))
                    .findFirst()
                    .orElse(null);
            kms = chave != null ? mapa.get(chave) : List.of();
        }

        return kms.stream()
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
        return construirMapaRodoviasKms();
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

        for (RadarLocationDTO coord : self.carregarCoordenadasAgrupadas()) {
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

        // REVERSÃO: Transforma o "12+625" recebido do front de volta para "12,625" para achar no BD
        String kmNormalizado = (km != null && !km.isBlank()) ? km.trim().replace("+", ",") : null;

        if (rodovia != null && !rodovia.isBlank()) {
            String regexLocal = (kmNormalizado != null)
                    ? "(?i)Rodovia:\\s*" + Pattern.quote(rodovia.trim()) + ".*KM:\\s*" + Pattern.quote(kmNormalizado)
                    : "(?i)Rodovia:\\s*" + Pattern.quote(rodovia.trim()) + ".*";
            query.addCriteria(Criteria.where("LOCAL").regex(regexLocal));
        } else if (kmNormalizado != null) {
            query.addCriteria(Criteria.where("LOCAL").regex("(?i).*KM:\\s*" + Pattern.quote(kmNormalizado) + ".*"));
        }

        if (sentido != null && !sentido.isBlank()) {
            String s = sentido.trim().toLowerCase();
            query.addCriteria(Criteria.where("SENTIDO").is(s.substring(0, 1).toUpperCase() + s.substring(1)));
        }

        return query;
    }

    private List<String> findDistinct(String field, Query query) {
        query.addCriteria(Criteria.where(field).ne(null));
        return mongoTemplate.findDistinct(query, field, COLLECTION_NAME, String.class).stream()
                .filter(s -> !s.isBlank())
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Converte coordenadas com vírgula do MongoDB para Double do Java de forma segura.
     */
    private Double converterCoordenadaSegura(String coordenada) {
        if (coordenada == null || coordenada.isBlank()) return null;
        try {
            return Double.parseDouble(coordenada.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            log.warn("Falha ao converter coordenada: {}", coordenada);
            return null;
        }
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
