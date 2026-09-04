package com.coruja.service;

import com.coruja.dto.RadarPageDTO;
import com.coruja.messaging.RadarMqPublisher;
import com.coruja.repository.RadarsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class RadarsService {

    private static final DateTimeFormatter MONGO_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            MONGO_DATE_FMT,
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );
    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm")
    );

    private final RadarsRepository radarsRepository;
    private final RadarMqPublisher radarMqPublisher;
    private final MongoTemplate mongoTemplate;
    private final String databaseName;

    public RadarsService(
            RadarsRepository radarsRepository,
            RadarMqPublisher radarMqPublisher,
            MongoTemplate mongoTemplate,
            @Value("${spring.data.mongodb.database:Veiculos}") String databaseName) {
        this.radarsRepository = radarsRepository;
        this.radarMqPublisher = radarMqPublisher;
        this.mongoTemplate = mongoTemplate;
        this.databaseName = databaseName;
    }

    /**
     * Consulta com filtros dinâmicos
     */
    public RadarPageDTO searchWithFilters(
            String placa,
            String local,
            String km,
            String sentido,
            LocalDate data,
            LocalTime horaInicial,
            LocalTime horaFinal,
            int page,
            int size
    ) {}
}
