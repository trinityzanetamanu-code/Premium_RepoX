#!/usr/bin/env python3
"""Compile the production parser/policy itself; no mirrored implementation or Android stubs.

Fixtures are synthetic. This checks code, NOT live extraction or playback.
Downloads pinned compiler dependencies from Maven Central into a reusable cache.
Requires Java 17+ and Python 3. Run: python3 KuraKura21/tests/run_policy_tests.py
"""
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

root = Path(__file__).resolve().parent
cache = Path(os.environ.get('KURA_TEST_DEPS', Path.home() / '.cache' / 'kurakura21-policy-tests'))
cache.mkdir(parents=True, exist_ok=True)
artifacts = {
    'compiler.jar': 'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.0/kotlin-compiler-embeddable-2.4.0.jar',
    'stdlib.jar': 'org/jetbrains/kotlin/kotlin-stdlib/2.4.0/kotlin-stdlib-2.4.0.jar',
    'script-runtime.jar': 'org/jetbrains/kotlin/kotlin-script-runtime/2.4.0/kotlin-script-runtime-2.4.0.jar',
    'reflect.jar': 'org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar',
    'coroutines.jar': 'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar',
    'annotations.jar': 'org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar',
    'jsoup.jar': 'org/jsoup/jsoup/1.22.2/jsoup-1.22.2.jar',
    'json.jar': 'org/json/json/20250517/json-20250517.jar',
}
for name, path in artifacts.items():
    target = cache / name
    if not target.is_file():
        temporary = target.with_suffix('.download')
        urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/' + path, temporary)
        temporary.replace(target)
classpath = os.pathsep.join(str(cache / name) for name in artifacts)
production = (root.parent / 'src/main/kotlin/com/KuraKura21/KuraKura21Playback.kt').read_text()
# Compile these exact production declarations in a JVM harness. Android integration
# is compiled separately by the existing repository build.
policy = production[production.index('internal object KuraKura21PlayerData'):]
with tempfile.TemporaryDirectory(prefix='kurakura21-tests-') as directory:
    work = Path(directory)
    source = work / 'ProductionPolicy.kt'
    source.write_text('package com.kurakura21\nimport java.net.URI\nimport java.util.Collections\nimport org.jsoup.Jsoup\nimport org.json.JSONObject\n' + policy)
    subprocess.run(['java', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', classpath,
                    '-d', str(work / 'classes'), str(source), str(root / 'PlaybackPolicyTest.kt')], check=True)
    subprocess.run(['java', '-cp', str(work / 'classes') + os.pathsep + classpath,
                    'com.kurakura21.PlaybackPolicyTestKt'], check=True)
