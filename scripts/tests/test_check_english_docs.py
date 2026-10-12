"""Tests for scripts/check-english-docs.py."""

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]


def load_script():
    spec = importlib.util.spec_from_file_location("check_english_docs", SCRIPTS / "check-english-docs.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


docs = load_script()


class CheckEnglishDocsTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "docs" / "en").mkdir(parents=True)
        (self.root / "docs" / "zh").mkdir(parents=True)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, relative, text):
        path = self.root / relative
        path.write_text(text, encoding="utf-8")
        return path

    def test_han_in_english_markdown_is_a_violation(self):
        self.write("docs/en/guide.md", "# Guide\n交互\n")
        self.assertEqual(docs.violations(self.root), ["docs/en/guide.md:2:交互"])

    def test_han_outside_an_english_location_is_allowed(self):
        self.write("docs/zh/guide.md", "# 指南\n")
        self.write("docs/zh/page.html", "<html lang=\"en\"><title>演示</title></html>")
        self.assertEqual(docs.violations(self.root), [])

    def test_han_in_an_english_html_page_is_a_violation(self):
        self.write("docs/en/page.html", "<html lang=\"en\">\n<title>演示</title>\n</html>\n")
        self.assertEqual(docs.violations(self.root), ["docs/en/page.html:2:<title>演示</title>"])

    def test_an_html_page_without_a_language_is_treated_as_english(self):
        self.write("docs/en/page.html", "<html>\n<p>演示</p>\n</html>\n")
        self.assertEqual(len(docs.violations(self.root)), 1)

    def test_an_html_page_declaring_another_language_is_allowed(self):
        self.write("docs/en/demo.html", "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<title>演示</title>\n")
        self.assertEqual(docs.violations(self.root), [])

    def test_build_output_is_ignored(self):
        (self.root / "target" / "en").mkdir(parents=True)
        self.write("target/en/page.html", "<html lang=\"en\"><p>演示</p></html>")
        self.assertEqual(docs.violations(self.root), [])


if __name__ == "__main__":
    unittest.main()
