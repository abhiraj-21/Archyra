from dataclasses import dataclass


@dataclass
class User:
    name: str
    email: str

    def display(self) -> str:
        return self.name
