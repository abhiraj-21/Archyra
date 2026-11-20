package com.abhiraj.archyra.billing;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link RazorpayGateway} over a plain {@code RestClient} with HTTP Basic ({@code key_id:key_secret}), as Razorpay's REST
 * API expects. Not the official {@code razorpay-java} SDK: it hard-codes {@code https://api.razorpay.com} (so it cannot be
 * pointed at a WireMock fake, which the tests need) and brings OkHttp 3.10 and an HTTP logging interceptor of 2018 into a
 * Boot 4 application that already ships {@code RestClient}. Four calls do not justify that.
 *
 * <p>Nothing is logged here: not the keys, not a body, not a header. A failure is a {@link RazorpayUnavailableException}
 * carrying the status only; the response body of a failure is never read.
 */
@Component
class RazorpayRestGateway implements RazorpayGateway {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** An order or subscription is a few hundred bytes; anything far larger is not what we asked for. */
	private static final int MAX_RESPONSE_BYTES = 256 * 1024;

	private final BillingProperties properties;
	private volatile RestClient client;

	RazorpayRestGateway(BillingProperties properties) {
		this.properties = properties;
	}

	@Override
	public Order createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("amount", amountPaise);
		body.put("currency", currency);
		body.put("receipt", receipt);
		body.put("notes", notes);
		JsonNode order = call("create order", "POST", "/v1/orders", body);
		return new Order(order.path("id").asString(""), order.path("amount").asLong(0), order.path("currency").asString(""),
				order.path("receipt").asString(null), order.path("status").asString(""));
	}

	@Override
	public RemoteSubscription createSubscription(String planId, int totalCount, Map<String, String> notes) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("plan_id", planId);
		body.put("total_count", totalCount);
		body.put("quantity", 1);
		body.put("customer_notify", 1);
		body.put("notes", notes);
		return subscription(call("create subscription", "POST", "/v1/subscriptions", body));
	}

	@Override
	public RemoteSubscription cancelSubscription(String subscriptionId, boolean atCycleEnd) {
		return subscription(call("cancel subscription", "POST", "/v1/subscriptions/" + pathSegment(subscriptionId) + "/cancel",
				Map.of("cancel_at_cycle_end", atCycleEnd ? 1 : 0)));
	}

	@Override
	public RemoteSubscription fetchSubscription(String subscriptionId) {
		return subscription(call("fetch subscription", "GET", "/v1/subscriptions/" + pathSegment(subscriptionId), null));
	}

	static RemoteSubscription subscription(JsonNode node) {
		long end = node.path("current_end").asLong(0);
		return new RemoteSubscription(node.path("id").asString(""), node.path("plan_id").asString(""), node.path("status").asString(""),
				end > 0 ? Instant.ofEpochSecond(end) : null, node.path("paid_count").asInt(0));
	}

	/** Ids are Razorpay's ({@code sub_...}): letters, digits and underscores. Anything else never reaches a URL. */
	private static String pathSegment(String id) {
		if (id == null || !id.matches("[A-Za-z0-9_]{1,64}")) {
			throw new RazorpayUnavailableException("request", 0);
		}
		return id;
	}

	private JsonNode call(String operation, String method, String path, Object body) {
		if (!properties.enabled()) {
			throw new RazorpayUnavailableException(operation, 0);
		}
		String url = properties.razorpay().baseUrl().strip().replaceAll("/+$", "") + path;
		try {
			RestClient.RequestBodySpec request = client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(url)
					.headers(this::headers);
			if (body != null) {
				request.body(JSON.writeValueAsString(body));
			}
			return request.exchange((req, res) -> {
				int status = res.getStatusCode().value();
				if (status < 200 || status >= 300) {
					throw new RazorpayUnavailableException(operation, status);
				}
				byte[] bytes = res.getBody().readNBytes(MAX_RESPONSE_BYTES);
				try {
					return JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
				}
				catch (RuntimeException notJson) {
					throw new RazorpayUnavailableException(operation, status);
				}
			});
		}
		catch (RazorpayUnavailableException ex) {
			throw ex;
		}
		catch (RuntimeException ex) { // connection refused, timeout: the cause may carry a URL, so it is dropped
			throw new RazorpayUnavailableException(operation, 0);
		}
	}

	private void headers(HttpHeaders headers) {
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(List.of(MediaType.APPLICATION_JSON));
		headers.setBasicAuth(properties.razorpay().keyId().strip(), properties.razorpay().keySecret().strip(), StandardCharsets.UTF_8);
	}

	private RestClient client() {
		RestClient built = client;
		if (built == null) {
			synchronized (this) {
				built = client;
				if (built == null) {
					HttpClient http = HttpClient.newBuilder().connectTimeout(properties.razorpay().timeout())
							.version(HttpClient.Version.HTTP_1_1).build();
					JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
					factory.setReadTimeout(properties.razorpay().timeout());
					built = RestClient.builder().requestFactory(factory).build();
					client = built;
				}
			}
		}
		return built;
	}
}
