#!/usr/bin/env bash
# Run only after build + test + lint succeed. No signing keys or subscription data are archived.
set -euo pipefail
cd "$(dirname "$0")/.."
output_dir="${1:-dist}"
mkdir -p "$output_dir"
cp app/build/outputs/apk/debug/app-debug.apk "$output_dir/moonshot-test.apk"
cp app/build/outputs/apk/release/app-release-unsigned.apk "$output_dir/moonshot-release-unsigned.apk"
"${ANDROID_HOME:?}/build-tools/35.0.0/apksigner" verify --verbose \
  "$output_dir/moonshot-test.apk" > "$output_dir/apk-signature.txt"
git archive --format=zip --prefix=moonshot/ HEAD > "$output_dir/moonshot-source.zip"
python3 - "$output_dir" <<'PY'
import hashlib, json, pathlib, subprocess, sys, xml.etree.ElementTree as ET, zipfile
out = pathlib.Path(sys.argv[1])
reports = list(pathlib.Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
if not reports:
    raise SystemExit('Missing test results')
totals = {key: sum(int(ET.parse(p).getroot().get(key, 0)) for p in reports)
          for key in ['tests', 'failures', 'errors', 'skipped']}
if totals['failures'] or totals['errors'] or totals['tests'] == 0:
    raise SystemExit('Unit tests did not pass')
with zipfile.ZipFile(out / 'moonshot-validation.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for pattern in ['app/build/test-results/testDebugUnitTest/TEST-*.xml',
                    'app/build/reports/lint-results-*.html',
                    'app/build/reports/lint-results-*.txt', 'docs/*.md']:
        for p in pathlib.Path('.').glob(pattern):
            z.write(p)
manifest = {'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
            'unit_tests': totals, 'test_apk_signature': 'development signing; verified',
            'release_apk': 'unsigned; cannot install until signed',
            'physical_device_acceptance': 'not certified by this workflow',
            'provider_acceptance': 'not certified by this workflow'}
(out / 'build-info.json').write_text(json.dumps(manifest, indent=2) + '\n')
files = sorted(p for p in out.iterdir() if p.is_file() and p.name != 'SHA256SUMS')
(out / 'SHA256SUMS').write_text(''.join(
    f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in files))
PY
