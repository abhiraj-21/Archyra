package com.acme

import com.acme.repo.Repo
import com.acme.service.Service

fun main(repo: Repo) {
    val service = Service(repo)
    service.load(1)
}
