package com.acme.service

import com.acme.model.User
import com.acme.repo.Repo
import com.acme.util.*
import java.io.Closeable

class Service(private val repo: Repo) : BaseService(), Closeable {
    private val cache = HashMap<String, User>()

    fun load(id: Int): User? {
        val user = repo.find(id)
        val created = User("a")
        created.display()
        this.audit(user)
        log("x")
        helper(1)
        return user
    }

    private fun audit(user: User?) {}

    override fun close() {}

    companion object {
        fun create(repo: Repo): Service = Service(repo)
    }
}
