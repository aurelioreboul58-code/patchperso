# Building the custom Piko patch

The project uses Morphe's Gradle patch plugin. The official plugin is distributed through GitHub Packages, so a local build needs GitHub package credentials. The repository already reads them from Gradle properties `gpr.user` and `gpr.key`, or from environment variables `GITHUB_ACTOR` and `GITHUB_TOKEN`.

Keep credentials outside this repository. For example, add your own values to `%USERPROFILE%\.gradle\gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```

Then, from the project directory, run:

```powershell
.\gradlew.bat :patches:buildAndroid
```

The Morphe patch bundle is written under `patches\build\libs\`. Give that `.mpp` bundle to Morphe together with the original X 12.19.1 APKM. The `buildAndroid` task is required because it adds the Android DEX entries Morphe Manager loads.
