from tools.cleaner import clean


def test_clean():
    assert clean(" a ") == "a"
