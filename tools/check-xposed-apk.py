#!/usr/bin/env python3
"""Verify modern Xposed discovery and loadability in the APK, including R8 output."""

import argparse
import re
import struct
from pathlib import Path
from zipfile import ZipFile


class Dex:
    def __init__(self, data):
        self.data = data
        assert data.startswith(b"dex\n"), "Not a DEX file"
        string_count, string_offset = self.words(56, 2)
        self.strings = []
        for offset in self.words(string_offset, string_count):
            _, start = self.uleb(offset)
            end = data.index(b"\0", start)
            self.strings.append(data[start:end].decode("utf-8", errors="replace"))
        type_count, type_offset = self.words(64, 2)
        self.types = [self.strings[i] for i in self.words(type_offset, type_count)]
        _, self.proto_offset = self.words(72, 2)
        _, self.method_offset = self.words(88, 2)
        class_count, class_offset = self.words(96, 2)
        self.classes = {}
        for offset in range(class_offset, class_offset + class_count * 32, 32):
            entry = self.words(offset, 8)
            self.classes[self.types[entry[0]]] = entry

    def words(self, offset, count):
        return struct.unpack_from("<" + "I" * count, self.data, offset)

    def uleb(self, offset):
        result = shift = 0
        while True:
            value = self.data[offset]
            offset += 1
            result |= (value & 0x7F) << shift
            if not value & 0x80:
                return result, offset
            shift += 7
            assert shift < 35, "Invalid ULEB128"

    def methods(self, class_entry):
        offset = class_entry[6]
        if not offset:
            return
        counts = []
        for _ in range(4):
            count, offset = self.uleb(offset)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, offset = self.uleb(offset)
            _, offset = self.uleb(offset)
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                delta, offset = self.uleb(offset)
                flags, offset = self.uleb(offset)
                _, offset = self.uleb(offset)
                index += delta
                _, proto, name = struct.unpack_from("<HHI", self.data, self.method_offset + index * 8)
                _, _, parameters = self.words(self.proto_offset + proto * 12, 3)
                yield self.strings[name], flags, parameters


def verify(path):
    entry_name = "org.matrix.chromext.MainHook"
    entry_type = "L" + entry_name.replace(".", "/") + ";"
    api_prefix = "Lio/github/libxposed/api/"
    with ZipFile(path) as apk:
        names = set(apk.namelist())
        assert "assets/xposed_init" not in names, "Legacy module entry is still packaged"
        base = "META-INF/xposed/"
        assert apk.read(base + "java_init.list").decode().split() == [entry_name], "Incorrect module entry"
        assert apk.read(base + "scope.list").decode().split() == ["com.android.chrome"], "Incorrect scope"
        properties = dict(line.split("=", 1) for line in apk.read(base + "module.prop").decode().splitlines()
                          if line.strip() and not line.startswith("#"))
        assert properties["minApiVersion"] == properties["targetApiVersion"] == "102", "Incorrect API level"
        assert properties["autoHotReload"] == "false", "Hot reload requires explicit lifecycle support"
        manifest = apk.read("AndroidManifest.xml")
        for old in ("xposedmodule", "xposeddescription", "xposedminversion", "xposedscope"):
            assert old.encode() not in manifest and old.encode("utf-16-le") not in manifest, \
                "Legacy manifest metadata remains: " + old

        found = False
        for name in sorted(names):
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            dex = Dex(apk.read(name))
            assert not any(t.startswith("Lde/robv/android/xposed/") or
                           t == "Landroid/app/AndroidAppHelper;" for t in dex.types), \
                "Legacy framework reference remains in " + name
            assert not any(t.startswith(api_prefix) for t in dex.classes), \
                "Framework API is bundled instead of compileOnly in " + name
            if entry_type in dex.classes:
                entry = dex.classes[entry_type]
                assert dex.types[entry[2]] == api_prefix + "XposedModule;", "Incorrect entry superclass"
                assert entry[1] & 1 and not entry[1] & 0x400, "Entry must be public and concrete"
                methods = list(dex.methods(entry))
                assert any(n == "<init>" and f & 1 and p == 0 for n, f, p in methods), \
                    "Public no-argument entry constructor was removed"
                assert {"onModuleLoaded", "onPackageReady"} <= {n for n, _, _ in methods}, \
                    "Module lifecycle callbacks were removed"
                found = True
        assert found, "Module entry named by java_init.list is missing from DEX"
    print(f"PASS {path}: API 102 metadata and loadable entry; no legacy references or bundled API")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="+")
    for apk_path in parser.parse_args().apk:
        verify(apk_path)
