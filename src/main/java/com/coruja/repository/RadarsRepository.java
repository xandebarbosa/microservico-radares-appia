package com.coruja.repository;

import com.coruja.entity.Radars;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RadarsRepository extends MongoRepository<Radars, String> {
    
    Page<Radars> findByPlaca(String placa, Pageable pageable);

    long countByPlaca(String placa);

    @Query(value = "{}",  sort = "{ 'DATA': -1, 'HORA': -1 }")
    List<Radars> findUltimos(Pageable pageable);
}
