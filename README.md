# Android Master Template

## How to Use
1. Fork this repo for each new app
2. Use ChatGPT prompt to generate new app files
3. Replace files + add new code
4. Push to GitHub
5. Download APK from Actions tab

## Build Info
- Auto-builds on push to main/master
- Manual trigger: Actions → Run workflow
- Download: Actions → Artifacts → Signed-APK

----------------------------------------------------------------------------------------------------
----------------------------------------------------------------------------------------------------

## PROMPT

You are an expert Android developer. I have a master Android template repository and I need to create a new Android app.

BEFORE generating any code, you MUST ask me these 2 questions ONE BY ONE and wait for my answers:

Question 1: "What is your APP NAME?" (e.g., Calculator, Weather, Notes)
(Wait for my answer)

Question 2: "Describe your app in detail - what should it DO? What features? What should the UI look like?"
(Wait for my answer)

*AI Instruction: Auto-generate the package name (format: com.example.[lowercaseappnamewithoutspaces]) based on the app name I provide. Do not ask me for the package name or any other details.*

Wait for my answers to BOTH questions before generating anything.

AFTER I answer, generate the COMPLETE content for EXACTLY these 7 files. DO NOT generate, suggest, or create any extra files (no extra XML layouts, no extra Kotlin files, no image/mipmap assets). All app logic, features, and UI must be implemented strictly within these 7 files (use inner classes, programmatic UI generation for dynamic list items, and single-activity architecture to keep it contained).

📁 CONFIG FILES (5 files):
1. settings.gradle → Update `rootProject.name`. CRITICAL: Use `RepositoriesMode.PREFER_SETTINGS` (NOT `FAIL_ON_PROJECT_REPOS`) to prevent conflicts with any existing repository declarations in the root `build.gradle`.
2. app/build.gradle → Change `namespace` and `applicationId` ONLY. Do not modify the `plugins` block or existing repository configurations.
3. app/src/main/AndroidManifest.xml → Update theme name to match app. Add any strictly necessary permissions (e.g., READ_MEDIA_AUDIO). CRITICAL: Remove `android:icon` and `android:roundIcon` attributes from the `<application>` tag to prevent resource linking errors if the master template lacks default mipmap resources.
4. app/src/main/res/values/strings.xml → Update `app_name` string.
5. app/src/main/res/values/themes.xml → Update theme name to match app.

📁 APP CODE FILES (2 files):
6. app/src/main/java/[package-path]/MainActivity.kt → FULL working code based on my description. Include all necessary logic, adapters, listeners, and programmatic UI (if needed to avoid creating extra XML files) inside this single file.
7. app/src/main/res/layout/activity_main.xml → FULL layout based on my description.

OUTPUT FORMAT:
- Give each file with its FULL PATH clearly labeled.
- Make sure ALL package names match exactly.
- Make MainActivity.kt contain REAL, compilable, working code that implements my description.
- Keep code simple, clean, and working. Use Material Design components and Kotlin. Include proper imports.
- STRICTLY DO NOT output any files outside the 7 listed above.

At the end, give me a SHORT checklist of:
- Which files to replace
- Exact steps to deploy

Keep everything minimal and working. Do not over-engineer. Ensure 0 build errors related to missing resources or repository conflicts.
