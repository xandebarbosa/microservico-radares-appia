package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacaResumoDTO {

    /** Quantidade total de passagens registradas */
    private long totalPassagens;

    /** Data da passagem mais antiga (formato ISO: yyyy-MM-dd) */
    private String primeiraPassagemData;

    /** Data da passagem mais recente (formato ISO: yyyy-MM-dd) */
    private String ultimaPassagemData;

    /** Rodovias distintas em que a placa foi detectada */
    private List<String> rodovias;

    /** KMs distintos em que a placa foi detectada */
    private List<String> kms;

    /** Sentidos distintos registrados */
    private List<String> sentidos;
}
