from abc import ABC, abstractmethod

from app.models.user import User


class UserRepository(ABC):
    @abstractmethod
    def save(self, user: User) -> User:
        ...


class MemoryRepository(UserRepository):
    def __init__(self):
        self.items = {}

    def save(self, user: User) -> User:
        self.items[user.email] = user
        return user
