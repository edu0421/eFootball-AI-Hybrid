# eFootball AI Hybrid (unified Android app)

One Android Studio project: Kotlin shell + floating overlay + screen capture, with all logic
(analysis, SQLite, formations) in a Python package run by Chaquopy.

    app/src/main/java/com/efai/hybrid/   Kotlin: MainActivity (Assistant/Matches/Tactics tabs),
                                         AssistantService, ScreenCapture, FloatingPanel,
                                         PythonBridge, Prefs, Ui, MatchesView, TacticsView
    app/src/main/python/efai/            Python: api.py (facade), analysis.py (OpenCV),
                                         database.py (SQLite), tactics.py
    python_tests/                        PC-runnable tests: python -m unittest discover -s python_tests -t .

AI Assist only. No input injection, no automation.
Build: Android Studio + JDK 17 + Python 3.11 on the PC; see app/build.gradle.kts for the
buildPython line and the opencv-python pin note.
