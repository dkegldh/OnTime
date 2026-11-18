package com.ontime.ontime_backend;

import org.springframework.boot.SpringApplication;

public class TestOntimeBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(OntimeBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
