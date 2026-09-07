package com.coruja.service;

import com.coruja.dto.*;
import com.coruja.entity.Radars;
import com.coruja.messaging.RadarMqPublisher;
import com.coruja.repository.RadarsRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Serviço principal de radares do microserviço Harpia.
 *
 * Responsabilidades:
 *  - Consulta com filtros dinâmicos no MongoDB (via MongoTemplate e Criteria)
 *  - Caching distribuído via Redis (@Cacheable)
 *  - Conversão de documentos para DTO
 *  - Publicação de eventos no RabbitMQ
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
    // Regex refatorado para garantir a captura apenas da parte numérica do KM
    // Suporta inteiros, 2 ou 3 casas decimais, usando ponto ou vírgula
    private static final Pattern LOCAL_PATTERN = Pattern.compile("(?i)Rodovia:\\s*(.*?)\\s*KM:\\s*([0-9]+(?:[.,][0-9]+)?)");

    // Janela de dias para usar o índice por DATA
    // 7 dias garante cobertura de todos os locais ativos
    // sem varrer os 170M documentos inteiros
    private static final int JANELA_DIAS_LOCAL = 7;
    private static final String COLLECTION_NAME = "APPIA";

    private final RadarsRepository radarsRepository;
    private final RadarMqPublisher mqPublisher;
    private final MongoTemplate mongoTemplate;

    public RadarsService(
            RadarsRepository radarsRepository,
            RadarMqPublisher mqPublisher,
            MongoTemplate mongoTemplate) {
        this.radarsRepository = radarsRepository;
        this.mqPublisher = mqPublisher;
        this.mongoTemplate = mongoTemplate;

    }

    @PostConstruct
    public void inicializar() {
        preAquecerCache();
    }

    public void preAquecerCache() {
        new Thread(() -> {
            try {
                Thread.sleep(15_000);
                log.info("🔥 Pré-aquecendo cache de rodovias e KMs no Redis (background)...");

                listarKmsAgrupados(); // Cacheia Rodovias e KMs
                carregarCoordenadasAgrupadas(); // Cacheia as coordenadas do mapa

                log.info("✅ Cache pré-aquecido com sucesso.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("⚠️ Pré-aquecimento interrompido.");
            } catch (Exception e) {
                log.warn("⚠️ Falha no pré-aquecimento: {}", e.getMessage());
            }
        }, "cache-warmup-appia").start();
    }

    // ═══════════════════════════════════════════════════════════════
    //  GEOGRAFIA E MAPEAMENTO DTO (SUBSTITUI O JSON)
    // ═══════════════════════════════════════════════════════════════

    @Cacheable(value = "mapa-radares-appia")
    public List<RadarLocationDTO> carregarCoordenadasAgrupadas() {
        log.info("🗺️ Extraindo coordenadas únicas dos radares via Aggregation...");
        long inicio = System.currentTimeMillis();

        // 1. Pipeline de Agregação
        Aggregation aggregation = Aggregation.newAggregation(
                // Filtra apenas registros que possuam latitude e longitude preenchidas
                Aggregation.match(Criteria.where("LATITUDE").exists(true).ne(null).ne("")
                        .and("LONGITUDE").exists(true).ne(null).ne("")),

                // Agrupa pelo campo "LOCAL" e pega a PRIMEIRA coordenada que aparecer
                // Isso garante 100% que não haverá repetição para o mesmo KM
                Aggregation.group("LOCAL")
                        .first("LATITUDE").as("latitude")
                        .first("LONGITUDE").as("longitude"),

                Aggregation.sort(Sort.Direction.ASC, "_id")
        );

        // 2. Executa a query
        AggregationResults<Document> results = mongoTemplate.aggregate(
                aggregation, COLLECTION_NAME, org.bson.Document.class
        );

        // 3. Mapeia os resultados para o formato DTO do frontend
        List<RadarLocationDTO> locations = new ArrayList<>();

        for (org.bson.Document doc : results.getMappedResults()) {
            String local = doc.getString("_id");
            if (local == null) continue;

            String latitudeStr = doc.getString("latitude");
            String longitudeStr = doc.getString("longitude");

            String rodovia = "Desconhecida";
            String km = "";

            // Reutiliza o regex para separar Rodovia e KM perfeitamente
            Matcher matcher = LOCAL_PATTERN.matcher(local);
            if (matcher.find()) {
                rodovia = matcher.group(1).trim();
                km = matcher.group(2).trim();
            } else {
                rodovia = local;
            }

            RadarLocationDTO dto = new RadarLocationDTO();
            // Gera um ID numérico único e consistente baseado na string do LOCAL
            dto.setId(Math.abs((long) local.hashCode()));
            dto.setConcessionaria("APPIA");
            dto.setRodovia(rodovia);
            dto.setKm(km);

            // O MongoDB retorna Strings ("-22.67574747"). Convertendo para Double.
            try {
                dto.setLatitude(Double.parseDouble(latitudeStr.replace(",", ".")));
                dto.setLongitude(Double.parseDouble(longitudeStr.replace(",", ".")));
            } catch (NumberFormatException e) {
                continue; // Pula a iteração se a coordenada vier corrompida do banco
            }

            dto.setSentido("Ambos");
            locations.add(dto);
        }

        log.info("Extração finalizada em {}ms — {} localizações únicas consolidadas no Redis.",
                System.currentTimeMillis() - inicio, locations.size());

        return locations;
    }

    // ═══════════════════════════════════════════════════════════════
    //  OPÇÕES DE FILTRO (AGREGAÇÃO DINÂMICA VIA REDIS)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Centraliza a agregação pesada e guarda o resultado processado no Redis.
     * Retorna um Map onde a Chave é a Rodovia e o Valor é a lista de KMs.
     */
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

        AggregationResults<org.bson.Document> results = mongoTemplate.aggregate(aggregation, COLLECTION_NAME, org.bson.Document.class);
        List<String> locaisBrutos = results.getMappedResults().stream()
                .map(doc -> doc.getString("_id"))
                .toList();

        Map<String, Set<String>> kmsPorRodoviaTemp = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (String local : locaisBrutos) {
            Matcher matcher = LOCAL_PATTERN.matcher(local);
            if (matcher.find()) {
                String rodovia = matcher.group(1).trim();
                String km = matcher.group(2).trim();
                if (!rodovia.isBlank()) {
                    kmsPorRodoviaTemp.computeIfAbsent(rodovia, k -> new TreeSet<>()).add(km);
                }
            }
        }

        Map<String, List<String>> processados = new LinkedHashMap<>();
        kmsPorRodoviaTemp.forEach((rodovia, kms) -> processados.put(rodovia, new ArrayList<>(kms)));

        log.info("Agregação finalizada em {}ms — {} LOCALs distintos extraídos.",
                System.currentTimeMillis() - inicio, locaisBrutos.size());

        return processados;
    }

    public List<RodoviaDTO> listarRodovias() {
        Map<String, List<String>> agrupamento = listarKmsAgrupados();
        List<RodoviaDTO> dtos = new ArrayList<>();
        long id = 1L;
        for (String nomeRodovia : agrupamento.keySet()) {
            dtos.add(new RodoviaDTO(id++, nomeRodovia));
        }
        return dtos;
    }

    public List<KmRodoviaDTO> listarKmsPorRodovia(Long rodoviaId) {
        List<RodoviaDTO> rodovias = listarRodovias();
        String nomeAlvo = rodovias.stream()
                .filter(r -> r.getId().equals(rodoviaId))
                .map(RodoviaDTO::getNome)
                .findFirst()
                .orElse(null);

        if (nomeAlvo == null) return Collections.emptyList();

        List<String> kms = listarKmsAgrupados().getOrDefault(nomeAlvo, Collections.emptyList());
        List<KmRodoviaDTO> dtos = new ArrayList<>();
        long id = 1L;

        for (String km : kms) {
            dtos.add(KmRodoviaDTO.builder().id(id++).valor(km).rodoviaId(rodoviaId).build());
        }
        return dtos;
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONSULTA COM FILTROS DINÂMICOS & CACHE
    // ═══════════════════════════════════════════════════════════════

    @Cacheable(
            value = "radars-search-appia",
            key = "{#placa, #rodovia, #km, #sentido, #data, #horaInicial, #horaFinal, #page, #size}"
    )
    public RadarPageDTO buscarComFiltros(
            String placa, String rodovia, String km, String sentido,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
            int page, int size) {

        Query query = buildQuery(placa, rodovia, km, sentido, data, horaInicial, horaFinal);

        long total = mongoTemplate.count(query, Radars.class);

        query.with(Sort.by(Sort.Direction.DESC, "DATA", "HORA"));

        if (size > 0) {
            query.with(PageRequest.of(page, size));
        }

        List<RadarsDTO> content = mongoTemplate.find(query, Radars.class).stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new RadarPageDTO(content, new PageMetadata(page, size, total, totalPages));
    }

    private Query buildQuery(String placa, String rodovia, String km, String sentido,
                             LocalDate data, LocalTime horaInicial, LocalTime horaFinal) {
        Query query = new Query();

        if (data != null) {
            query.addCriteria(Criteria.where("DATA").is(data.format(MONGO_DATE_FMT)));
        }

        if (horaInicial != null || horaFinal != null) {
            Criteria horaCriteria = Criteria.where("HORA");
            if (horaInicial != null) horaCriteria.gte(horaInicial.toString());
            if (horaFinal != null) horaCriteria.lte(horaFinal.toString());
            query.addCriteria(horaCriteria);
        }

        if (placa != null && !placa.isBlank()) {
            query.addCriteria(Criteria.where("PLACA").regex("(?i).*" + Pattern.quote(placa.trim()) + ".*"));
        }

        // Correção crítica no regex do LOCAL
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
            String sentidoFormatado = s.substring(0, 1).toUpperCase() + s.substring(1);
            query.addCriteria(Criteria.where("SENTIDO").is(sentidoFormatado));
        }

        return query;
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONSULTA POR PLACA
    // ═══════════════════════════════════════════════════════════════

    @Cacheable(value = "radars-placa-appia", key = "#placa != null ? #placa.toUpperCase().trim() + '-' + #page + '-' + #size : 'empty'")
    public RadarPageDTO buscarPorPlaca(String placa, int page, int size) {
        if (placa == null || placa.isBlank()) {
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(page, size, 0, 0));
        }

        Query query = new Query(Criteria.where("PLACA").is(placa.toUpperCase().trim()));
        long total = mongoTemplate.count(query, Radars.class);

        if (total == 0) {
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(page, size, 0, 0));
        }

        query.with(Sort.by(Sort.Direction.DESC, "DATA", "HORA"));
        if (size > 0) query.with(PageRequest.of(page, size));

        List<RadarsDTO> content = mongoTemplate.find(query, Radars.class).stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new RadarPageDTO(content, new PageMetadata(page, size, total, totalPages));
    }

    public PlacaResumoDTO montarResumo(String placa, long total) {
        Query queryPlaca = new Query(Criteria.where("PLACA").is(placa));

        // Removido o hardcode "SP 300", extraindo dinamicamente do banco
        List<String> rodoviasEncontradas = findDistinct("LOCAL", queryPlaca).stream()
                .map(local -> {
                    Matcher m = LOCAL_PATTERN.matcher(local);
                    return m.find() ? m.group(1).trim() : null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        List<String> kms = findDistinct("LOCAL", queryPlaca);
        List<String> sentidos = findDistinct("SENTIDO", queryPlaca);

        Query queryPrimeiro = new Query(Criteria.where("PLACA").is(placa)).with(Sort.by(Sort.Direction.ASC, "DATA", "HORA")).limit(1);
        Radars primeiro = mongoTemplate.findOne(queryPrimeiro, Radars.class);

        Query queryUltimo = new Query(Criteria.where("PLACA").is(placa)).with(Sort.by(Sort.Direction.DESC, "DATA", "HORA")).limit(1);
        Radars ultimo = mongoTemplate.findOne(queryUltimo, Radars.class);

        return PlacaResumoDTO.builder()
                .totalPassagens(total)
                .primeiraPassagemData(primeiro != null ? primeiro.getData() : null)
                .ultimaPassagemData(ultimo != null ? ultimo.getData() : null)
                .rodovias(rodoviasEncontradas)
                .kms(sorted(kms))
                .sentidos(sorted(sentidos))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  BUSCA GEOGRÁFICA COM RECORTE TEMPORAL
    // ═══════════════════════════════════════════════════════════════

    @Cacheable(
            value = "locais-radares-bff",
            key = "{#latCentro, #lngCentro, #raioMetros, #data, #horaInicial, #horaFinal, #page, #size}"
    )
    public RadarPageDTO buscaGeografica(
            Double latCentro, Double lngCentro, Double raioMetros,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
            int page, int size) {

        // 1. Aplica o raio padrão de 15.000 metros (15 km) se não for informado
        double raio = (raioMetros != null) ? raioMetros : 15000.0;

        // 2. Encontra quais KMs fixos estão dentro do raio (Lendo do Cache/Redis)
        List<String> locaisNoRaio = new ArrayList<>();
        List<RadarLocationDTO> todasAsCoordenadas = carregarCoordenadasAgrupadas();

        for (RadarLocationDTO coord : todasAsCoordenadas) {
            if (coord.getLatitude() == null || coord.getLongitude() == null) continue;

            double distancia = calcularDistanciaHaversine(
                    latCentro, lngCentro,
                    coord.getLatitude(), coord.getLongitude()
            );

            if (distancia <= raio) {
                // Remonta a string do LOCAL para bater exatamente com a nomenclatura do banco
                // Ajuste os espaços conforme o formato exato salvo na sua base (ex: "RODOVIA: SP300 KM:285,100")
                String localFormatado = "RODOVIA: " + coord.getRodovia() + " KM:" + coord.getKm();
                locaisNoRaio.add(localFormatado);
            }
        }

        // Se nenhum radar estiver no raio, retorna página vazia imediatamente sem ir ao banco
        if (locaisNoRaio.isEmpty()) {
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(page, size, 0, 0));
        }

        // 3. Monta a consulta dinâmica no MongoDB
        Query query = new Query();

        // Filtro de Localização (Raio)
        query.addCriteria(Criteria.where("LOCAL").in(locaisNoRaio));

        // Filtro de Data
        if (data != null) {
            query.addCriteria(Criteria.where("DATA").is(data.format(MONGO_DATE_FMT)));
        }

        // Filtro de Horário (Range)
        if (horaInicial != null || horaFinal != null) {
            Criteria horaCriteria = Criteria.where("HORA");
            if (horaInicial != null) horaCriteria.gte(horaInicial.toString());
            if (horaFinal != null) horaCriteria.lte(horaFinal.toString());
            query.addCriteria(horaCriteria);
        }

        // 4. Executa a paginação e ordenação
        long totalElements = mongoTemplate.count(query, Radars.class);

        // Ordena pelos mais recentes primeiro
        query.with(Sort.by(Sort.Direction.DESC, "DATA", "HORA"));

        if (size > 0) {
            query.with(PageRequest.of(page, size));
        }

        List<Radars> radaresEncontrados = mongoTemplate.find(query, Radars.class);

        // 5. Converte para DTO
        List<RadarsDTO> content = radaresEncontrados.stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());

        // Injeta as coordenadas de volta nos DTOs de resposta para o mapa renderizar os pontos
        for (RadarsDTO dto : content) {
            todasAsCoordenadas.stream()
                    .filter(c -> c.getKm().equals(dto.getKm()) && c.getRodovia().equalsIgnoreCase(dto.getRodovia()))
                    .findFirst()
                    .ifPresent(c -> {
                        dto.setLatitude(c.getLatitude());
                        dto.setLongitude(c.getLongitude());
                    });
        }

        // 6. Monta a resposta final
        RadarPageDTO response = new RadarPageDTO();
        response.setContent(content);

        PageMetadata meta = new PageMetadata();
        meta.setNumber(page);
        meta.setSize(size);
        meta.setTotalElements(totalElements);
        meta.setTotalPages(size == 0 ? 0 : (int) Math.ceil((double) totalElements / size));
        response.setPageMetadata(meta);

        return response;
    }

    /**
     * Utilitário: Fórmula de Haversine para calcular a distância em metros entre duas coordenadas.
     * Considera a curvatura da Terra para entregar uma precisão alta em distâncias curtas e médias.
     */
    private double calcularDistanciaHaversine(double lat1, double lon1, double lat2, double lon2) {
        final int RAIO_TERRA_KM = 6371;

        double latDist = Math.toRadians(lat2 - lat1);
        double lonDist = Math.toRadians(lon2 - lon1);

        double a = Math.sin(latDist / 2) * Math.sin(latDist / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDist / 2) * Math.sin(lonDist / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // Retorna o valor já convertido de quilômetros para metros
        return RAIO_TERRA_KM * c * 1000;
    }

    // ═══════════════════════════════════════════════════════════════
    //  SALVAR + PUBLICAR NO RABBITMQ
    // ═══════════════════════════════════════════════════════════════

    public void salvarRadares(List<Radars> radares) {
        if (radares == null || radares.isEmpty()) return;

        // A persistência falhará em tempo de execução se o usuário do MongoDB for estritamente Read-Only.
        radarsRepository.saveAll(radares);
        log.info("{} registros submetidos para persistência.", radares.size());

        radares.forEach(mqPublisher::publicar);
    }

    // ═══════════════════════════════════════════════════════════════
    //  OPÇÕES DE FILTRO (DISTINCT VALUES)
    // ═══════════════════════════════════════════════════════════════

    public List<RadarsDTO> buscarUltimos(int limite) {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "_id")).limit(limite);

        return mongoTemplate.find(query, Radars.class).stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());
    }

    private List<String> findDistinct(String field, Query query) {
        query.addCriteria(Criteria.where(field).ne(null));
        return mongoTemplate.findDistinct(query, field, Radars.class, String.class).stream()
                .filter(s -> s != null && !s.isBlank())
                .sorted()
                .collect(Collectors.toList());
    }

    private List<String> sorted(List<String> list) {
        return list.stream().filter(s -> s != null && !s.isBlank()).sorted().collect(Collectors.toList());
    }

    private RadarsDTO converterParaDTO(Radars r) {
        String rodovia = "";
        String km = "";
        String rawLocal = r.getLocal() != null ? r.getLocal() : "";

        Matcher matcher = LOCAL_PATTERN.matcher(rawLocal);
        if (matcher.find()) {
            rodovia = matcher.group(1).trim();
            km = matcher.group(2).trim();
        } else {
            rodovia = rawLocal;
        }

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
                .praca("") // Mantido vazio conforme requisito de estrutura do BFF
                .rodovia(rodovia)
                .km(km)
                .sentido(r.getSentido() != null ? r.getSentido().toUpperCase() : null)
                .build();
    }

    /**
     * Gera lista de datas no formato dd/MM/yyyy para os últimos N dias.
     * Usado no $match para ativar o índice composto por DATA.
     */
    private List<String> buildDateRange(int dias) {
        List<String> datas = new ArrayList<>();
        LocalDate hoje = LocalDate.now();
        for (int i = 0; i < dias; i++) {
            datas.add(hoje.minusDays(i).format(MONGO_DATE_FMT));
        }
        return datas;
    }

    /**
     * ✅ LOCALIZAÇÕES PARA MAPA - Cache de 24 horas
     */
    /*@Cacheable(
            value = "mapa-radares-appia",
            unless = "#result == null || #result.isEmpty()"
    )
    @Transactional(readOnly = true)
    public List<LocalizacaoRadarProjection> listarTodasLocalizacoes() {
        return
    }*/
}
