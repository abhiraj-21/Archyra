import os
import numpy as np
from app.models import User
from .repository import UserRepository, MemoryRepository
from . import helpers


class UserService:
    limit: int = 10

    def __init__(self, repo: UserRepository, name: str = "svc"):
        self.repo = repo
        self.fallback = MemoryRepository()
        self.name = name

    def register(self, email: str) -> User:
        user = User(email, email)
        user.display()
        self.audit(user)
        helpers.log("registered")
        np.array([1])
        return self.repo.save(user)

    def audit(self, user):
        os.getcwd()


def create_service() -> UserService:
    return UserService(MemoryRepository())
