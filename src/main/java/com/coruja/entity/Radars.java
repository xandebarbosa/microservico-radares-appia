package com.coruja.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonIgnore;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "Appia")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Radars {
    @BsonId
    private ObjectId id;

    @BsonProperty("DATA")
    private String data;

    @BsonProperty("HORA")
    private String hora;

    @BsonProperty("PLACA")
    private String placa;

    @BsonProperty("SENTIDO")
    private String sentido;

    @BsonProperty("LOCAL")
    private String local;

    @BsonProperty("LATITUDE")
    private Double latitude;

    @BsonProperty("LONGITUDE")
    private Double longitude;

    @BsonIgnore
    private String concessionaria = "Appia";

    public Radars(ObjectId id, String data, String hora, String placa, String sentido, String local, Double latitude, Double longitude) {
        this.id = id;
        this.data = data;
        this.hora = hora;
        this.placa = placa;
        this.sentido = sentido;
        this.local = local;
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
