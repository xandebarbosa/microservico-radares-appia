package com.coruja.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Date;

@Document(collection = "Appia")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Radars {
    @Id
    private String id;

    // A anotação @Field é a única que o Spring lê para mapear nomes no banco
    @Field("DATA")
    private String data;

    @Field("HORA")
    private String hora;

    @Field("PLACA")
    private String placa;

    @Field("SENTIDO")
    private String sentido;

    @Field("LOCAL")
    private String local;

    // Mantido como String para evitar erro de parse com a vírgula do MongoDB
    @Field("LATITUDE")
    private String latitude;

    @Field("LONGITUDE")
    private String longitude;

    // Campo vital para a ordenação cronológica que implementamos no Service
    @Field("CRIADOEM")
    private Date criadoEm;

    /**
     * Campo fixo para a Concessionária.
     * @Transient garante que o Spring não tente ler/gravar essa informação no banco.
     */
    @Transient
    private String concessionaria = "Appia";

    public Radars(String id, String data, String hora, String placa, String sentido, String local, String latitude, String longitude, Date criadoEm) {
        this.id = id;
        this.data = data;
        this.hora = hora;
        this.placa = placa;
        this.sentido = sentido;
        this.local = local;
        this.criadoEm = criadoEm;
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
