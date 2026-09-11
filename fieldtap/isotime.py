"""ISO-8601 timestamps as the session files carry them.

`datetime.fromisoformat` accepts a trailing "Z" only from Python 3.11. The
package supports 3.9, and other writers (the Android app, anything using
`Instant.toString()`) produce "Z", so every reader of a `*_utc` value goes
through `parse_iso`, which maps a trailing "Z" to "+00:00" and otherwise
behaves exactly like `datetime.fromisoformat`.
"""

from __future__ import annotations

from datetime import datetime


def parse_iso(text: str) -> datetime:
    """`datetime.fromisoformat`, with a trailing "Z" read as "+00:00".

    Raises ValueError for anything fromisoformat rejects, and TypeError for a
    value that is not a string, just as fromisoformat does.
    """
    if isinstance(text, str) and text.endswith("Z"):
        text = text[:-1] + "+00:00"
    return datetime.fromisoformat(text)
