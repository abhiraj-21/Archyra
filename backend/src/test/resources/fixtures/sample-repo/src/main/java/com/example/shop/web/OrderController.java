package com.example.shop.web;

import com.example.shop.model.Order;
import com.example.shop.model.OrderRequest;
import com.example.shop.service.OrderService;

public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	public Order create(OrderRequest request) {
		return orderService.place(request);
	}

	public Order createDefault() {
		return this.orderService.place(new OrderRequest("1", "book"));
	}

	public void log(Order order) {
		System.out.println(order.getStatus());
		print(order);
	}

	private void print(Order order) {
		order.getId();
	}
}
