package com.coruja.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Provider
@Slf4j
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {
    @Override
    public Response toResponse(Exception e) {
        // Se for um erro HTTP padrão do JAX-RS (como 404, 401, 400), mantém o status original
        if (e instanceof WebApplicationException) {
            WebApplicationException wae = (WebApplicationException) e;
            return Response.status(wae.getResponse().getStatus())
                    .entity(Map.of(
                            "status", wae.getResponse().getStatus(),
                            "erro", e.getMessage() != null ? e.getMessage() : "Erro no na requisição Concessionária MonitoraSP"
                    ))
                    .build();
        }

        // Caso contrário, é um NullPointerException ou erro real e tratamos como 500
        log.error("Erro não tratado: {}", e.getMessage());

        int status = e instanceof IllegalArgumentException ? 400 : 500;
        String message = status == 400 ? e.getMessage() : "Erro interno no servidor - Concessionária APPIA.";

        return Response.status(status)
                .entity(Map.of(
                        "status", status,
                        "erro", message
                ))
                .build();
    }
}
