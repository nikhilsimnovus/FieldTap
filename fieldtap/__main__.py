"""`python -m fieldtap`: the same commands as the installed `fieldtap` script (pyproject.toml), without installing.

The Android end-to-end proof (android/e2e/run_e2e.sh) runs `python -m fieldtap validate DIR --upload` and
`python -m fieldtap report DIR` from the repository root this way.
"""
from .cli import main

raise SystemExit(main())
