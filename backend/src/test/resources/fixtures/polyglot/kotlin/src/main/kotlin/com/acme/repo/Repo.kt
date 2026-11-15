package com.acme.repo

import com.acme.model.User

interface Repo {
    fun find(id: Int): User?
}
