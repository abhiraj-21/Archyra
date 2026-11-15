from app.services.user_service import UserService, create_service


def main():
    service = create_service()
    service.register("a@b.c")
    UserService(None)


if __name__ == "__main__":
    main()
