package com.example.shop.model;

import java.util.ArrayList;
import java.util.List;

public class Order extends BaseEntity {

	public enum Status { NEW, PAID }

	private final List<String> items = new ArrayList<>();
	private Status status = Status.NEW;

	public Order(String id) {
		super(id);
	}

	public void addItem(String item) {
		items.add(item);
	}

	public Status getStatus() {
		return status;
	}
}
