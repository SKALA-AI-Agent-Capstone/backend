import ast

from capstone_agent.instructions import PROMPTS, instruction


def test_every_model_prompt_is_loaded_from_a_prompt_file():
    files = {p.stem for p in PROMPTS.glob("*.md") if p.name != "README.md"}
    used = set()
    for module in ("review_engine.py", "checks.py"):
        tree = ast.parse((PROMPTS.parent / module).read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "parse" \
                    and isinstance(node.func.value, ast.Name) and node.func.value.id == "provider":
                prompt = node.args[0]
                assert isinstance(prompt, ast.Call) and prompt.func.id == "instruction", f"inline prompt in {module}"
                used.add(prompt.args[0].value)
    assert used == files
    for name in files:
        text = instruction(name)
        assert text and "\n" not in text and "  " not in text
