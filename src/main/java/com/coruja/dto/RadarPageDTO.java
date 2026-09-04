package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.web.PagedModel;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RadarPageDTO implements Serializable {
    private List<RadarsDTO> content;
    private PageMetadata pageMetadata;
}
