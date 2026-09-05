package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FilterOptionsDTO implements Serializable {

    // Implementar Serializable é boa prática para cache Redis
    @Serial
    private static final long serialVersionUID = 1L;
    @JsonAlias("pracas")
    private List<String> rodovias;
    private List<String> kms;
    private List<String> sentidos;
}
