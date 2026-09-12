package com.coruja;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class MicroservicoRadaresAppiaApplication {

	public static void main(String[] args) {
		SpringApplication.run(MicroservicoRadaresAppiaApplication.class, args);
	}

}
