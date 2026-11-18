package com.acme.app;

public class GreetingService {

	private final GreetingRepository repository;

	public GreetingService(GreetingRepository repository) {
		this.repository = repository;
	}

	public String greet(String name) {
		return "Hello " + repository.find(name);
	}
}
