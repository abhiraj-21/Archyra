package com.acme.app;

import com.acme.app.fixtures.Samples;

public class GreetingServiceTest {

	void greetsByName() {
		GreetingService service = new GreetingService(name -> name);
		service.greet(Samples.name());
	}
}
