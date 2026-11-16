# Build eFootball AI Hybrid using only your Android phone

This archive includes a GitHub Actions workflow so the APK can be compiled in the cloud.
You can upload the source from your phone and download the APK when the build completes.

## Phone-only steps

1. Extract this ZIP using Samsung My Files. Open the `unified` folder.
2. In your phone browser, sign in to GitHub and create a new repository named `eFootball-AI-Hybrid`.
3. Upload the **contents of the `unified` folder** to the repository root (including the hidden `.github` folder and its workflow file). Do not upload only the ZIP.
4. Open the repository's **Actions** tab. If prompted, enable GitHub Actions.
5. Select **Build Android APK** and tap **Run workflow**. A push to the `main` branch also triggers a build.
6. Open the completed workflow run. Under **Artifacts**, download `eFootball-AI-Hybrid-debug-apk`.
7. Extract the downloaded artifact ZIP, then tap `app-debug.apk` to install it. Android may ask you to allow installation from that browser or file manager.

## Important

- The build runs on GitHub's hosted computer; your phone is used to upload the project and download the APK.
- This workflow is a build setup, not proof that the APK will compile. If the build fails, open the failed run, copy the first meaningful error from the build log, and share it for troubleshooting.
- The current project README says the build expects Android Studio/JDK 17/Python 3.11 on a PC; this workflow supplies those build tools in the cloud.
- The app's Python requirements include NumPy and `opencv-python`. Chaquopy wheel availability may cause the first cloud build to fail; if it does, share the log so the dependency can be adjusted.
- Android screen capture still requires you to approve the system permission prompt. The app is an AI analysis assistant; it does not control eFootball.
