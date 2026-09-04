package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageMetadata implements Serializable {
    private int number;          // Número da página atual (Começa em 0)
    private int size;            // Tamanho da página (ex: 20 itens)
    private long totalElements;  // Total de itens no banco
    private int totalPages;      // Total de páginas disponiveis
}
