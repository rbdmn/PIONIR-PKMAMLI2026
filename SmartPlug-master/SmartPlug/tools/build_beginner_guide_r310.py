"""Build the revised user guide, preserving the R3.9 PDF."""
from pathlib import Path
import runpy

runpy.run_path(str(Path(__file__).with_name('build_beginner_guide_r390.py')),
              init_globals={'GUIDE_REVISION': '3.10', 'ANDROID_GUIDE': True})
