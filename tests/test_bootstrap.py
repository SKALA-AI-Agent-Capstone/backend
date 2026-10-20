import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_bootstrap(tmp_path):
    spec = importlib.util.spec_from_file_location("bootstrap_under_test", ROOT / "scripts/bootstrap.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.ROOT = tmp_path
    (tmp_path / ".env.example").write_text(
        "APP_DB_PASSWORD=__GENERATE_APP_PASSWORD__\n"
        "APP_URL=postgresql://app:__GENERATE_APP_PASSWORD__@localhost/db\n"
        "RABBITMQ_USERNAME=capstone\n"
        "RABBITMQ_PASSWORD=__GENERATE_RABBITMQ_PASSWORD__\n"
    )
    return module


def test_bootstrap_preserves_existing_secrets_and_is_idempotent(tmp_path, capsys):
    module = load_bootstrap(tmp_path)
    destination = tmp_path / ".env"
    original = "# local settings\nAPP_DB_PASSWORD=existing-secret\nCUSTOM=value\n"
    destination.write_text(original)
    module.main()
    updated = destination.read_text()
    assert updated.startswith(original)
    assert "APP_URL=postgresql://app:existing-secret@localhost/db" in updated
    assert "RABBITMQ_PASSWORD=" in updated and "__GENERATE_" not in updated
    module.main()
    assert destination.read_text() == updated
    assert "existing-secret" not in capsys.readouterr().out


def test_bootstrap_new_file_has_private_permissions_and_no_placeholders(tmp_path, capsys):
    module = load_bootstrap(tmp_path)
    module.main()
    destination = tmp_path / ".env"
    text = destination.read_text()
    assert "__GENERATE_" not in text
    assert destination.stat().st_mode & 0o777 == 0o600
    password = next(line.split("=", 1)[1] for line in text.splitlines() if line.startswith("RABBITMQ_PASSWORD="))
    assert password and password not in capsys.readouterr().out
