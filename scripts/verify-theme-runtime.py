#!/usr/bin/env python3
"""Run Android skin/SVG/IFF raster checks against this checkout's built APK (Termux)."""
from pathlib import Path
import argparse, hashlib, os, shutil, subprocess, tempfile, zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, default=root/'build/outputs/apk/debug/InfiniMedia-debug.apk')
parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', Path.home()/'android-sdk')))
parser.add_argument('--output', type=Path, default=root/'build/theme-runtime')
parser.add_argument('--decoder-only', action='store_true', help='Skip host playback scheduler/lifecycle checks')
args = parser.parse_args()
android = args.sdk/'platforms/android-36/android.jar'
def compile_jar(path):
    return root/path/'build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar'
jars = [compile_jar(Path('libraries/libinfinicanvas/canvas-android')),
        compile_jar(Path('libraries/libinfinicore/core-android')),
        compile_jar(Path('libraries/libinfiniui/ui-android')),
        compile_jar(Path('modules/viewer-audio')),
        root/'libraries/libinfinistream/stream-core/build/libs/stream-core-0.1.0.jar',
        next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/*.jar'))]
for path in [args.apk, android, *jars]:
    if not path.is_file(): parser.error('Build this checkout first; missing '+str(path))
if not Path('/system/bin/app_process64').is_file(): parser.error('Requires a local Android app_process64 runtime')
args.output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='infini-theme-runtime-') as directory:
    work=Path(directory);classes=work/'classes';dex=work/'dex';classes.mkdir();dex.mkdir()
    svg_aar=next((Path.home()/'.gradle/caches/modules-2/files-2.1/com.caverock/androidsvg-aar/1.4').glob('*/*.aar'))
    with zipfile.ZipFile(svg_aar) as archive:
        svg_jar=work/'androidsvg.jar';svg_jar.write_bytes(archive.read('classes.jar'));jars.append(svg_jar)
    subprocess.run(['javac','--release','17','-cp',os.pathsep.join(map(str,[android,*jars])),
                    '-d',str(classes),str(root/'scripts/runtime/ThemeRuntimeCheck.java')],check=True)
    subprocess.run([str(args.sdk/'build-tools/35.0.0/d8'),'--min-api','26','--lib',str(android),
                    *[item for jar in jars for item in ('--classpath',str(jar))],
                    '--output',str(dex),*map(str,classes.glob('*.class'))],check=True)
    (dex/'classes.dex').chmod(0o444)
    apk=work/'runtime.apk';shutil.copyfile(args.apk,apk);apk.chmod(0o444)
    env=os.environ.copy();env.pop('LD_PRELOAD',None);env.pop('LD_LIBRARY_PATH',None)
    env['CLASSPATH']=os.pathsep.join([str(dex/'classes.dex'),str(apk)])
    env['INFINI_TEST_APK']=str(apk)
    subprocess.run(['/system/bin/app_process64','/system/bin','ThemeRuntimeCheck',str(root),str(args.output.resolve()),*(['decoder'] if args.decoder_only else [])],env=env,check=True,timeout=120)
print('APK SHA-256:',hashlib.sha256(args.apk.read_bytes()).hexdigest())
