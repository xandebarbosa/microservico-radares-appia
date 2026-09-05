package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RadarsDTO {
    /** ObjectId do MongoDB serializado como String */
    private Long id;

    private LocalDate data;
    private LocalTime hora;
    private String placa;
    private String km;
    private String sentido;
    private String rodovia;
    private String praca;
    private Double latitude;
    private Double longitude;
    private String concessionaria;
}
