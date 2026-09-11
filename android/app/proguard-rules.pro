# R8 rules for the release build of 5gto6G FieldTap.
#
# None is needed today:
# - session.json and the settings are read through kotlinx-serialization's JsonElement tree and written by the app's
#   own JsonText, so no serializer is looked up by name;
# - the app uses no reflection of its own;
# - AndroidX, Compose, coroutines and kotlinx-serialization ship consumer rules for what they need;
# - error texts written to session files name only java.* and android.* exception classes, which R8 never renames.
#
# Add a rule only with the reason it is needed, and prove it with android/e2e/release_smoke.sh on the CI emulators.
