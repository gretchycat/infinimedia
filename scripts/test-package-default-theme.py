#!/usr/bin/env python3
from pathlib import Path
import importlib.util,hashlib,tempfile,unittest,zipfile
spec=importlib.util.spec_from_file_location('packager',Path(__file__).with_name('package-default-theme.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class PackagingTest(unittest.TestCase):
    def test_deterministic_and_preserves_source_bytes(self):
        source=Path(__file__).resolve().parents[1]/'theme'
        with tempfile.TemporaryDirectory() as tmp:
            a=Path(tmp)/'a.wsz';b=Path(tmp)/'b.wsz';m.package(source,a);m.package(source,b)
            self.assertEqual(a.read_bytes(),b.read_bytes())
            with zipfile.ZipFile(a) as archive:
                self.assertIn('theme.json',archive.namelist())
                for name in archive.namelist():self.assertEqual((source/name).read_bytes(),archive.read(name))
                self.assertNotIn('README.md',archive.namelist())
    def test_missing_layer_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'theme.json').write_text('{"images":{"main":"missing.iff"}}')
            with self.assertRaises(ValueError):m.package(root,root/'output.wsz')
    def test_authoring_files_and_backups_are_not_packaged(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'theme.json').write_text('{"images":{"main":"main.iff"}}')
            for name in ('main.iff','main.bmp','main.bmp.backup','svg-to-iff.py','glyph-check.png','eqmain_iso.bmp'):(root/name).write_bytes(b'fixture')
            m.package(root,root/'output.wsz')
            with zipfile.ZipFile(root/'output.wsz') as archive:self.assertEqual(['main.iff','theme.json'],archive.namelist())
if __name__=='__main__':unittest.main()
