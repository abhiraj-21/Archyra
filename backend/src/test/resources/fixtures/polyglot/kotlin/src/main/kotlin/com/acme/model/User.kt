package com.acme.model

data class User(val name: String) {
    fun display(): String = name
}
